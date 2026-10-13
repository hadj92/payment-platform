#!/usr/bin/env python3
"""
Revue de code assistée par IA, spécialisée paiement.

Lit le diff d'une pull request, applique la politique de `.github/ai-review.yml`,
demande une relecture à Claude et publie le résultat en commentaire de PR.

Ce que ce script N'EST PAS :

  * un remplacement de la revue humaine. PCI DSS req. 6.2.3 impose qu'un code
    touchant au périmètre carte soit relu par quelqu'un d'autre que son auteur.
    Un modèle n'est pas quelqu'un : il n'engage pas sa responsabilité et ne
    répond pas devant un auditeur. Ce script produit une PRÉ-revue, non bloquante.

  * un linter. Checkstyle, CodeQL, Checkov et Trivy tournent déjà et font mieux
    que lui sur ce qu'ils couvrent. Ce script cherche les fautes de DOMAINE que
    l'analyse statique ne voit pas : une capture non idempotente, un montant en
    flottant, un PAN qui part dans un log.

Ordre des opérations, et il compte : la politique de confidentialité s'applique
AVANT tout appel réseau. Un fichier exclu n'est pas « filtré de la réponse », il
n'est jamais transmis.

Usage :
    ANTHROPIC_API_KEY=... python ai_review.py --base origin/main --head HEAD
    python ai_review.py --self-test        # vérifie les gardes, sans réseau
"""

from __future__ import annotations

import argparse
import fnmatch
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

MODELE = "claude-opus-5"

# Tarifs au million de tokens, pour afficher ce que la relecture a coûté.
# Une dépense invisible est une dépense que personne n'arbitre.
PRIX_ENTREE_PAR_MTOK = 5.00
PRIX_SORTIE_PAR_MTOK = 25.00


# ---------------------------------------------------------------- politique


@dataclass
class Politique:
    chemins_exclus: list[str] = field(default_factory=list)
    motifs_interdits: list[tuple[str, re.Pattern[str]]] = field(default_factory=list)
    max_lignes_diff: int = 1500
    max_fichiers: int = 40
    afficher_cout: bool = True
    regles: list[dict] = field(default_factory=list)
    hors_perimetre: list[str] = field(default_factory=list)

    @classmethod
    def charger(cls, chemin: Path) -> "Politique":
        """
        Lit la politique sans dépendance YAML.

        Le format est volontairement plat et le parseur minimal : ajouter PyYAML
        à une étape de CI qui manipule du code sensible, c'est ajouter une
        dépendance tierce de plus à surveiller pour lire quarante lignes de
        configuration.
        """
        texte = chemin.read_text(encoding="utf-8")
        p = cls()

        p.chemins_exclus = _liste_simple(texte, "chemins_exclus")
        p.hors_perimetre = _liste_simple(texte, "hors_perimetre")

        for nom, motif in _paires(texte, "motifs_interdits", "nom", "regex"):
            p.motifs_interdits.append((nom, re.compile(motif)))

        for cle, defaut in (("max_lignes_diff", 1500), ("max_fichiers", 40)):
            m = re.search(rf"^\s*{cle}:\s*(\d+)", texte, re.M)
            setattr(p, cle, int(m.group(1)) if m else defaut)

        p.regles = _regles(texte)
        return p


def _liste_simple(texte: str, cle: str) -> list[str]:
    """Éléments d'une liste YAML de chaînes, sous la clé donnée."""
    m = re.search(rf"^\s*{cle}:\s*$", texte, re.M)
    if not m:
        return []
    valeurs = []
    for ligne in texte[m.end():].splitlines()[1:]:
        if ligne.strip().startswith("#") or not ligne.strip():
            continue
        item = re.match(r'^\s+-\s+"?([^"#]+?)"?\s*$', ligne)
        if not item:
            break
        valeurs.append(item.group(1).strip())
    return valeurs


def _paires(texte: str, cle: str, champ_a: str, champ_b: str) -> list[tuple[str, str]]:
    """Entrées d'une liste d'objets à deux champs."""
    m = re.search(rf"^\s*{cle}:\s*$", texte, re.M)
    if not m:
        return []
    bloc = texte[m.end():]
    fin = re.search(r"^\w", bloc, re.M)
    if fin:
        bloc = bloc[: fin.start()]
    sorties, courant = [], {}
    for ligne in bloc.splitlines():
        a = re.match(rf'^\s*-\s*{champ_a}:\s*"?(.+?)"?\s*$', ligne)
        b = re.match(rf"^\s*{champ_b}:\s*'(.+)'\s*$", ligne)
        if a:
            courant = {champ_a: a.group(1)}
        elif b and courant:
            sorties.append((courant[champ_a], b.group(1)))
            courant = {}
    return sorties


