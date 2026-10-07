# =============================================================================
# Module ecs-service : service Fargate derriere un ALB
#
# Pourquoi Fargate plutot qu'EC2 : il n'y a aucun systeme d'exploitation a patcher.
# Sur un service dans le perimetre PCI, cela supprime d'un coup les exigences de
# durcissement, de gestion des correctifs et d'anti-malware sur les instances
# (req. 2, 5 et 6) -- autant de chapitres a ne pas defendre devant un auditeur.
#
# Pourquoi pas EKS : le plan de controle Kubernetes demande une equipe pour le tenir
# (montees de version, CNI, RBAC, add-ons). Ca se justifie quand une plateforme K8s
# mutualisee existe deja dans le groupe, pas pour porter trois services.
# =============================================================================

locals {
  name_prefix = "${var.project}-${var.environment}-${var.service_name}"
}

# --------------------------------------------------------------------- logs

resource "aws_cloudwatch_log_group" "this" {
  name              = "/ecs/${local.name_prefix}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.logs_kms_key_arn

  tags = {
    Name = "${local.name_prefix}-logs"
  }
}

# --------------------------------------------------------------------- roles IAM

# Deux roles distincts, et c'est une vraie separation de privileges, pas du zele :
#
#   execution_role : utilise par l'agent ECS pour DEMARRER la tache (tirer l'image,
#                    lire les secrets, ecrire les logs). Le code applicatif ne l'a pas.
#   task_role      : assume par le CODE a l'execution (appeler SQS, KMS, la base).
#
# Resultat : une faille dans l'application ne donne pas les droits de demarrage, et
# notamment pas la lecture directe des secrets injectes.

data "aws_iam_policy_document" "ecs_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name_prefix        = "${substr(local.name_prefix, 0, 20)}-exec-"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json
  description        = "Role utilise par l'agent ECS pour demarrer la tache"

  tags = {
    Name = "${local.name_prefix}-execution-role"
  }
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Lecture des secrets au demarrage, limitee aux ARN explicitement fournis.
# Pas de "secretsmanager:GetSecretValue" sur "*" : c'est le genre de wildcard qu'un
# auditeur releve immediatement, et qui transforme une tache compromise en acces a
# tous les secrets du compte.
data "aws_iam_policy_document" "execution_secrets" {
  count = length(var.secret_arns) > 0 ? 1 : 0

  statement {
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = values(var.secret_arns)
  }

  dynamic "statement" {
    for_each = var.secrets_kms_key_arn == null ? [] : [1]

    content {
      effect    = "Allow"
      actions   = ["kms:Decrypt"]
      resources = [var.secrets_kms_key_arn]
    }
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  count = length(var.secret_arns) > 0 ? 1 : 0

  name   = "read-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_secrets[0].json
}

resource "aws_iam_role" "task" {
  name_prefix        = "${substr(local.name_prefix, 0, 20)}-task-"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json
  description        = "Role assume par le code applicatif a l'execution"

  tags = {
    Name = "${local.name_prefix}-task-role"
  }
}

# Authentification a la base par jeton IAM : plus aucun mot de passe applicatif.
# La tache demande un jeton valable 15 minutes avec ce role.
data "aws_iam_policy_document" "task_database" {
  count = var.database_resource_id == null ? 0 : 1

  statement {
    effect  = "Allow"
    actions = ["rds-db:connect"]
    resources = [
      "arn:aws:rds-db:${var.region}:${var.account_id}:dbuser:${var.database_resource_id}/${var.database_iam_user}"
    ]
  }
}

resource "aws_iam_role_policy" "task_database" {
  count = var.database_resource_id == null ? 0 : 1

  name   = "connect-database"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task_database[0].json
}

# Politique applicative supplementaire (SQS, KMS, S3...), fournie par l'appelant.
resource "aws_iam_role_policy" "task_extra" {
  count = var.task_policy_json == null ? 0 : 1

  name   = "application-permissions"
  role   = aws_iam_role.task.id
  policy = var.task_policy_json
}

# --------------------------------------------------------------------- reseau

resource "aws_security_group" "service" {
  name_prefix = "${local.name_prefix}-svc-"
  description = "Taches ${var.service_name} : entree depuis l'ALB uniquement"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Trafic applicatif depuis l'ALB"
    from_port       = var.container_port
    to_port         = var.container_port
    protocol        = "tcp"
    security_groups = [var.alb_security_group_id]
  }

  # Sortie ouverte en HTTPS : necessaire pour joindre le PSP, et les endpoints AWS.
  # On ne restreint pas par IP car les plages du PSP changent ; le controle se fait en
  # amont (pas d'IP publique sur les taches, sortie par NAT, et endpoints PrivateLink
  # pour tout ce qui reste interne a AWS).
  egress {
    description = "HTTPS sortant (PSP, endpoints AWS)"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "PostgreSQL vers le cluster de donnees"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Name = "${local.name_prefix}-sg"
  }
}

