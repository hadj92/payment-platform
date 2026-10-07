# =============================================================================
# Module network : VPC a 3 niveaux pour une plateforme de paiement
#
# La segmentation reseau n'est pas qu'une bonne pratique ici : sans elle, PCI DSS
# considere que TOUT le reseau fait partie du CDE (Cardholder Data Environment) et
# devient auditable. Isoler le CDE, c'est reduire le perimetre d'audit -- donc le cout
# de la conformite et la surface d'attaque.
#
#   subnets publics  -> uniquement l'ALB et les NAT Gateways
#   subnets app      -> les taches ECS. Aucune IP publique. Sortie via NAT.
#   subnets data     -> Aurora, Redis. AUCUNE route vers Internet, dans aucun sens.
# =============================================================================

locals {
  # Une AZ par bloc : la redondance multi-AZ est non negociable sur un service dont
  # l'indisponibilite arrete l'encaissement de toutes les reservations.
  az_count = length(var.availability_zones)

  name_prefix = "${var.project}-${var.environment}"
}

resource "aws_vpc" "this" {
  cidr_block = var.vpc_cidr

  # Requis par les VPC endpoints interface (PrivateLink) et par la resolution des
  # endpoints RDS depuis les taches.
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = "${local.name_prefix}-vpc"
  }
}

# --------------------------------------------------------------------- subnets

resource "aws_subnet" "public" {
  for_each = { for idx, az in var.availability_zones : az => idx }

  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, each.value)

  # Pas d'IP publique automatique, meme dans un subnet public : seules les ressources
  # qui en ont explicitement besoin (ALB, NAT) en recoivent une.
  map_public_ip_on_launch = false

  tags = {
    Name = "${local.name_prefix}-public-${each.key}"
    Tier = "public"
  }
}

resource "aws_subnet" "app" {
  for_each = { for idx, az in var.availability_zones : az => idx }

  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, each.value + local.az_count)

  tags = {
    Name = "${local.name_prefix}-app-${each.key}"
    Tier = "application"
  }
}

resource "aws_subnet" "data" {
  for_each = { for idx, az in var.availability_zones : az => idx }

  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, each.value + (local.az_count * 2))

  tags = {
    Name = "${local.name_prefix}-data-${each.key}"
    Tier = "data"
  }
}

# --------------------------------------------------------------------- sortie Internet

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${local.name_prefix}-igw"
  }
}

# Une NAT Gateway PAR AZ, pas une seule mutualisee.
#
# Avec une NAT unique, la panne de son AZ coupe la sortie vers le PSP pour TOUTES les
# taches, y compris celles qui tournent dans des AZ saines : on transforme une panne
# partielle en panne totale. Le surcout (~32 $/mois par NAT) est sans commune mesure
# avec l'arret de l'encaissement. En dev, var.single_nat_gateway permet d'economiser.
resource "aws_eip" "nat" {
  for_each = var.single_nat_gateway ? { (var.availability_zones[0]) = 0 } : { for idx, az in var.availability_zones : az => idx }

  domain = "vpc"

  tags = {
    Name = "${local.name_prefix}-nat-eip-${each.key}"
  }
}

resource "aws_nat_gateway" "this" {
  for_each = aws_eip.nat

  allocation_id = each.value.id
  subnet_id     = aws_subnet.public[each.key].id

  # Sans cette dependance explicite, Terraform peut tenter de creer la NAT avant que
  # la route vers l'IGW n'existe : la creation echoue alors de facon intermittente.
  depends_on = [aws_internet_gateway.this]

  tags = {
    Name = "${local.name_prefix}-nat-${each.key}"
  }
}

# --------------------------------------------------------------------- routage

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }

  tags = {
    Name = "${local.name_prefix}-rt-public"
  }
}

resource "aws_route_table_association" "public" {
  for_each = aws_subnet.public

  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

# Une table par AZ : chaque subnet app sort par la NAT de sa propre AZ, ce qui evite
# le trafic inter-AZ (facture) et garde l'isolation des pannes.
resource "aws_route_table" "app" {
  for_each = aws_subnet.app

  vpc_id = aws_vpc.this.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = var.single_nat_gateway ? aws_nat_gateway.this[var.availability_zones[0]].id : aws_nat_gateway.this[each.key].id
  }

  tags = {
    Name = "${local.name_prefix}-rt-app-${each.key}"
  }
}

resource "aws_route_table_association" "app" {
  for_each = aws_subnet.app

  subnet_id      = each.value.id
  route_table_id = aws_route_table.app[each.key].id
}

# Les subnets data n'ont AUCUNE route par defaut : pas d'IGW, pas de NAT.
# Une base de donnees de paiement ne doit pouvoir initier aucune connexion sortante.
# C'est la mesure qui transforme une injection SQL en impasse plutot qu'en exfiltration.
resource "aws_route_table" "data" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${local.name_prefix}-rt-data"
  }
}

resource "aws_route_table_association" "data" {
  for_each = aws_subnet.data

  subnet_id      = each.value.id
  route_table_id = aws_route_table.data.id
}

# --------------------------------------------------------------------- VPC endpoints

# Les endpoints Gateway sont GRATUITS et evitent que le trafic vers S3 et DynamoDB ne
# sorte par la NAT. Double gain : la facture NAT (facturee au Go traite) et l'argument
# de segmentation PCI -- ce trafic ne quitte jamais le reseau AWS.
resource "aws_vpc_endpoint" "gateway" {
  for_each = toset(["s3", "dynamodb"])

  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${var.region}.${each.key}"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = concat([for rt in aws_route_table.app : rt.id], [aws_route_table.data.id])

  tags = {
    Name = "${local.name_prefix}-vpce-${each.key}"
  }
}

# Les endpoints Interface (PrivateLink) sont payants a l'heure mais indispensables :
# sans eux, une tache ECS dans un subnet prive doit sortir par la NAT pour lire un
# secret ou dechiffrer avec KMS. Faire transiter un appel KMS par Internet est
# exactement ce qu'un auditeur ne veut pas voir.
resource "aws_vpc_endpoint" "interface" {
  for_each = toset(var.interface_endpoints)

  vpc_id              = aws_vpc.this.id
  service_name        = "com.amazonaws.${var.region}.${each.key}"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = [for subnet in aws_subnet.app : subnet.id]
  security_group_ids  = [aws_security_group.vpc_endpoints.id]
  private_dns_enabled = true

  tags = {
    Name = "${local.name_prefix}-vpce-${each.key}"
  }
}

resource "aws_security_group" "vpc_endpoints" {
  name_prefix = "${local.name_prefix}-vpce-"
  description = "Autorise les taches applicatives a joindre les endpoints AWS en HTTPS"
  vpc_id      = aws_vpc.this.id

  ingress {
    description = "HTTPS depuis le VPC"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  # create_before_destroy avec name_prefix : sans ca, modifier ce SG echoue parce
  # qu'AWS refuse de supprimer un groupe encore reference par les endpoints.
  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Name = "${local.name_prefix}-vpce-sg"
  }
}

# --------------------------------------------------------------------- flow logs

# PCI DSS req. 10 : journaliser les acces reseau. Les flow logs sont aussi le premier
# outil quand il faut prouver qu'une donnee n'a PAS quitte le CDE.
resource "aws_flow_log" "this" {
  count = var.enable_flow_logs ? 1 : 0

  vpc_id               = aws_vpc.this.id
  traffic_type         = "ALL"
  log_destination_type = "s3"
  log_destination      = var.flow_logs_bucket_arn

  tags = {
    Name = "${local.name_prefix}-flow-logs"
  }
}