def _regles(texte: str) -> list[dict]:
    """Règles de domaine : id, poids, énoncé (bloc replié `>-`)."""
    m = re.search(r"^\s*regles:\s*$", texte, re.M)
    if not m:
        return []
    bloc = texte[m.end():]
    fin = re.search(r"^\s{2}hors_perimetre:", bloc, re.M)
    if fin:
        bloc = bloc[: fin.start()]

    regles = []
    for morceau in re.split(r"^\s*-\s+id:\s*", bloc, flags=re.M)[1:]:
        lignes = morceau.splitlines()
        rid = lignes[0].strip()
        poids = next(
            (l.split(":", 1)[1].strip() for l in lignes if l.strip().startswith("poids:")),
            "majeur",
        )
        enonce = " ".join(
            l.strip()
            for l in lignes[lignes.index(next(l for l in lignes if "enonce:" in l)) + 1:]
            if l.strip() and not l.strip().startswith("-")
        )
        regles.append({"id": rid, "poids": poids, "enonce": enonce})
    return regles


# ---------------------------------------------------------------- diff


@dataclass
class Diff:
    par_fichier: dict[str, str]
    ignores: list[tuple[str, str]]   # (chemin, raison)
    lignes: int

    def vide(self) -> bool:
        return not self.par_fichier


def lire_diff(base: str, head: str) -> dict[str, str]:
    """Diff unifié, découpé par fichier."""
    brut = subprocess.run(
        ["git", "diff", "--unified=5", f"{base}...{head}"],
        capture_output=True, text=True, check=True,
    ).stdout

    par_fichier: dict[str, str] = {}
    chemin, tampon = None, []
    for ligne in brut.splitlines(keepends=True):
        if ligne.startswith("diff --git "):
            if chemin:
                par_fichier[chemin] = "".join(tampon)
            m = re.search(r" b/(.+)$", ligne.rstrip())
            chemin, tampon = (m.group(1) if m else "?"), [ligne]
        elif chemin:
            tampon.append(ligne)
    if chemin:
        par_fichier[chemin] = "".join(tampon)
    return par_fichier


def appliquer_politique(par_fichier: dict[str, str], p: Politique) -> Diff:
    """
    Retire ce qui ne doit pas sortir du périmètre.

    Deux niveaux qui ne font pas le même travail :
      - les chemins exclus protègent de ce qu'on a prévu ;
      - les motifs interdits protègent du fichier que personne n'avait anticipé,
        et c'est celui-là qui fuite en pratique.

    Les fichiers retirés sont RAPPORTÉS, jamais silencieusement omis : une
    protection invisible ne se vérifie pas.
    """
    gardes, ignores = {}, []

    for chemin, diff in par_fichier.items():
        exclu = next(
            (motif for motif in p.chemins_exclus
             if fnmatch.fnmatch(chemin, motif) or fnmatch.fnmatch(f"/{chemin}", motif)),
            None,
        )
        if exclu:
            ignores.append((chemin, f"chemin exclu par la politique ({exclu})"))
            continue

        trouve = next((nom for nom, rx in p.motifs_interdits if rx.search(diff)), None)
        if trouve:
            ignores.append((chemin, f"motif interdit detecte : {trouve}"))
            continue

        gardes[chemin] = diff

    lignes = sum(
        1 for d in gardes.values() for l in d.splitlines()
        if l.startswith(("+", "-")) and not l.startswith(("+++", "---"))
    )
    return Diff(par_fichier=gardes, ignores=ignores, lignes=lignes)


# ---------------------------------------------------------------- invite


SYSTEME = """Tu relis une pull request sur une plateforme de paiement par carte \
destinee au secteur hotelier. Tu produis une PRE-revue : un ingenieur relira \
derriere toi et tranchera.

Ton travail n'est pas de trouver des defauts de style. Un formateur, CodeQL, \
Checkov et Trivy tournent deja sur cette base de code et font mieux que toi sur \
ce qu'ils couvrent. Tu cherches les fautes de DOMAINE que l'analyse statique ne \
voit pas, et uniquement celles-la.

Regles de ce projet, par ordre de gravite :

{regles}

Tu ne signales JAMAIS :
{hors_perimetre}

Deux consignes qui priment sur le reste :

1. Un signalement faux coute plus cher qu'un signalement manque. Une revue \
bruyante est ignoree au bout de deux semaines, et plus personne ne lit les trois \
remarques qui comptaient. Dans le doute, tu te tais.

2. Tu ne signales que ce que le diff montre. Si une regle semble violee mais que \
la preuve est dans un fichier absent du diff, tu ne signales pas — tu n'as pas le \
contexte pour trancher.

Pour chaque signalement : le fichier, la ligne approximative, la regle concernee, \
ce qui ne va pas, et le scenario concret dans lequel ca casse. Le scenario est \
obligatoire : s'il n'y en a pas, c'est que le signalement n'en est pas un."""


