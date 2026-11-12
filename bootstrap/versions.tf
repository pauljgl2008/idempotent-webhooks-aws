terraform {
  required_version = ">= 1.10"
  # Bootstrap deliberately uses local state; see README.md for custody instructions.
  backend "local" {}
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = { project = "iwh" }
  }
}
