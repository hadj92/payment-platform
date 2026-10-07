output "service_name" {
  description = "Nom du service ECS"
  value       = aws_ecs_service.this.name
}

output "security_group_id" {
  description = "Security group des taches, a autoriser en entree sur la base"
  value       = aws_security_group.service.id
}

output "task_role_arn" {
  description = "Role assume par le code applicatif"
  value       = aws_iam_role.task.arn
}

output "task_role_name" {
  description = "Nom du task role, pour y attacher des politiques depuis l'appelant"
  value       = aws_iam_role.task.name
}

output "target_group_arn" {
  description = "Target group ALB"
  value       = aws_lb_target_group.this.arn
}

output "log_group_name" {
  description = "Groupe de logs CloudWatch"
  value       = aws_cloudwatch_log_group.this.name
}
