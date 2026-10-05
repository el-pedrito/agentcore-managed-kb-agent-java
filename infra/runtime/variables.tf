# Configuration of the AgentCore runtime only. The base (ECR repository, role, permissions) is in
# infra/base: its outputs feed these variables (scripts/deploy.sh reads them).
# Every variable without a default is required: an incomplete apply fails at plan time
# instead of changing or destroying the runtime.

variable "project_name" {
  description = "Resource name prefix (the same as the base)."
  type        = string
  default     = "techassist-agent"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,28}[a-z0-9]$", var.project_name))
    error_message = "Lowercase letters, digits and hyphens, 3 to 30 characters, starting with a letter."
  }
}

variable "region" {
  description = "Runtime Region (the same as the base and the Knowledge Base)."
  type        = string
  default     = "eu-west-1"
}

variable "account_id" {
  description = "Expected target AWS account (12 digits). Terraform refuses any other account."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "AWS account ID expected (12 digits)."
  }
}

variable "role_arn" {
  description = "Runtime execution role (agent_role_arn output of the base)."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:iam::${var.account_id}:role/", var.role_arn))
    error_message = "IAM role ARN of the target account expected (agent_role_arn output of infra/base)."
  }
}

variable "image_uri" {
  description = "ARM64 agent image, pushed to the base ECR repository (required)."
  type        = string

  validation {
    condition     = can(regex("^${var.account_id}\\.dkr\\.ecr\\.${var.region}\\.amazonaws\\.com/[a-z0-9._/-]+(:[A-Za-z0-9._-]+|@sha256:[0-9a-f]{64})$", var.image_uri))
    error_message = "ECR image of the target account and Region expected (<account>.dkr.ecr.<region>.amazonaws.com/<repository>:<tag>)."
  }
}

variable "knowledge_base_id" {
  description = "Managed Knowledge Base queried by the agent (knowledge_base_id output of the base)."
  type        = string

  validation {
    condition     = can(regex("^[0-9A-Z]{10}$", var.knowledge_base_id))
    error_message = "Knowledge Base ID expected (10 uppercase alphanumeric characters)."
  }
}

variable "model_id" {
  description = "European inference profile (model_id output of the base, allowed by its role)."
  type        = string

  validation {
    condition     = can(regex("^eu\\.[a-z0-9-]+\\.[a-z0-9.:-]+$", var.model_id))
    error_message = "Use an eu. inference profile (format eu.<provider>.<model>)."
  }
}

variable "guardrail_id" {
  description = "Grounding guardrail (guardrail_id output of the base)."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9]{1,64}$", var.guardrail_id))
    error_message = "Guardrail ID expected (lowercase letters and digits)."
  }
}

variable "guardrail_version" {
  description = "Published guardrail version (guardrail_version output of the base)."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{1,8}$", var.guardrail_version))
    error_message = "Published guardrail version expected (a number, not DRAFT)."
  }
}
