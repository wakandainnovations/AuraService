data "aws_ami" "deep_learning_gpu" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["Deep Learning AMI GPU PyTorch*"]
  }

  filter {
    name   = "architecture"
    values = ["x86_64"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }

  filter {
    name   = "root-device-type"
    values = ["ebs"]
  }
}

resource "aws_instance" "gpu_host" {
  ami                                  = data.aws_ami.deep_learning_gpu.id
  instance_type                        = "g4dn.xlarge"
  subnet_id                            = aws_subnet.public_a.id
  key_name                             = var.instance_key_name
  vpc_security_group_ids               = [aws_security_group.ec2_sg.id]
  associate_public_ip_address          = true
  iam_instance_profile                 = aws_iam_instance_profile.ec2_profile.name
  instance_initiated_shutdown_behavior = "terminate"
  user_data                            = file("${path.module}/user_data.sh")

  root_block_device {
    volume_type           = "gp3"
    volume_size           = 100
    delete_on_termination = true
  }

  tags = {
    Name = "${local.name_prefix}-gpu-ec2"
  }
}
