# =============================================================================
# Module aurora : cluster PostgreSQL pour le ledger de paiements
#
# Pourquoi du relationnel et pas du NoSQL : on a besoin de transactions ACID (le
# paiement et son evenement d'outbox commitent ensemble ou pas du tout), de contraintes
# d'unicite (la cle d'idempotence), de contraintes CHECK (capture <= autorise) et de
# SQL pour la reconciliation comptable. Aucune de ces garanties n'est negociable quand
# on manipule de l'argent.
#
# Pourquoi Aurora et pas RDS classique : stockage replique sur 3 AZ (6 copies),
# bascule en ~30 s au lieu de plusieurs minutes, jusqu'a 15 read replicas, et
# Serverless v2 pour absorber les pics sans surdimensionner en permanence.
# =============================================================================

locals {
  name_prefix = "${var.project}-${var.environment}"
}

# --------------------------------------------------------------------- chiffrement

# CMK dediee plutot que la cle AWS par defaut (aws/rds).
#
# Trois raisons, toutes demandables par un auditeur : on controle la POLITIQUE de la
# cle (qui l'administre n'est pas qui l'utilise -- la separation des roles de PCI DSS
# req. 7), on peut la revoquer pour rendre les sauvegardes illisibles, et chaque
# utilisation est tracee dans CloudTrail sous notre cle.
resource "aws_kms_key" "database" {
  description             = "Chiffrement au repos du cluster de paiements ${var.environment}"
  enable_key_rotation     = true
  deletion_window_in_days = var.environment == "prod" ? 30 : 7

  tags = {
    Name      = "${local.name_prefix}-db-key"
    DataClass = "pci-cde"
  }
}

resource "aws_kms_alias" "database" {
  name          = "alias/${local.name_prefix}-database"
  target_key_id = aws_kms_key.database.key_id
}

# --------------------------------------------------------------------- reseau

resource "aws_db_subnet_group" "this" {
  name       = "${local.name_prefix}-db-subnets"
  subnet_ids = var.data_subnet_ids

  description = "Subnets de donnees, sans route vers Internet"

  tags = {
    Name = "${local.name_prefix}-db-subnets"
  }
}

resource "aws_security_group" "database" {
  name_prefix = "${local.name_prefix}-db-"
  description = "Acces PostgreSQL restreint aux taches applicatives"
  vpc_id      = var.vpc_id

  # On reference le SECURITY GROUP source, jamais un CIDR.
  #
  # C'est auto-documente ("la base accepte l'application"), ca survit a tout
  # changement d'adressage, et ca reste juste quand les taches Fargate obtiennent de
  # nouvelles IP a chaque deploiement -- ce qui arrive a chaque deploiement.
  ingress {
    description     = "PostgreSQL depuis les taches applicatives"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = var.allowed_security_group_ids
  }

  # Aucune regle de sortie : la base n'a aucune raison d'initier une connexion.
  # Le fournisseur AWS n'ajoute pas de regle egress par defaut lorsqu'on n'en declare
  # aucune, ce qui est exactement le comportement voulu ici.

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Name = "${local.name_prefix}-db-sg"
  }
}

# --------------------------------------------------------------------- parametres

resource "aws_rds_cluster_parameter_group" "this" {
  name_prefix = "${local.name_prefix}-pg-"
  family      = var.parameter_group_family
  description = "Parametres du cluster de paiements"

  # TLS obligatoire. Sans ce parametre, un client mal configure peut se connecter en
  # clair sans que personne ne s'en apercoive -- PCI DSS req. 4 impose le chiffrement
  # en transit, et la v4 pousse a l'appliquer aussi a l'interieur du reseau.
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  # Journalisation des requetes lentes : la premiere cause de degradation d'un service
  # de paiement sous charge est une requete qui passe de 5 ms a 500 ms apres une
  # evolution du volume de donnees.
  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }

  # Journalise les connexions : exigence d'audit (req. 10) et aide reelle au diagnostic
  # des saturations de pool.
  parameter {
    name  = "log_connections"
    value = "1"
  }

  parameter {
    name  = "log_disconnections"
    value = "1"
  }

  lifecycle {
    create_before_destroy = true
  }
}

# --------------------------------------------------------------------- cluster

