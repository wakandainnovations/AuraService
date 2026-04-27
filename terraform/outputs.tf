output "rds_endpoint" {
  description = "RDS PostgreSQL endpoint"
  value       = aws_db_instance.postgres.endpoint
}

output "ec2_public_ip" {
  description = "Public IP of the EC2 GPU host"
  value       = aws_instance.gpu_host.public_ip
}

output "ecs_cluster_name" {
  description = "ECS cluster name"
  value       = aws_ecs_cluster.main.name
}
