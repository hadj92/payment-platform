variable "project" {
  description = "Nom du projet"
  type        = string
  default     = "payment-platform"
}

variable "environment" {
  description = "Environnement"
  type        = string
  default     = "prod"
}

variable "region" {
  description = "Region AWS. eu-west-3 (Paris) : les donnees de paiement europeennes restent dans l'UE."
  type        = string
  default     = "eu-west-3"
}

variable "vpc_cidr" {
  description = "CIDR du VPC"
  type        = string
  default     = "10.0.0.0/16"
}

variable "availability_zones" {
  description = "Zones de disponibilite"
  type        = list(string)
  default     = ["eu-west-3a", "eu-west-3b", "eu-west-3c"]
}

variable "certificate_arn" {
  description = "Certificat ACM du listener HTTPS"
  type        = string
}

variable "payment_api_image" {
  description = "Image du conteneur, par digest ou tag immuable. Jamais 'latest'."
  type        = string

  validation {
    condition     = !endswith(var.payment_api_image, ":latest")
    error_message = "Le tag 'latest' est interdit : un deploiement doit etre reproductible et un rollback doit viser une image precise."
  }
}

variable "waf_rate_limit" {
  description = "Requetes par IP sur 5 minutes avant blocage. Parade au card testing."
  type        = number
  default     = 2000
}

variable "min_authorization_rate" {
  description = <<-EOT
    Taux d'autorisation minimal (%) avant alerte.
    A calibrer sur l'historique reel : un taux normal depend du mix de pays, de types de
    carte et de la politique 3DS. Un seuil trop haut genere du bruit, un seuil trop bas
    laisse passer un incident revenu.
  EOT
  type        = number
  default     = 80
}
