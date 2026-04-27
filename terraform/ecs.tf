resource "aws_ecs_cluster" "main" {
  name = "${local.name_prefix}-ecs-cluster"
}

resource "aws_cloudwatch_log_group" "ecs_java_app" {
  name              = "/ecs/${local.name_prefix}-java-app"
  retention_in_days = 14
}

resource "aws_ecs_task_definition" "java_app" {
  family                   = "${local.name_prefix}-java-app"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "256"
  memory                   = "512"
  execution_role_arn       = aws_iam_role.ecs_task_execution_role.arn

  container_definitions = jsonencode([
    {
      name      = "java-app"
      image     = var.java_app_image
      essential = true
      command   = ["java", "-version"]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.ecs_java_app.name
          awslogs-region        = "ap-south-1"
          awslogs-stream-prefix = "ecs"
        }
      }
    }
  ])
}

resource "aws_ecs_service" "java_app_spot" {
  name            = "${local.name_prefix}-java-app-spot"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.java_app.arn
  desired_count   = 1

  capacity_provider_strategy {
    capacity_provider = "FARGATE_SPOT"
    weight            = 1
  }

  network_configuration {
    subnets          = [aws_subnet.public_a.id, aws_subnet.public_b.id]
    security_groups  = [aws_security_group.fargate_sg.id]
    assign_public_ip = true
  }

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
}
