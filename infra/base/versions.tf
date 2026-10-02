terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.67.0, < 7.0.0"
    }
  }

  # Etat local pour la demo. En equipe, utiliser un backend S3 (verrouillage natif use_lockfile) :
  # backend "s3" {
  #   bucket       = "<bucket-etat-terraform>"
  #   key          = "techassist/demo2-base.tfstate"
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
