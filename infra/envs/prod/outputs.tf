output "alb_dns_name" {
  description = "Nom DNS de l'ALB, cible de l'enregistrement Route 53 / CloudFront"
  value       = aws_lb.this.dns_name
}

output "database_endpoint" {
  description = "Endpoint d'ecriture du cluster"
  value       = module.database.cluster_endpoint
}

output "alerts_topic_arn" {
  description = "Topic SNS des alertes, a brancher sur l'outil d'astreinte"
  value       = aws_sns_topic.alerts.arn
}

output "payment_api_task_role_arn" {
  description = "Task role de l'API, pour y attacher des droits supplementaires"
  value       = module.payment_api.task_role_arn
}
