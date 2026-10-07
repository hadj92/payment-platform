output "cluster_endpoint" {
  description = "Endpoint d'ecriture (suit automatiquement la bascule)"
  value       = aws_rds_cluster.this.endpoint
}

output "reader_endpoint" {
  description = "Endpoint de lecture, equilibre entre les replicas"
  value       = aws_rds_cluster.this.reader_endpoint
}

output "cluster_resource_id" {
  description = "Identifiant de ressource du cluster, requis pour construire la policy IAM d'authentification par jeton"
  value       = aws_rds_cluster.this.cluster_resource_id
}

output "security_group_id" {
  description = "Security group de la base"
  value       = aws_security_group.database.id
}

output "master_user_secret_arn" {
  description = "ARN du secret gere par AWS contenant les credentials maitre"
  # Attribut sous forme de liste : vide si manage_master_user_password est desactive.
  value     = try(aws_rds_cluster.this.master_user_secret[0].secret_arn, null)
  sensitive = true
}

output "kms_key_arn" {
  description = "CMK de chiffrement, a reutiliser pour les sauvegardes et les exports"
  value       = aws_kms_key.database.arn
}

output "database_name" {
  description = "Nom de la base logique"
  value       = aws_rds_cluster.this.database_name
}
