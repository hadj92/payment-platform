# =============================================================================
# State distant : la piece la plus critique d'une infra Terraform
#
#   * chiffre (KMS) : le state contient des valeurs sensibles -- ARN de secrets,
#     identifiants generes. Un state en clair dans un bucket public est une fuite.
#   * versionne (S3 versioning) : un state corrompu doit pouvoir etre restaure.
#   * verrouille : deux apply concurrents sans verrou corrompent l'infra.
#   * un state PAR environnement ET PAR domaine : jamais de state monolithique. Avec
#     2000 ressources dans un seul state, le plan prend 20 minutes et une erreur
#     impacte tout. On decoupe pour limiter le rayon d'action.
#
# use_lockfile : verrou S3 natif, disponible depuis Terraform 1.10/1.11. Avant cela,
# le verrou passait par une table DynamoDB avec ecriture conditionnelle
# (dynamodb_table = "terraform-locks"), approche encore tres repandue. Savoir
# expliquer les deux.
# =============================================================================

terraform {
  required_version = "~> 1.11"

  required_providers {
    aws = {
      source = "hashicorp/aws"
      # Contrainte pessimiste : autorise les correctifs 5.x, bloque le passage en 6.0.
      # Un provider non epingle, c'est un plan qui explose six mois plus tard sans
      # qu'une seule ligne de code ait change.
      version = "~> 5.70"
    }
  }

  # Decommenter apres creation du bucket (chicken-and-egg : le bucket de state ne peut
  # pas etre gere par le state qu'il contient -- il se cree une fois, a part).
  #
  # backend "s3" {
  #   bucket       = "payment-platform-tfstate-prod"
  #   key          = "payment-platform/prod/terraform.tfstate"
  #   region       = "eu-west-3"
  #   encrypt      = true
  #   kms_key_id   = "arn:aws:kms:eu-west-3:ACCOUNT_ID:key/KEY_ID"
  #   use_lockfile = true
  # }
}

provider "aws" {
  region = var.region

  # Tags appliques automatiquement a TOUTES les ressources du provider.
  #
  # Ce n'est pas cosmetique : sans tags coherents, impossible de ventiler la facture
  # par equipe, de retrouver le proprietaire d'une ressource orpheline, ou de prouver
  # a un auditeur quelles ressources appartiennent au CDE.
  default_tags {
    tags = {
      Project     = var.project
      Environment = var.environment
      Owner       = "tribe-payment"
      ManagedBy   = "terraform"
      Repository  = "payment-platform"
      DataClass   = "pci-cde"
    }
  }
}
