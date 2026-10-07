# =============================================================================
# Environnement de production de la plateforme de paiement
#
# Un DOSSIER par environnement, pas un workspace Terraform. Les workspaces
# conviennent a des environnements strictement identiques et jetables (une preview par
# PR). Pour dev/staging/prod d'un systeme de paiement, les dossiers separes donnent :
# un state isole, des variables explicites et versionnees, la possibilite de faire
# diverger les environnements, et surtout aucun risque de se croire en dev alors qu'on
# applique en prod.
# =============================================================================

data "aws_caller_identity" "current" {}

locals {
  name_prefix = "${var.project}-${var.environment}"
}

# --------------------------------------------------------------------- reseau

module "network" {
  source = "../../modules/network"

  project            = var.project
  environment        = var.environment
  region             = var.region
  vpc_cidr           = var.vpc_cidr
  availability_zones = var.availability_zones

  # Une NAT par AZ en production : voir le commentaire du module. Mutualiser
  # transformerait une panne d'AZ en panne totale de la sortie vers le PSP.
  single_nat_gateway = false
  enable_flow_logs   = false # a activer avec un bucket de destination
}

# --------------------------------------------------------------------- ALB

resource "aws_security_group" "alb" {
  name_prefix = "${local.name_prefix}-alb-"
  description = "ALB public : HTTPS uniquement"
  vpc_id      = module.network.vpc_id

  ingress {
    description = "HTTPS entrant"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # Pas d'ecoute en HTTP sur le port 80, meme pour rediriger : sur un service de
  # paiement, on ne laisse aucune possibilite d'etablir une connexion en clair.
  # La redirection HTTP->HTTPS se fait en amont, sur CloudFront.

  egress {
    description = "Vers les taches applicatives"
    from_port   = 0
    to_port     = 65535
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Name = "${local.name_prefix}-alb-sg"
  }
}

resource "aws_lb" "this" {
  name               = "${local.name_prefix}-alb"
  load_balancer_type = "application"
  internal           = false
  subnets            = module.network.public_subnet_ids
  security_groups    = [aws_security_group.alb.id]

  # Protection contre une suppression accidentelle par un plan mal relu.
  enable_deletion_protection = true

  # Rejette les requetes HTTP malformees plutot que de les normaliser : parade contre
  # le request smuggling, qui permet de contourner des controles en amont.
  drop_invalid_header_fields = true

  # Les logs d'acces de l'ALB sont une exigence d'audit (req. 10) et le premier
  # element de preuve en cas d'investigation.
  # access_logs {
  #   bucket  = var.access_logs_bucket
  #   prefix  = local.name_prefix
  #   enabled = true
  # }

  tags = {
    Name = "${local.name_prefix}-alb"
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"

  # Politique TLS : TLS 1.2 minimum, impose par PCI DSS req. 4. La politique retenue
  # exclut TLS 1.0 et 1.1 et ne conserve que des suites avec forward secrecy.
  ssl_policy      = "ELBSecurityPolicy-TLS13-1-2-Res-2021-06"
  certificate_arn = var.certificate_arn

  # Reponse par defaut : tout chemin non route explicitement est refuse.
  # Liste blanche plutot que liste noire -- une regle de routage oubliee ne doit pas
  # exposer un service par accident.
  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "application/json"
      message_body = jsonencode({ code = "not_found", detail = "Route inconnue" })
      status_code  = "404"
    }
  }
}

# --------------------------------------------------------------------- WAF

# Le rate limiting n'est pas optionnel sur une API de paiement : le card testing
# (tester des milliers de numeros voles a 1 € pour trouver ceux qui passent) est une
# attaque quotidienne. Elle coute cher en frais d'autorisation, degrade le taux
# d'approbation aupres des schemes, et peut declencher un programme de monitoring.
resource "aws_wafv2_web_acl" "this" {
  name        = "${local.name_prefix}-waf"
  description = "Protection de l'API de paiement"
  scope       = "REGIONAL"

  default_action {
    allow {}
  }

  rule {
    name     = "rate-limit-per-ip"
    priority = 1

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = var.waf_rate_limit
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "rate-limit-per-ip"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-managed-common"
    priority = 2

    # 'none' et non 'block' : on laisse le groupe managé appliquer ses propres
    # actions. Le passer en count d'abord, observer, puis bloquer -- une regle
    # managée activée brutalement en production bloque toujours du trafic legitime.
    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "aws-managed-common"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-managed-bad-inputs"
    priority = 3

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "aws-managed-bad-inputs"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${local.name_prefix}-waf"
    sampled_requests_enabled   = true
  }

  tags = {
    Name = "${local.name_prefix}-waf"
  }
}

resource "aws_wafv2_web_acl_association" "alb" {
  resource_arn = aws_lb.this.arn
  web_acl_arn  = aws_wafv2_web_acl.this.arn
}

# --------------------------------------------------------------------- base de donnees

module "database" {
  source = "../../modules/aurora"

  project     = var.project
  environment = var.environment

  vpc_id          = module.network.vpc_id
  data_subnet_ids = module.network.data_subnet_ids

  # Dependance circulaire assumee et resolue dans ce sens : la base autorise le SG du
  # service, et le service sort vers le CIDR du VPC sur 5432. Si on autorisait des deux
  # cotes par reference de SG, Terraform ne pourrait pas ordonner le graphe.
  allowed_security_group_ids = [module.payment_api.security_group_id]

  min_capacity_acu = 2  # pas trop bas en production : remonter en capacite pendant le
  max_capacity_acu = 32 # pic ajoute de la latence au pire moment
  reader_count     = 2
}

# --------------------------------------------------------------------- cluster ECS

