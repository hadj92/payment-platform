# payment-platform

Plateforme de paiement par carte conçue pour le **cycle hôtelier** : garantie à la réservation, autorisations incrémentales pendant le séjour, capture du folio réel au check-out, facturation de no-show.

> **Projet de démonstration personnel.** Le prestataire de paiement est simulé, aucune donnée carte réelle n'est manipulée, et ce code n'est affilié à aucun groupe hôtelier.

```
┌─────────────────┐   HTTPS    ┌──────────────┐   token    ┌─────────────┐
│ payment-portal  │───────────▶│ payment-api  │───────────▶│   PSP       │
│   Vue 3 + Pinia │            │ Spring Boot  │            │ (simulé)    │
└─────────────────┘            └──────┬───────┘            └─────────────┘
                                      │
                         ┌────────────┴─────────────┐
                         │ PostgreSQL               │
                         │  payments                │
                         │  idempotency_records     │
                         │  outbox_events           │
                         └──────────────────────────┘

                  infra/ ── Terraform : VPC 3 tiers, ECS Fargate,
                            Aurora Serverless v2, ALB + WAF, KMS
```

---

## Les 7 décisions d'architecture à retenir

### 1. Aucune donnée carte ne traverse le système
Le formulaire de carte est servi par le PSP (*hosted fields* / iframe) : le numéro va du navigateur **directement** au PSP, qui renvoie un token. En base on conserve le token, le BIN, les 4 derniers chiffres et l'expiration — assez pour l'UX, la réconciliation et le scoring de risque, rien d'exploitable.

**Pourquoi c'est la décision la plus rentable :** elle maintient le périmètre en **SAQ A / A-EP** (~30 à 100 exigences PCI DSS) au lieu de **SAQ D** (~300 exigences + audit QSA lourd). Le schéma de base lui-même empêche la faute : il n'y a aucune colonne où stocker un PAN.

### 2. Idempotence garantie par la base, pas par le code
La clé d'idempotence est la **clé primaire** de `idempotency_records` : c'est l'insertion qui arbitre la course entre deux requêtes concurrentes. Un `SELECT` suivi d'un `INSERT` laisserait une fenêtre entre les deux — et sous charge, cette fenêtre est toujours exploitée, avec un double débit à la clé.

Trois cas distincts sur une clé déjà vue :

| Situation | Réponse | Raison |
|---|---|---|
| Traitement **en cours** | `409` | La première requête appelle encore le PSP : autoriser la seconde débiterait deux fois |
| Terminée, **même corps** | réponse mémorisée rejouée | Indiscernable de la première réponse, code d'erreur compris |
| Terminée, **corps différent** | `422` | Erreur d'intégration de l'appelant : la masquer cacherait un bug qui porte sur un montant |

### 3. L'appel réseau au PSP n'est jamais dans une transaction base ouverte
`PaymentService` **n'est pas** `@Transactional`. Il ouvre une transaction courte pour persister l'intention, la referme, appelle le PSP, puis ouvre une seconde transaction pour enregistrer le résultat.

Garder une transaction ouverte pendant un appel réseau de plusieurs centaines de millisecondes immobilise une connexion du pool pour rien : sous charge, le pool se vide et **toute l'application s'arrête, lectures comprises**. C'est un mode de défaillance classique.

Les méthodes transactionnelles vivent dans une classe séparée (`PaymentTransactions`) — un appel `@Transactional` depuis le même bean ne traverse pas le proxy Spring et l'annotation est silencieusement ignorée.

### 4. Outbox transactionnel plutôt que publication synchrone
L'événement métier est écrit dans **la même transaction** que le paiement. Soit les deux sont commités, soit aucun : on n'annonce jamais un paiement qui n'existe pas, et on ne laisse jamais une réservation en attente sur un paiement pourtant autorisé.

Le 2PC/XA résoudrait théoriquement le problème, au prix d'une disponibilité dégradée et d'un couplage que personne ne veut sur un chemin critique.

**Contrepartie assumée :** livraison *at-least-once*, donc tout consommateur doit dédupliquer sur l'identifiant d'événement. On échange une incohérence possible contre un retard garanti — le bon sens du compromis quand il s'agit d'argent.

### 5. Montants en unités mineures, jamais en flottant
`Money(long minorUnits, Currency)` avec `Math.addExact`. En binaire, `0.1 + 0.2 = 0.30000000000000004` ; répété sur un volume de transactions, l'écart devient une anomalie de réconciliation que la Finance remonte toujours. Tous les PSP exposent leurs montants en unités mineures pour cette raison.

### 6. Machine à états explicite + verrou optimiste
Les transitions autorisées sont déclarées **une fois**, dans `PaymentStatus`, plutôt que dispersées en `if`. Le `@Version` JPA attrape les écritures concurrentes : deux captures simultanées — parce qu'un message SQS a été livré deux fois, ce qui arrive par conception — font échouer la seconde au lieu de doubler le débit.

