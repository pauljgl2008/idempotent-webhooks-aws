variable "region" {
  description = "AWS region for the state bucket and deployed stack."
  type        = string
  default     = "us-east-1"
}

variable "github_repo" {
  description = "Exact owner/repository allowed to assume the deploy role."
  type        = string
  default     = "pauljgl2008/idempotent-webhooks-aws"
  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repo))
    error_message = "Use an exact owner/repository without wildcards."
  }
}