resource "aws_ecs_cluster" "this" {
  name = "${local.name_prefix}-cluster"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = {
    Name = "${local.name_prefix}-cluster"
  }
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name = aws_ecs_cluster.this.name

  # FARGATE seul pour le service d'autorisation : pas de Spot sur le chemin critique.
  # Une tache Spot peut etre recuperee avec 2 minutes de preavis -- acceptable pour un
  # worker de reconciliation, pas pour une autorisation en cours.
  capacity_providers = ["FARGATE"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
    base              = 2
  }
}

# --------------------------------------------------------------------- service

module "payment_api" {
  source = "../../modules/ecs-service"

  project      = var.project
  environment  = var.environment
  service_name = "payment-api"
  region       = var.region
  account_id   = data.aws_caller_identity.current.account_id

  vpc_id         = module.network.vpc_id
  vpc_cidr       = var.vpc_cidr
  app_subnet_ids = module.network.app_subnet_ids

  cluster_arn  = aws_ecs_cluster.this.arn
  cluster_name = aws_ecs_cluster.this.name

  alb_listener_arn      = aws_lb_listener.https.arn
  alb_security_group_id = aws_security_group.alb.id
  alb_arn_suffix        = aws_lb.this.arn_suffix
  listener_priority     = 100
  path_patterns         = ["/v1/payments*"]

  # Tag immuable, jamais 'latest' : un deploiement doit etre reproductible et un
  # rollback doit pointer vers une image precise.
  image = var.payment_api_image

  cpu              = 1024
  memory           = 2048
  cpu_architecture = "ARM64"
  desired_count    = 3 # une par AZ
  min_capacity     = 3
  max_capacity     = 30

  environment_variables = {
    SPRING_PROFILES_ACTIVE = "prod"
    SERVER_PORT            = "8080"
    # Endpoint d'ecriture : il suit automatiquement la bascule, contrairement a
    # l'adresse d'une instance.
    DB_URL = "jdbc:postgresql://${module.database.cluster_endpoint}:5432/${module.database.database_name}?sslmode=require"
  }

  # Authentification par jeton IAM : plus aucun mot de passe de base a stocker.
  database_resource_id = module.database.cluster_resource_id
  database_iam_user    = "payment_app"

  log_retention_days = 365 # PCI DSS req. 10
}

# --------------------------------------------------------------------- alertes

resource "aws_sns_topic" "alerts" {
  name              = "${local.name_prefix}-alerts"
  kms_master_key_id = "alias/aws/sns"

  tags = {
    Name = "${local.name_prefix}-alerts"
  }
}

# L'alerte qui compte vraiment : la chute du taux d'autorisation.
#
# Un incident de paiement ne se voit pas forcement dans le CPU ni la latence. Le
# symptome, c'est que les paiements cessent de passer -- changement de regle chez
# l'emetteur, certificat PSP expire, regression de la logique 3DS. Alerter sur une
# metrique metier detecte ces cas en minutes au lieu d'attendre la remontee d'un
# hotelier.
resource "aws_cloudwatch_metric_alarm" "authorization_rate_drop" {
  alarm_name          = "${local.name_prefix}-taux-autorisation-degrade"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 2
  threshold           = var.min_authorization_rate
  alarm_description   = "Taux d'autorisation sous le seuil : incident revenu probable"
  treat_missing_data  = "notBreaching"

  metric_query {
    id          = "rate"
    expression  = "authorized / MAX([attempts, 1]) * 100"
    label       = "Taux d'autorisation (%)"
    return_data = true
  }

  metric_query {
    id = "authorized"

    metric {
      namespace   = "payment-api"
      metric_name = "payment.authorization"
      period      = 300
      stat        = "Sum"
      dimensions  = { outcome = "authorized" }
    }
  }

  metric_query {
    id = "attempts"

    metric {
      namespace   = "payment-api"
      metric_name = "payment.authorization"
      period      = 300
      stat        = "Sum"
    }
  }

  alarm_actions = [aws_sns_topic.alerts.arn]
  ok_actions    = [aws_sns_topic.alerts.arn]
}

# Une outbox qui gonfle signifie que les evenements ne partent plus : les reservations
# ne se confirment pas, meme si les paiements sont autorises. Panne silencieuse type.
resource "aws_cloudwatch_metric_alarm" "outbox_backlog" {
  alarm_name          = "${local.name_prefix}-outbox-saturee"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  threshold           = 1000
  period              = 60
  namespace           = "payment-api"
  metric_name         = "payment.outbox.pending"
  statistic           = "Maximum"
  alarm_description   = "Backlog d'evenements non publies : les consommateurs aval ne sont plus informes"
  treat_missing_data  = "notBreaching"

  alarm_actions = [aws_sns_topic.alerts.arn]
}

# Un evenement en FAILED est l'equivalent d'une DLQ qui se remplit : il ne repartira
# pas tout seul, il faut une intervention.
resource "aws_cloudwatch_metric_alarm" "outbox_failed" {
  alarm_name          = "${local.name_prefix}-outbox-evenements-abandonnes"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  threshold           = 0
  period              = 300
  namespace           = "payment-api"
  metric_name         = "payment.outbox.failed"
  statistic           = "Maximum"
  alarm_description   = "Evenements definitivement abandonnes : intervention manuelle requise"
  treat_missing_data  = "notBreaching"

  alarm_actions = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  alarm_name          = "${local.name_prefix}-erreurs-5xx"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  threshold           = 10
  period              = 60
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  statistic           = "Sum"
  alarm_description   = "Erreurs serveur sur l'API de paiement"
  treat_missing_data  = "notBreaching"

  dimensions = {
    LoadBalancer = aws_lb.this.arn_suffix
  }

  alarm_actions = [aws_sns_topic.alerts.arn]
}
