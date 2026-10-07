# Les outputs sont le contrat du module : on expose ce dont les appelants ont besoin,
# et rien de plus. Chaque output ajoute est un couplage de plus a maintenir.

output "vpc_id" {
  description = "Identifiant du VPC"
  value       = aws_vpc.this.id
}

output "vpc_cidr" {
  description = "Bloc CIDR du VPC, utile pour les regles de security group des appelants"
  value       = aws_vpc.this.cidr_block
}

output "public_subnet_ids" {
  description = "Subnets publics : ALB et NAT uniquement"
  value       = [for subnet in aws_subnet.public : subnet.id]
}

output "app_subnet_ids" {
  description = "Subnets applicatifs : taches ECS"
  value       = [for subnet in aws_subnet.app : subnet.id]
}

output "data_subnet_ids" {
  description = "Subnets de donnees : Aurora, Redis. Sans route vers Internet."
  value       = [for subnet in aws_subnet.data : subnet.id]
}
