variable "project" {
  description = "Nom du projet"
  type        = string
}

variable "environment" {
  description = "Environnement cible (dev, staging, prod)"
  type        = string
}

variable "vpc_id" {
  description = "VPC d'accueil"
  type        = string
}

variable "data_subnet_ids" {
  description = "Subnets de donnees, sans route vers Internet"
  type        = list(string)
}

variable "allowed_security_group_ids" {
  description = "Security groups autorises a joindre la base (celui des taches applicatives)"
  type        = list(string)
}

variable "database_name" {
  description = "Nom de la base logique"
  type        = string
  default     = "payments"
}

variable "master_username" {
  description = "Utilisateur maitre. Le mot de passe est gere par Secrets Manager, pas par Terraform."
  type        = string
  default     = "payments_admin"
}

variable "engine_version" {
  description = "Version du moteur Aurora PostgreSQL. A epingler : une montee de version majeure ne doit jamais etre implicite."
  type        = string
  default     = "16.4"
}

variable "parameter_group_family" {
  description = "Famille du parameter group, alignee sur la version majeure du moteur"
  type        = string
  default     = "aurora-postgresql16"
}

variable "min_capacity_acu" {
  description = <<-EOT
    Capacite minimale en ACU (1 ACU ~ 2 Gio de RAM).
    Ne pas descendre trop bas en production : une base qui doit remonter en capacite
    au moment du pic ajoute de la latence quand on en a le moins besoin.
  EOT
  type        = number
  default     = 0.5
}

variable "max_capacity_acu" {
  description = "Capacite maximale en ACU. Plafond volontaire : un garde-fou contre la facture autant que contre une requete folle."
  type        = number
  default     = 16
}

variable "reader_count" {
  description = "Nombre de replicas de lecture. Au moins 1 en production."
  type        = number
  default     = 1
}
