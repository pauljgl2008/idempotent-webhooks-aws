variable "region" {
  description = "Region used by bootstrap and the stack."
  type        = string
  default     = "us-east-1"
}

variable "ingest_jar" {
  description = "Path to the shaded ingest JAR, relative to infra or absolute."
  type        = string
  default     = "../ingest/target/ingest-1.0.0-lambda.jar"
}

variable "process_jar" {
  description = "Path to the shaded process JAR, relative to infra or absolute."
  type        = string
  default     = "../process/target/process-1.0.0-lambda.jar"
}

variable "secret_parameter_name" {
  description = "Existing SecureString name created outside Terraform by scripts/put-secret.sh; never read its value into state."
  type        = string
  default     = "/iwh/hmac-secret"
  validation {
    condition     = startswith(var.secret_parameter_name, "/iwh/") && length(var.secret_parameter_name) > length("/iwh/")
    error_message = "The secret must be a parameter under /iwh/."
  }
}

variable "boundary_arn" {
  description = "iwh-boundary ARN output by bootstrap."
  type        = string
  validation {
    condition     = can(regex("^arn:[^:]+:iam::[0-9]{12}:policy/iwh-boundary$", var.boundary_arn))
    error_message = "Supply the iwh-boundary policy ARN from bootstrap."
  }
}

variable "fault_after_commit_event_prefix" {
  description = "Scenario-only event prefix for a failure after commit; empty omits the environment variable."
  type        = string
  default     = ""
}

variable "alert_email" {
  description = "Optional budget notification email; empty disables the notification."
  type        = string
  default     = ""
}