INVITE = """Voici le diff de la pull request.

{diff}

Relis-le selon les regles du projet. S'il n'y a rien a signaler, renvoie une \
liste vide : c'est une reponse parfaitement valable et frequente."""


SCHEMA = {
    "type": "object",
    "properties": {
        "signalements": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "fichier": {"type": "string"},
                    "ligne": {"type": "integer"},
                    "regle": {"type": "string"},
                    "gravite": {"type": "string", "enum": ["critique", "majeur", "mineur"]},
                    "probleme": {"type": "string"},
                    "scenario": {"type": "string"},
                    "suggestion": {"type": "string"},
                },
                "required": ["fichier", "ligne", "regle", "gravite", "probleme",
                             "scenario", "suggestion"],
                "additionalProperties": False,
            },
        },
        "synthese": {"type": "string"},
    },
    "required": ["signalements", "synthese"],
    "additionalProperties": False,
}


def demander_revue(diff: Diff, p: Politique) -> tuple[dict, dict]:
    """Interroge le modele. Renvoie (resultat, usage)."""
    import anthropic

    client = anthropic.Anthropic()

    regles = "\n".join(
        f"- [{r['poids'].upper()}] {r['id']} : {r['enonce']}" for r in p.regles
    )
    hors = "\n".join(f"- {h}" for h in p.hors_perimetre)
    corpus = "\n\n".join(
        f"=== {chemin} ===\n{d}" for chemin, d in diff.par_fichier.items()
    )

    reponse = client.messages.create(
        model=MODELE,
        max_tokens=8000,
        system=SYSTEME.format(regles=regles, hors_perimetre=hors),
        messages=[{"role": "user", "content": INVITE.format(diff=corpus)}],
        # Le schema garantit un JSON exploitable : sans lui, il faudrait parser
        # du texte libre et la CI casserait sur une reponse mal formee.
        output_config={"format": {"type": "json_schema", "schema": SCHEMA}},
    )

    texte = next(b.text for b in reponse.content if b.type == "text")
    usage = {
        "entree": reponse.usage.input_tokens,
        "sortie": reponse.usage.output_tokens,
    }
    return json.loads(texte), usage


# ---------------------------------------------------------------- rapport


GRAVITES = {"critique": "🔴 critique", "majeur": "🟠 majeur", "mineur": "🔵 mineur"}


def rendre(resultat: dict, diff: Diff, usage: dict | None, p: Politique) -> str:
    lignes = ["## Pré-revue assistée", ""]

    signalements = resultat.get("signalements", [])
    if not signalements:
        lignes += ["Rien à signaler sur les règles du projet.", ""]
    else:
        ordre = {"critique": 0, "majeur": 1, "mineur": 2}
        for s in sorted(signalements, key=lambda x: ordre.get(x["gravite"], 3)):
            lignes += [
                f"### {GRAVITES.get(s['gravite'], s['gravite'])} — `{s['fichier']}`"
                f" ligne ~{s['ligne']}",
                "",
                f"**Règle `{s['regle']}`** — {s['probleme']}",
                "",
                f"> **Ce qui casse :** {s['scenario']}",
                "",
                f"**Piste :** {s['suggestion']}",
                "",
            ]

    if resultat.get("synthese"):
        lignes += ["---", "", resultat["synthese"], ""]

    # Ce qui n'a pas ete transmis doit se voir : une protection silencieuse ne
    # se verifie pas, et personne ne saurait qu'un fichier a ete saute.
    if diff.ignores:
        lignes += ["<details><summary>Fichiers non transmis au modèle"
                   f" ({len(diff.ignores)})</summary>", ""]
        lignes += [f"- `{c}` — {raison}" for c, raison in diff.ignores]
        lignes += ["", "</details>", ""]

    pied = [f"{diff.lignes} lignes relues"]
    if usage and p.afficher_cout:
        cout = (usage["entree"] / 1e6 * PRIX_ENTREE_PAR_MTOK
                + usage["sortie"] / 1e6 * PRIX_SORTIE_PAR_MTOK)
        pied.append(f"{usage['entree'] + usage['sortie']} tokens, ~{cout:.3f} $")

    lignes += [
        "---",
        "",
        f"<sub>{' · '.join(pied)} · modèle `{MODELE}`</sub>",
        "",
        "<sub>**Cette revue ne remplace pas une relecture humaine.** PCI DSS "
        "req. 6.2.3 exige qu'un code touchant au périmètre carte soit relu par "
        "une personne autre que son auteur. Cette pré-revue débroussaille ; "
        "elle n'approuve rien.</sub>",
    ]
    return "\n".join(lignes)


