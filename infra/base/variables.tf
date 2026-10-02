variable "project_name" {
  description = "Prefixe des ressources."
  type        = string
  default     = "techassist-agent"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,28}[a-z0-9]$", var.project_name))
    error_message = "Minuscules, chiffres et tirets, 3 a 30 caracteres, commence par une lettre."
  }
}

variable "region" {
  description = "Region du runtime, de la Knowledge Base et du point d'entree Bedrock."
  type        = string
  default     = "eu-west-1"
}

variable "knowledge_base_id" {
  description = <<-EOT
    Identifiant de la Knowledge Base managee interrogee par l'agent. La meme que la demo 1
    (output knowledge_base_id de bedrock-managed-kb-rag-java/infra) : meme documentation,
    deux facons de l'interroger.
  EOT
  type        = string

  validation {
    condition     = can(regex("^[0-9A-Z]{10}$", var.knowledge_base_id))
    error_message = "Identifiant de Knowledge Base attendu (10 caracteres alphanumeriques majuscules)."
  }
}

variable "grounding_check" {
  description = "Controle d'ancrage de la reponse finale. Le desactiver est un choix explicite (deconseille)."
  type        = bool
  default     = true
}

variable "guardrail_id" {
  description = "Garde-fou d'ancrage applique a la reponse finale (output guardrail_id de la demo 1). Obligatoire si grounding_check = true."
  type        = string
  default     = ""

  validation {
    condition     = !var.grounding_check || var.guardrail_id != ""
    error_message = "grounding_check = true : guardrail_id est obligatoire (output guardrail_id de la demo 1)."
  }

  # Valeur injectee dans un ARN IAM : format strict, pas de caractere generique.
  validation {
    condition     = var.guardrail_id == "" || can(regex("^[a-z0-9]{1,64}$", var.guardrail_id))
    error_message = "Identifiant de garde-fou attendu (minuscules et chiffres, sans caractere generique)."
  }
}

variable "guardrail_version" {
  description = "Version publiee du garde-fou (output guardrail_version de la demo 1)."
  type        = string
  default     = ""

  validation {
    condition     = (var.guardrail_id == "") == (var.guardrail_version == "")
    error_message = "guardrail_id et guardrail_version vont ensemble : les deux renseignes ou les deux vides."
  }

  validation {
    condition     = var.guardrail_version == "" || can(regex("^[0-9]{1,8}$", var.guardrail_version))
    error_message = "Version publiee du garde-fou attendue (nombre, pas DRAFT)."
  }
}

variable "model_id" {
  description = "Profil d'inference europeen (prefixe eu.) utilise par l'agent."
  type        = string
  default     = "eu.anthropic.claude-haiku-4-5-20251001-v1:0"

  validation {
    # Profil eu. (inference en Europe) et format strict : la valeur est injectee dans un ARN IAM.
    condition     = can(regex("^eu\\.[a-z0-9-]+\\.[a-z0-9.:-]+$", var.model_id))
    error_message = "Utiliser un profil d'inference eu. (format eu.<fournisseur>.<modele>, sans caractere generique)."
  }
}

variable "log_retention_days" {
  description = "Retention des logs du runtime (groupe cree par AgentCore, retention posee par deploy.sh)."
  type        = number
  default     = 30
}

variable "account_id" {
  description = "Compte AWS cible attendu (12 chiffres). Terraform refuse tout autre compte."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "Identifiant de compte AWS attendu (12 chiffres)."
  }
}