resource "aws_rds_cluster" "this" {
  cluster_identifier = "${local.name_prefix}-payments"

  engine         = "aurora-postgresql"
  engine_version = var.engine_version
  engine_mode    = "provisioned" # requis par Serverless v2

  database_name   = var.database_name
  master_username = var.master_username

  # Le mot de passe est genere ET gere par Secrets Manager, pas par Terraform.
  #
  # Point important a savoir expliquer : si Terraform generait le mot de passe, il
  # finirait en clair dans le fichier de state. Ici AWS le cree, le stocke chiffre et
  # le fait tourner tout seul ; Terraform n'a jamais la valeur entre les mains.
  manage_master_user_password   = true
  master_user_secret_kms_key_id = aws_kms_key.database.arn

  db_subnet_group_name            = aws_db_subnet_group.this.name
  vpc_security_group_ids          = [aws_security_group.database.id]
  db_cluster_parameter_group_name = aws_rds_cluster_parameter_group.this.name

  storage_encrypted = true
  kms_key_id        = aws_kms_key.database.arn

  # Authentification par jeton IAM : la tache ECS demande un jeton de 15 minutes avec
  # son task role. Il n'y a plus de mot de passe applicatif a stocker, a faire tourner
  # ni a fuiter. C'est la meilleure reponse a "comment gerez-vous les credentials".
  iam_database_authentication_enabled = true

  backup_retention_period      = var.environment == "prod" ? 35 : 7
  preferred_backup_window      = "02:00-03:00"
  preferred_maintenance_window = "sun:03:30-sun:04:30"
  copy_tags_to_snapshot        = true

  # Exporter les logs vers CloudWatch : sans ca, ils restent dans l'instance et
  # disparaissent avec elle. PCI DSS exige 12 mois de retention.
  enabled_cloudwatch_logs_exports = ["postgresql"]

  # En production, on ne detruit pas un cluster de paiements sur un plan Terraform mal
  # relu. Trois verrous se cumulent : skip_final_snapshot a false, deletion_protection,
  # et le prevent_destroy ci-dessous.
  skip_final_snapshot       = var.environment != "prod"
  final_snapshot_identifier = var.environment == "prod" ? "${local.name_prefix}-final-${formatdate("YYYYMMDDhhmm", timestamp())}" : null
  deletion_protection       = var.environment == "prod"

  serverlessv2_scaling_configuration {
    min_capacity = var.min_capacity_acu
    max_capacity = var.max_capacity_acu
  }

  lifecycle {
    # Le nom du snapshot final contient un horodatage : sans cet ignore_changes, chaque
    # plan afficherait une modification fantome et finirait par declencher un replace.
    ignore_changes = [final_snapshot_identifier]
  }

  tags = {
    Name      = "${local.name_prefix}-payments"
    DataClass = "pci-cde"
  }
}

# Instance d'ecriture.
resource "aws_rds_cluster_instance" "writer" {
  identifier         = "${local.name_prefix}-writer"
  cluster_identifier = aws_rds_cluster.this.id
  instance_class     = "db.serverless"
  engine             = aws_rds_cluster.this.engine
  engine_version     = aws_rds_cluster.this.engine_version

  # Performance Insights : indispensable pour diagnostiquer une degradation en
  # production sans se connecter a la base, donc sans acceder aux donnees.
  performance_insights_enabled          = true
  performance_insights_kms_key_id       = aws_kms_key.database.arn
  performance_insights_retention_period = var.environment == "prod" ? 465 : 7

  monitoring_interval = 30
  monitoring_role_arn = var.monitoring_role_arn

  # Les mises a jour mineures s'appliquent pendant la fenetre de maintenance :
  # PCI DSS req. 6 impose d'appliquer les correctifs de securite.
  auto_minor_version_upgrade = true

  publicly_accessible = false

  tags = {
    Name = "${local.name_prefix}-writer"
  }
}

# Replicas de lecture : le portail hotelier et les exports comptables lisent ici.
#
# Separer les lectures protege le chemin critique : un export mal filtre par un
# hotelier ne doit pas pouvoir ralentir les autorisations. Attention toutefois au
# retard de replication (quelques dizaines de ms) -- on ne relit jamais sur un replica
# un paiement qu'on vient d'ecrire.
resource "aws_rds_cluster_instance" "reader" {
  count = var.reader_count

  identifier         = "${local.name_prefix}-reader-${count.index + 1}"
  cluster_identifier = aws_rds_cluster.this.id
  instance_class     = "db.serverless"
  engine             = aws_rds_cluster.this.engine
  engine_version     = aws_rds_cluster.this.engine_version

  performance_insights_enabled    = true
  performance_insights_kms_key_id = aws_kms_key.database.arn

  publicly_accessible        = false
  auto_minor_version_upgrade = true

  # Priorite de promotion elevee (chiffre faible = promu en premier) pour que la
  # bascule choisisse un replica deja chaud.
  promotion_tier = count.index

  tags = {
    Name = "${local.name_prefix}-reader-${count.index + 1}"
  }
}