# ---------------------------------------------------------------- auto-test


def auto_test(p: Politique) -> int:
    """
    Vérifie les gardes sans toucher au réseau.

    Un script de sécurité qu'on ne teste pas est une intention, pas un contrôle.
    Ce test tourne dans la CI avant la revue elle-même : si une garde cède, la
    revue ne part pas.
    """
    cas = [
        ("infra/envs/prod/terraform.tfvars", "+secret = 1\n", "chemin exclu"),
        ("src/test/fixtures/cartes.json", "+{}\n", "chemin exclu"),
        ("src/Paiement.java", "+String pan = \"4111111111111111\";\n", "motif interdit"),
        ("deploy.sh", "+AWS_KEY=AKIAIOSFODNN7EXAMPLE\n", "motif interdit"),
        ("cle.txt", "+-----BEGIN RSA PRIVATE KEY-----\n", "motif interdit"),
    ]
    ok = True
    print("Auto-test des gardes de confidentialité\n")
    for chemin, contenu, attendu in cas:
        resultat = appliquer_politique({chemin: contenu}, p)
        retenu = chemin in resultat.par_fichier
        statut = "ÉCHEC — le fichier serait transmis" if retenu else "ok   — non transmis"
        print(f"  [{statut}] {chemin}  ({attendu})")
        ok = ok and not retenu

    passant = {"src/main/java/Paiement.java": "+long montant = 1050L;\n"}
    garde = appliquer_politique(passant, p)
    transmis = "src/main/java/Paiement.java" in garde.par_fichier
    print(f"  [{'ok   — transmis' if transmis else 'ÉCHEC — faux positif'}]"
          f" src/main/java/Paiement.java  (code ordinaire)")
    ok = ok and transmis

    print(f"\n{len(p.regles)} règles de domaine chargées,"
          f" {len(p.chemins_exclus)} chemins exclus,"
          f" {len(p.motifs_interdits)} motifs interdits")
    print("\nRésultat :", "toutes les gardes tiennent" if ok else "UNE GARDE A CÉDÉ")
    return 0 if ok else 1


# ---------------------------------------------------------------- entrée


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--base", default="origin/main")
    ap.add_argument("--head", default="HEAD")
    ap.add_argument("--politique", default=".github/ai-review.yml")
    ap.add_argument("--sortie", default="revue.md")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    p = Politique.charger(Path(args.politique))

    if args.self_test:
        return auto_test(p)

    diff = appliquer_politique(lire_diff(args.base, args.head), p)

    if diff.vide():
        Path(args.sortie).write_text(
            "## Pré-revue assistée\n\nAucun fichier à relire "
            "(diff vide, ou tout le contenu est exclu par la politique).\n",
            encoding="utf-8",
        )
        return 0

    # Au-dela du budget, on ne relit pas : une PR de cette taille ne se relit
    # utilement ni par un modele ni par un humain. Le message le dit franchement.
    if diff.lignes > p.max_lignes_diff or len(diff.par_fichier) > p.max_fichiers:
        Path(args.sortie).write_text(
            f"## Pré-revue assistée\n\n"
            f"Revue non lancée : {diff.lignes} lignes sur "
            f"{len(diff.par_fichier)} fichiers, au-delà du budget "
            f"({p.max_lignes_diff} lignes, {p.max_fichiers} fichiers).\n\n"
            f"Une PR de cette taille ne se relit utilement ni par un modèle ni "
            f"par un relecteur humain. Le découpage est la bonne réponse.\n",
            encoding="utf-8",
        )
        return 0

    try:
        resultat, usage = demander_revue(diff, p)
    except Exception as e:                     # noqa: BLE001
        # Le relecteur est un confort, pas une dependance. S'il echoue, la PR
        # continue : rendre la livraison dependante d'un service tiers pour une
        # PRE-revue serait un mauvais arbitrage.
        Path(args.sortie).write_text(
            f"## Pré-revue assistée\n\nIndisponible : `{type(e).__name__}`.\n\n"
            f"La revue humaine reste la seule obligatoire ; cette étape est un "
            f"confort et n'a pas vocation à bloquer la livraison.\n",
            encoding="utf-8",
        )
        print(f"revue indisponible : {e}", file=sys.stderr)
        return 0

    Path(args.sortie).write_text(rendre(resultat, diff, usage, p), encoding="utf-8")
    print(f"{len(resultat.get('signalements', []))} signalement(s),"
          f" {diff.lignes} lignes relues, {len(diff.ignores)} fichier(s) non transmis")
    return 0


if __name__ == "__main__":
    sys.exit(main())
