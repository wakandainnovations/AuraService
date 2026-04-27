variable "db_password" {
  description = "Password for RDS PostgreSQL master user"
  type        = string
  sensitive   = true
}

variable "instance_key_name" {
  description = "EC2 key pair name for SSH access"
  type        = string
}

variable "my_ip_address" {
  description = "Your public IP in CIDR format for SSH (example: 1.2.3.4/32)"
  type        = string
}

variable "db_username" {
  description = "RDS master username"
  type        = string
  default     = "auradbadmin"
}

variable "java_app_image" {
  description = "Container image for Java application on ECS Fargate Spot"
  type        = string
  default     = "public.ecr.aws/docker/library/amazoncorretto:17"
}
