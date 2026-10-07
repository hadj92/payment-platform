variable "project" {
  description = "Nom du projet"
  type        = string
}

variable "environment" {
  description = "Environnement cible"
  type        = string
}

variable "service_name" {
  description = "Nom logique du service"
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,23}$", var.service_name))
    error_message = "Minuscules et tirets, 3 a 24 caracteres (contrainte de nommage des ressources AWS derivees)."
  }
}

variable "region" {
  description = "Region AWS"
  type        = string
}

variable "account_id" {
  description = "Identifiant du compte AWS, utilise pour construire les ARN"
  type        = string
}

variable "vpc_id" {
  type        = string
  description = "VPC d'accueil"
}

variable "vpc_cidr" {
  type        = string
  description = "CIDR du VPC, pour la regle de sortie vers la base"
}

variable "app_subnet_ids" {
  description = "Subnets prives accueillant les taches"
  type        = list(string)
}

variable "cluster_arn" {
  type        = string
  description = "ARN du cluster ECS"
}

variable "cluster_name" {
  type        = string
  description = "Nom du cluster ECS, requis par l'identifiant de cible d'autoscaling"
}

variable "alb_listener_arn" {
  type        = string
  description = "Listener HTTPS de l'ALB sur lequel greffer la regle de routage"
}

variable "alb_security_group_id" {
  type        = string
  description = "Security group de l'ALB : seule source de trafic autorisee vers les taches"
}

variable "alb_arn_suffix" {
  type        = string
  description = "Suffixe d'ARN de l'ALB, requis par la metrique ALBRequestCountPerTarget"
}

variable "listener_priority" {
  description = "Priorite de la regle de listener. Doit etre unique par listener."
  type        = number
}

variable "path_patterns" {
  description = "Chemins routes vers ce service"
  type        = list(string)
  default     = ["/*"]
}

variable "image" {
  description = "Image du conteneur. Toujours un digest ou un tag immuable en production : un tag 'latest' rend un deploiement non reproductible et interdit tout rollback fiable."
  type        = string
}

variable "container_port" {
  type    = number
  default = 8080
}

variable "health_check_path" {
  description = "Sonde de readiness. Elle doit verifier les dependances, pas seulement que le process est vivant."
  type        = string
  default     = "/actuator/health/readiness"
}

variable "cpu" {
  description = "Unites de CPU Fargate (1024 = 1 vCPU)"
  type        = number
  default     = 1024
}

variable "memory" {
  description = "Memoire en Mio. Les combinaisons CPU/memoire valides sont imposees par Fargate."
  type        = number
  default     = 2048
}

variable "cpu_architecture" {
  description = "X86_64 ou ARM64 (Graviton, meilleur rapport performance/prix)"
  type        = string
  default     = "ARM64"

  validation {
    condition     = contains(["X86_64", "ARM64"], var.cpu_architecture)
    error_message = "Architecture attendue : X86_64 ou ARM64."
  }
}

variable "desired_count" {
  description = "Nombre initial de taches. Repris ensuite par l'autoscaling."
  type        = number
  default     = 2

  validation {
    condition     = var.desired_count >= 2
    error_message = "Minimum 2 taches : un service de paiement ne tourne pas sur une instance unique."
  }
}

variable "min_capacity" {
  type    = number
  default = 2
}

variable "max_capacity" {
  type    = number
  default = 20
}

variable "target_requests_per_task" {
  description = "Nombre de requetes par tache visee par l'autoscaling. A calibrer par test de charge, pas au doigt mouille."
  type        = number
  default     = 500
}

variable "environment_variables" {
  description = "Variables d'environnement NON sensibles"
  type        = map(string)
  default     = {}
}

variable "secret_arns" {
  description = "Secrets injectes par l'agent ECS : nom de variable -> ARN Secrets Manager"
  type        = map(string)
  default     = {}
}

variable "secrets_kms_key_arn" {
  description = "CMK ayant chiffre les secrets, si ce n'est pas la cle AWS geree par defaut"
  type        = string
  default     = null
}

variable "database_resource_id" {
  description = "cluster_resource_id Aurora, pour autoriser l'authentification par jeton IAM. Null pour desactiver."
  type        = string
  default     = null
}

variable "database_iam_user" {
  description = "Role PostgreSQL auquel la tache se connecte par jeton IAM"
  type        = string
  default     = "payment_app"
}

variable "task_policy_json" {
  description = "Politique IAM applicative additionnelle (SQS, KMS, S3...)"
  type        = string
  default     = null
}

variable "log_retention_days" {
  description = "Retention des logs. PCI DSS req. 10 impose 12 mois, dont 3 immediatement accessibles."
  type        = number
  default     = 365
}

variable "logs_kms_key_arn" {
  description = "CMK de chiffrement du groupe de logs"
  type        = string
  default     = null
}

variable "readonly_root_filesystem" {
  description = "Monte le systeme de fichiers du conteneur en lecture seule (durcissement PCI req. 2)"
  type        = bool
  default     = true
}

variable "enable_execute_command" {
  description = <<-EOT
    Autorise 'aws ecs execute-command' (shell dans le conteneur).
    A laisser a false en production : c'est un acces interactif a un systeme du CDE, et
    il devrait passer par une procedure break-glass tracee, pas etre disponible en
    permanence.
  EOT
  type        = bool
  default     = false
}