resource "aws_lb_target_group" "this" {
  # CKV_AWS_378 — le trafic entre l'ALB et les tâches est en HTTP. PCI DSS v4 pousse
  # vers un chiffrement de bout en bout, et c'est la bonne cible : il faudrait
  # distribuer un certificat à chaque tâche et terminer le TLS dans le conteneur.
  # Tant que ce n'est pas fait, le trafic reste confiné à des subnets privés sans
  # route vers Internet, entre deux security groups qui se référencent mutuellement.
  # Limite connue et assumée, pas un oubli.
  #checkov:skip=CKV_AWS_378: TLS ALB->tache a implementer ; trafic confine en subnets prives

  name_prefix = substr(var.service_name, 0, 6)
  port        = var.container_port
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip" # obligatoire avec Fargate (mode reseau awsvpc)

  health_check {
    enabled = true
    # Sonde de readiness dediee : elle verifie que la base et les dependances
    # repondent, pas seulement que la JVM est demarree. Une tache qui repond 200 sur /
    # mais qui n'atteint pas sa base recevrait du trafic qu'elle ne peut pas traiter.
    path                = var.health_check_path
    healthy_threshold   = 2
    unhealthy_threshold = 3
    timeout             = 5
    interval            = 15
    matcher             = "200"
  }

  # Laisse le temps aux requetes en vol de se terminer avant de retirer une tache.
  # Sur un service de paiement, couper une autorisation en cours cree precisement le
  # cas le plus penible : un debit sans trace locale.
  deregistration_delay = 30

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Name = "${local.name_prefix}-tg"
  }
}

resource "aws_lb_listener_rule" "this" {
  listener_arn = var.alb_listener_arn
  priority     = var.listener_priority

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this.arn
  }

  condition {
    path_pattern {
      values = var.path_patterns
    }
  }
}

# --------------------------------------------------------------------- tache

resource "aws_ecs_task_definition" "this" {
  family                   = local.name_prefix
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  # Graviton (ARM64) : environ 20 a 40 % de performance par euro en plus sur une charge
  # JVM. L'image doit etre construite pour cette architecture -- un build multi-arch
  # dans la CI suffit.
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode([
    {
      name      = var.service_name
      image     = var.image
      essential = true

      portMappings = [
        {
          containerPort = var.container_port
          protocol      = "tcp"
        }
      ]

      # Variables non sensibles uniquement.
      environment = [
        for key, value in var.environment_variables : {
          name  = key
          value = value
        }
      ]

      # Les secrets ne sont PAS des variables d'environnement en clair : on declare
      # l'ARN, et l'agent ECS injecte la valeur au demarrage. Une task definition est
      # lisible par quiconque a "ecs:DescribeTaskDefinition" -- y mettre un mot de
      # passe en clair revient a le publier.
      secrets = [
        for key, arn in var.secret_arns : {
          name      = key
          valueFrom = arn
        }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.this.name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "ecs"
        }
      }

      # Sonde locale, complementaire du health check de l'ALB : elle permet a ECS de
      # remplacer une tache bloquee meme si l'ALB ne lui envoie plus de trafic.
      healthCheck = {
        command     = ["CMD-SHELL", "curl -f http://localhost:${var.container_port}${var.health_check_path} || exit 1"]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 60 # demarrage de la JVM : ne pas tuer la tache avant qu'elle soit prete
      }

      # Durcissement : systeme de fichiers en lecture seule, sauf les repertoires
      # temporaires montes explicitement. Limite fortement ce qu'un code injecte peut
      # ecrire sur le conteneur.
      readonlyRootFilesystem = var.readonly_root_filesystem

      mountPoints = var.readonly_root_filesystem ? [
        { sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }
      ] : []
    }
  ])

  dynamic "volume" {
    for_each = var.readonly_root_filesystem ? [1] : []

    content {
      name = "tmp"
    }
  }

  tags = {
    Name = local.name_prefix
  }
}

