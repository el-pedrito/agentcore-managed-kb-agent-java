variable "project_name" {
  description = "Resource name prefix."
  type        = string
  default     = "techassist-agent"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,28}[a-z0-9]$", var.project_name))
    error_message = "Lowercase letters, digits and hyphens, 3 to 30 characters, starting with a letter."
  }
}

variable "region" {
  description = "Region of the runtime, the Knowledge Base and the Bedrock endpoint."
  type        = string
  default     = "eu-west-1"
}

variable "knowledge_base_id" {
  description = <<-EOT
    ID of the managed Knowledge Base queried by the agent. The same as demo 1
    (knowledge_base_id output of bedrock-managed-kb-rag-java/infra): same documentation,
    two ways to query it.
  EOT
  type        = string

  validation {
    condition     = can(regex("^[0-9A-Z]{10}$", var.knowledge_base_id))
    error_message = "Knowledge Base ID expected (10 uppercase alphanumeric characters)."
  }
}

variable "guardrail_id" {
  description = "Grounding guardrail applied to the final answer (guardrail_id output of demo 1)."
  type        = string

  # Value injected into an IAM ARN: strict format, no wildcard.
  validation {
    condition     = can(regex("^[a-z0-9]{1,64}$", var.guardrail_id))
    error_message = "Guardrail ID expected (lowercase letters and digits, no wildcard)."
  }
}

variable "guardrail_version" {
  description = "Published guardrail version (guardrail_version output of demo 1)."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{1,8}$", var.guardrail_version))
    error_message = "Published guardrail version expected (a number, not DRAFT)."
  }
}

variable "model_id" {
  description = "European inference profile (eu. prefix) used by the agent."
  type        = string
  default     = "eu.anthropic.claude-haiku-4-5-20251001-v1:0"

  validation {
    # eu. profile (inference in Europe) and strict format: the value is injected into an IAM ARN.
    condition     = can(regex("^eu\\.[a-z0-9-]+\\.[a-z0-9.:-]+$", var.model_id))
    error_message = "Use an eu. inference profile (format eu.<provider>.<model>, no wildcard)."
  }
}

variable "log_retention_days" {
  description = "Runtime log retention (log group created by AgentCore, retention set by deploy.sh)."
  type        = number
  default     = 30
}

variable "account_id" {
  description = "Expected target AWS account (12 digits). Terraform refuses any other account."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "AWS account ID expected (12 digits)."
  }
}
