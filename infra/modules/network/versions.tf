# Contraintes de version du module.
#
# Un module qui ne déclare pas ses contraintes hérite de celles de l'appelant :
# six mois plus tard, un `terraform init` prend une version majeure différente et
# le plan explose sans qu'une seule ligne de code ait changé. La contrainte
# pessimiste autorise les correctifs de la branche 5.x et bloque le passage en 6.0.
terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.70"
    }
  }
}
