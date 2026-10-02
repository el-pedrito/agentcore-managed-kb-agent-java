terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.67.0, < 7.0.0"
    }
  }

  # Etat local pour la demo, distinct de celui du socle (infra/base). En equipe, backend S3 :
  # backend "s3" {
  #   bucket       = "<bucket-etat-terraform>"
  #   key          = "techassist/demo2-runtime.tfstate"
  #   region       = "eu-west-1"
  #   use_lockfile = true
  #   encrypt      = true
  # }
}

provider "aws" {
  region = var.region
  # Garde-fou : Terraform refuse de toucher un autre compte que celui attendu.
  allowed_account_ids = [var.account_id]

  default_tags {
    tags = {
      project    = var.project_name
      managed-by = "terraform"
    }
  }
}
