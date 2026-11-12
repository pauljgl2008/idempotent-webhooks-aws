terraform {
  required_version = ">= 1.10"
  backend "s3" {
    key                  = "iwh/terraform.tfstate"
    use_lockfile         = true
    encrypt              = true
    workspace_key_prefix = "iwh/workspaces"
    # Supply bucket and region through -backend-config during initialization.
  }
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