### 7. Les invariants monétaires sont aussi dans la base
```sql
CONSTRAINT chk_captured_le_authorized CHECK (captured_amount <= authorized_amount),
CONSTRAINT chk_refunded_le_captured   CHECK (refunded_amount  <= captured_amount)
```
Défense en profondeur : le code se déploie, les données restent. Un bug, une migration ou une correction manuelle ne peuvent pas violer ces règles.

---

## Spécificités hôtelières implémentées

| Cas d'usage | Mécanique |
|---|---|
| Garantie de réservation | Autorisation sans capture ; le hold a une durée de vie suivie (`authorizationExpiresAt`) |
| Extras pendant le séjour | Captures incrémentales successives |
| Check-out | Capture **partielle** : le folio réel est souvent inférieur au montant autorisé |
| No-show | **MIT** référençant le `networkTransactionId` de la CIT initiale authentifiée — sans ce chaînage, l'émetteur refuse et l'exemption SCA est perdue |
| Annulation vs remboursement | `cancel` libère le hold (invisible sur le relevé) ; `refund` renvoie de l'argent déjà prélevé. Jamais interchangeables |

---

## Résilience

- **Circuit breaker** (Resilience4j) sur les appels PSP : fenêtre de 20 appels, ouverture à 50 % d'échecs. Un refus de l'émetteur **n'ouvre jamais** le circuit — ce n'est pas une panne.
- **Retry** avec backoff exponentiel **et jitter** : sans jitter, tous les clients retentent en chœur et achèvent le service qui se remet à peine debout. Uniquement sur les erreurs techniques.
- **Distinction refus / panne** : un refus (`402`) est définitif et mémorisé ; une panne (`503`) est rejouable et libère la clé d'idempotence.
- **Arrêt gracieux** : les requêtes en vol se terminent avant que la tâche ne rende la main.

## Codes HTTP — le choix compte

| Situation | Code | Pourquoi |
|---|---|---|
| Refus de l'émetteur | **402** | La requête est valide, c'est la banque qui refuse. Retenter à l'identique ne sert à rien |
| Transition interdite | **409** | Conflit avec l'état de la ressource |
| Requête concurrente en cours | **409** | Il faut attendre, pas doubler |
| Montant > disponible | **422** | Syntaxe valide, règle métier violée |
| Clé d'idempotence recyclée | **422** | Erreur d'intégration de l'appelant |
| PSP indisponible | **503** + `retryable: true` | Panne temporaire, rejouable avec la même clé |

Format **ProblemDetail (RFC 9457)** avec un `code` stable : un client de paiement doit pouvoir décider automatiquement s'il retente, sans parser du texte.

---

## Démarrer

```bash
docker compose up -d postgres
cd payment-api && mvn spring-boot:run
```

```bash
cd payment-portal && npm install && npm run dev
```

API : `http://localhost:8080` · Swagger : `http://localhost:8080/swagger-ui.html` · Portail : `http://localhost:5173`

### Tests

```bash
cd payment-api && mvn verify
```

```bash
cd payment-portal && npm test
```

**58 tests Java** (domaine sans infrastructure + intégration API) et **7 tests front**.

### Sécurité de la chaîne

La CI bloque sur les vulnérabilités critiques et sur les contrôles d'infrastructure — elle ne se contente pas de les signaler :

| Scanner | Portée | État |
|---|---|---|
| **Trivy** | dépendances Java | **0 CRITICAL/HIGH** — versions de Tomcat, PostgreSQL et Jackson surchargées au-delà de ce qu'épingle Spring Boot |
| **Checkov** | Terraform | **209 contrôles passés, 0 échec**, 15 exceptions |
| **CodeQL** | code Java | analyse statique à chaque PR |
| **gitleaks** | historique Git | recherche de secrets sur l'historique complet |

Chaque exception porte **sa justification dans le code**, à côté de la ressource concernée — une liste d'exclusions sans raison revient à désactiver le scanner en faisant semblant de l'utiliser. Par exemple :

- la politique d'une **clé KMS** utilise `resources = ["*"]`, où `*` désigne la clé elle-même : c'est la seule écriture possible, et les contrôles IAM génériques ne s'y appliquent pas ;
- le trafic **ALB → tâche** est en HTTP : c'est une limite assumée et documentée, pas un oubli — le chiffrement de bout en bout demanderait de distribuer un certificat à chaque tâche ;
- `CVE-2026-47884` (spring-webmvc) est écartée après **analyse d'exploitabilité** : l'application n'utilise aucune vue XSLT, n'expose que du JSON et ne déclare aucun `ViewResolver`. Le correctif n'existe qu'en Spring Framework 7, donc Spring Boot 4 — migration planifiée séparément.

### Scénarios simulables par préfixe de token

Dans l'esprit des numéros de test publiés par les PSP :

| Token | Comportement |
|---|---|
| `tok_ok_*` | Autorisé |
| `tok_decline_*` | Refus émetteur (`05 Do not honor`) |
| `tok_insufficient_*` | Fonds insuffisants (`51`) |
| `tok_error_*` | Panne technique → alimente le circuit breaker |
| `tok_3ds_*` | Challenge 3DS requis (`202`) |

