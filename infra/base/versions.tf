terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.67.0, < 7.0.0"
    }
  }

  # Local state for the demo. In a team, use an S3 backend (native locking with use_lockfile):
  # backend "s3" {
  #   bucket       = "<terraform-state-bucket>"
  #   key          = "techassist/demo2-base.tfstate"
  #   region       = "eu-west-1"
  #   use_lockfile = true
  #   encrypt      = true
  # }
}

provider "aws" {
  region = var.region
  # Safety net: Terraform refuses to touch any account other than the expected one.
  allowed_account_ids = [var.account_id]

  default_tags {
    tags = {
      project    = var.project_name
      managed-by = "terraform"
    }
  }
}
