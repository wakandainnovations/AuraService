resource "aws_db_subnet_group" "rds_subnets" {
  name       = "${local.name_prefix}-rds-subnets"
  subnet_ids = [aws_subnet.private_a.id, aws_subnet.private_b.id]

  tags = {
    Name = "${local.name_prefix}-rds-subnets"
  }
}

resource "aws_db_instance" "postgres" {
  identifier             = "${local.name_prefix}-postgres"
  engine                 = "postgres"
  engine_version         = "15.7"
  instance_class         = "db.t3.small"
  allocated_storage      = 20
  storage_type           = "gp3"
  db_name                = "auradb"
  username               = var.db_username
  password               = var.db_password
  publicly_accessible    = false
  db_subnet_group_name   = aws_db_subnet_group.rds_subnets.name
  vpc_security_group_ids = [aws_security_group.rds_sg.id]
  skip_final_snapshot    = true
  deletion_protection    = false

  tags = {
    Name = "${local.name_prefix}-postgres"
  }
}