```bash
curl -X POST http://localhost:8080/v1/payments \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"reservationId":"RES-001","hotelId":"HOTEL-PAR-001","amount":12500,
       "currency":"EUR","paymentMethodToken":"tok_ok_visa",
       "cardBrand":"VISA","cardBin":"411111","cardLast4":"1111","cardExpiry":"2030-12"}'
```

> Rejouer **exactement la même commande avec la même clé** renvoie le même paiement avec `"replayed": true` — aucun second débit.

---

## Infrastructure (`infra/`)

```
modules/network/      VPC 3 tiers · NAT par AZ · VPC endpoints · flow logs
modules/aurora/       Aurora PG Serverless v2 · CMK dédiée · auth IAM · TLS forcé
modules/ecs-service/  Fargate ARM64 · rôles execution/task séparés · autoscaling
envs/prod/            Assemblage · ALB + WAF · alarmes métier
```

Points à défendre :
- **Subnets data sans aucune route vers Internet** — transforme une injection SQL en impasse plutôt qu'en exfiltration.
- **Une NAT Gateway par AZ** — mutualiser transformerait une panne d'AZ en panne totale de la sortie vers le PSP.
- **VPC endpoints** — un appel KMS ou Secrets Manager ne transite jamais par Internet. Argument PCI **et** facture NAT.
- **Deux rôles IAM** — `execution_role` démarre la tâche (lit les secrets), `task_role` est assumé par le code. Une faille applicative ne donne pas la lecture des secrets.
- **Authentification base par jeton IAM** — jeton de 15 min via le task role : plus aucun mot de passe applicatif à stocker, faire tourner ou fuiter.
- **Autoscaling sur les requêtes par tâche, pas le CPU** — un service qui attend le PSP a un CPU bas tout en étant saturé.
- **`ignore_changes = [desired_count]`** — sinon chaque `apply` réduirait la capacité en pleine montée de charge.
- **Alarme sur le taux d'autorisation** — un incident de paiement ne se voit pas dans le CPU. Le symptôme, c'est que les paiements cessent de passer.

```bash
cd infra/envs/prod && terraform init -backend=false && terraform validate
```

## CI/CD (`.github/workflows/`)

- **OIDC GitHub → AWS** : aucune clé d'accès longue durée. La *trust policy* restreint le `sub` au dépôt **et** à la branche.
- **Rôle `plan` en lecture seule**, rôle `apply` distinct : un plan s'exécute sur chaque PR et ne doit rien pouvoir modifier.
- **On applique le plan qui a été revu**, pas un plan recalculé — sinon on applique ce que personne n'a lu.
- **Plan en commentaire de PR** : la revue d'infra devient une revue de code normale.
- **Détection de dérive quotidienne** (`plan -detailed-exitcode`) → ouvre une issue.
- Scans : CodeQL (SAST), Trivy (dépendances + image), gitleaks (secrets dans l'historique), Checkov (policy as code sur le Terraform).
- **Image taguée par SHA, jamais `latest`** — un déploiement doit être reproductible et un rollback doit viser une image précise.

> **Les étapes de déploiement sont écrites mais désactivées.** Build d'image, `terraform plan`/`apply` et détection de dérive ne s'exécutent que si le dépôt est relié à un compte AWS — variable de dépôt `DEPLOY_ENABLED=true` et secrets de rôle OIDC. Sans cible configurée, ces jobs échoueraient systématiquement, et un badge rouge permanent finit par ne plus rien signaler. Le code du pipeline reste complet et lisible : c'est lui qui documente la chaîne de livraison.

---

## Ce qu'il resterait à faire pour une vraie production

Savoir nommer ses limites compte autant que ce qui est livré :

- **Authentification / RBAC** : le `hotelId` est aujourd'hui un paramètre de requête. En production il est dérivé du jeton OIDC, jamais fourni par le client — en l'état, un hôtelier pourrait lire les paiements d'un autre hôtel.
- **Réconciliation** : ingestion des fichiers de settlement, matching ligne à ligne, file d'exceptions pour la Finance. Une plateforme de paiement n'est pas finie quand l'autorisation passe, elle est finie quand la Finance a réconcilié à l'euro près.
- **Balayage des `PENDING` orphelins** : la requête existe (`findStalePending`), le job qui interroge le PSP pour trancher reste à écrire. C'est là que se cachent les clients débités sans trace locale.
- **Outbox multi-instances** : `SELECT ... FOR UPDATE SKIP LOCKED`, ou remplacement du polling par du CDC (Debezium sur le WAL).
- **Migrations testées sur PostgreSQL** via Testcontainers en CI : les tests actuels tournent sur H2, qui ne supporte pas les index partiels de `V1`.
- **Second PSP** avec routage de bascule — gros chantier : tokens non portables (sauf network tokens), réconciliation double, logique de routage.