resource "aws_ecs_service" "this" {
  name            = var.service_name
  cluster         = var.cluster_arn
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  # Deploiement progressif : 200 % de maximum et 100 % de minimum signifient qu'on
  # demarre les nouvelles taches AVANT d'arreter les anciennes. Aucune reduction de
  # capacite pendant un deploiement.
  deployment_maximum_percent         = 200
  deployment_minimum_healthy_percent = 100

  # Rollback automatique si le deploiement degrade les health checks. Sur un service
  # de paiement, detecter une regression et revenir en arriere tout seul vaut mieux
  # que d'attendre qu'une astreinte s'en apercoive.
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = var.app_subnet_ids
    security_groups  = [aws_security_group.service.id]
    assign_public_ip = false # les taches n'ont jamais d'IP publique
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.this.arn
    container_name   = var.service_name
    container_port   = var.container_port
  }

  # Laisse le temps a l'application de demarrer avant de compter les echecs de sonde.
  health_check_grace_period_seconds = 90

  # Repartit les taches sur les AZ puis sur les instances : sans cette strategie, un
  # incident d'AZ peut emporter la majorite des taches.
  ordered_placement_strategy {
    type  = "spread"
    field = "attribute:ecs.availability-zone"
  }

  enable_execute_command = var.enable_execute_command

  lifecycle {
    # L'autoscaling pilote desired_count : sans cet ignore, chaque apply ramenerait le
    # service a la valeur du code -- et reduirait la capacite en pleine montee de charge.
    ignore_changes = [desired_count]
  }

  depends_on = [aws_lb_listener_rule.this]

  tags = {
    Name = local.name_prefix
  }
}

# --------------------------------------------------------------------- autoscaling

resource "aws_appautoscaling_target" "this" {
  service_namespace  = "ecs"
  resource_id        = "service/${var.cluster_name}/${aws_ecs_service.this.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = var.min_capacity
  max_capacity       = var.max_capacity
}

# Mise a l'echelle sur le nombre de requetes par tache, pas sur le CPU.
#
# Le CPU est un indicateur indirect : un service qui attend le PSP a un CPU bas tout en
# etant sature de requetes en vol. On mesure donc ce qui compte vraiment -- le travail
# entrant par tache.
resource "aws_appautoscaling_policy" "requests" {
  name               = "${local.name_prefix}-requests"
  service_namespace  = aws_appautoscaling_target.this.service_namespace
  resource_id        = aws_appautoscaling_target.this.resource_id
  scalable_dimension = aws_appautoscaling_target.this.scalable_dimension
  policy_type        = "TargetTrackingScaling"

  target_tracking_scaling_policy_configuration {
    target_value = var.target_requests_per_task

    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      resource_label         = "${var.alb_arn_suffix}/${aws_lb_target_group.this.arn_suffix}"
    }

    # Monter vite, descendre lentement : une descente trop agressive provoque un
    # battement (scale down puis scale up immediat) et degrade la latence au pire
    # moment.
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
  }
}

resource "aws_appautoscaling_policy" "cpu" {
  name               = "${local.name_prefix}-cpu"
  service_namespace  = aws_appautoscaling_target.this.service_namespace
  resource_id        = aws_appautoscaling_target.this.resource_id
  scalable_dimension = aws_appautoscaling_target.this.scalable_dimension
  policy_type        = "TargetTrackingScaling"

  # Filet de securite : couvre les charges CPU-bound que la metrique de requetes ne
  # verrait pas (un batch de reconciliation, par exemple).
  target_tracking_scaling_policy_configuration {
    target_value = 70

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }

    scale_in_cooldown  = 300
    scale_out_cooldown = 60
  }
}
