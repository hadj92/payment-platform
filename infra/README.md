# infra

Infrastructure de la plateforme de paiement, décrite en Terraform.

```
modules/
  network/       VPC 3 tiers · NAT par AZ · VPC endpoints · SG par défaut verrouillé
  aurora/        Aurora PostgreSQL Serverless v2 · CMK avec politique explicite
                 · authentification IAM · TLS forcé · Enhanced Monitoring
  ecs-service/   Fargate ARM64 · rôles execution et task séparés · autoscaling
                 sur les requêtes par tâche
envs/
  prod/          Assemblage · ALB + WAF + journalisation · alarmes métier
```

## Démarrer

```bash
cd envs/prod
terraform init -backend=false      # validation hors ligne, sans compte AWS
terraform validate
```

Pour un vrai déploiement : décommenter le bloc `backend "s3"` dans `backend.tf`
après avoir créé le bucket d'état (il ne peut pas être géré par l'état qu'il
contient — il se crée une fois, à part), puis copier `terraform.tfvars.example`
en `terraform.tfvars`.

## Les décisions à connaître

| Choix | Pourquoi |
|---|---|
| **Subnets data sans aucune route vers Internet** | Transforme une injection SQL en impasse plutôt qu'en exfiltration |
| **Une NAT Gateway par AZ** | Mutualiser transformerait une panne d'AZ en panne totale de la sortie vers le PSP |
| **VPC endpoints** (S3, DynamoDB, KMS, Secrets Manager, ECR…) | Un appel KMS ne transite jamais par Internet. Argument d'audit **et** facture NAT, qui se compte au Go |
| **Security groups référencés entre eux** | Auto-documenté, et ça survit aux changements d'IP — qui arrivent à chaque déploiement Fargate |
| **Deux rôles IAM par service** | `execution_role` démarre la tâche et lit les secrets, `task_role` est assumé par le code. Une faille applicative ne donne pas la lecture des secrets |
| **Authentification base par jeton IAM** | Jeton de 15 minutes : plus aucun mot de passe applicatif à stocker, faire tourner ou fuiter |
| **Mot de passe maître géré par Secrets Manager** | S'il était généré par Terraform, il finirait en clair dans le fichier d'état |
| **Autoscaling sur les requêtes par tâche** | Un service qui attend le PSP a un CPU bas tout en étant saturé : le CPU arrive trop tard |
| **`ignore_changes = [desired_count]`** | Sinon chaque `apply` ramène le service à la valeur du code — et réduit la capacité en pleine montée de charge |
| **Alarme sur le taux d'autorisation** | Un incident de paiement ne se voit pas dans le CPU. Le symptôme, c'est que les paiements cessent de passer |

## Un mot sur les exceptions de sécurité

Checkov s'exécute en mode bloquant (209 contrôles, 0 échec). Les 15 exceptions sont
déclarées **dans le code, à côté de la ressource concernée**, chacune avec sa raison :
une liste d'exclusions sans justification revient à désactiver le scanner en faisant
semblant de l'utiliser.

```bash
docker run --rm -v "$PWD/..":/tf -w /tf bridgecrew/checkov:latest \
  -d infra/ --framework terraform --compact --quiet
```
