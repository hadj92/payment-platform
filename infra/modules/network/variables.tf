variable "project" {
  description = "Nom du projet, utilise comme prefixe de nommage"
  type        = string
}

variable "environment" {
  description = "Environnement cible (dev, staging, prod)"
  type        = string

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "L'environnement doit etre dev, staging ou prod."
  }
}

variable "region" {
  description = "Region AWS"
  type        = string
}

variable "vpc_cidr" {
  description = "Bloc CIDR du VPC. Prevoir /16 : decoupe en 3 niveaux sur plusieurs AZ, un /20 sature vite."
  type        = string
  default     = "10.0.0.0/16"

  validation {
    condition     = can(cidrnetmask(var.vpc_cidr))
    error_message = "vpc_cidr doit etre un bloc CIDR valide."
  }
}

variable "availability_zones" {
  description = "Zones de disponibilite. Minimum 2 ; 3 recommande en production."
  type        = list(string)

  validation {
    condition     = length(var.availability_zones) >= 2
    error_message = "Au moins 2 AZ sont requises : un service de paiement ne tourne pas sur une seule AZ."
  }
}

variable "single_nat_gateway" {
  description = <<-EOT
    Mutualiser une seule NAT Gateway pour toutes les AZ.
    Acceptable en dev pour economiser (~32 $/mois par NAT evitee).
    JAMAIS en production : la panne de l'AZ portant la NAT couperait la sortie vers
    le PSP pour toutes les taches, y compris celles des AZ saines.
  EOT
  type        = bool
  default     = false
}

variable "interface_endpoints" {
  description = "Services AWS joints par PrivateLink, pour que le trafic ne sorte pas par la NAT"
  type        = list(string)
  default = [
    "ecr.api", # tirer les images au demarrage des taches
    "ecr.dkr",
    "logs",           # CloudWatch Logs
    "secretsmanager", # credentials PSP et base
    "kms",            # dechiffrement
    "sqs",
    "sts", # assumption de role par les taches
    "ssm",
  ]
}

variable "enable_flow_logs" {
  description = "Activer les VPC Flow Logs (PCI DSS req. 10)"
  type        = bool
  default     = true
}

variable "flow_logs_bucket_arn" {
  description = "Bucket S3 destinataire des flow logs. Requis si enable_flow_logs."
  type        = string
  default     = null
}
