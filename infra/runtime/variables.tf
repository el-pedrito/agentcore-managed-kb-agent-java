# Configuration du runtime AgentCore seul. Le socle (depot ECR, role, permissions) est dans
# infra/base : ses outputs alimentent ces variables (scripts/deploy.sh les relit).
# Toutes les variables sans valeur par defaut sont obligatoires : un apply incomplet echoue
# au plan au lieu de modifier ou detruire le runtime.

variable "project_name" {
  description = "Prefixe des ressources (le meme que le socle)."
  type        = string
  default     = "techassist-agent"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,28}[a-z0-9]$", var.project_name))
    error_message = "Minuscules, chiffres et tirets, 3 a 30 caracteres, commence par une lettre."
  }
}

variable "region" {
  description = "Region du runtime (la meme que le socle et la Knowledge Base)."
  type        = string
  default     = "eu-west-1"
}

variable "account_id" {
  description = "Compte AWS cible attendu (12 chiffres). Terraform refuse tout autre compte."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "Identifiant de compte AWS attendu (12 chiffres)."
  }
}

variable "role_arn" {
  description = "Role d'execution du runtime (output agent_role_arn du socle)."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:iam::${var.account_id}:role/", var.role_arn))
    error_message = "ARN de role IAM du compte cible attendu (output agent_role_arn de infra/base)."
  }
}

variable "image_uri" {
  description = "Image ARM64 de l'agent, poussee dans le depot ECR du socle (obligatoire)."
  type        = string

  validation {
    condition     = can(regex("^${var.account_id}\\.dkr\\.ecr\\.${var.region}\\.amazonaws\\.com/[a-z0-9._/-]+(:[A-Za-z0-9._-]+|@sha256:[0-9a-f]{64})$", var.image_uri))
    error_message = "Image ECR du compte et de la region cibles attendue (<compte>.dkr.ecr.<region>.amazonaws.com/<depot>:<tag>)."
  }
}

variable "knowledge_base_id" {
  description = "Knowledge Base managee interrogee par l'agent (output knowledge_base_id du socle)."
  type        = string

  validation {
    condition     = can(regex("^[0-9A-Z]{10}$", var.knowledge_base_id))
    error_message = "Identifiant de Knowledge Base attendu (10 caracteres alphanumeriques majuscules)."
  }
}

variable "model_id" {
  description = "Profil d'inference europeen (output model_id du socle, autorise par son role)."
  type        = string

  validation {
    condition     = can(regex("^eu\\.[a-z0-9-]+\\.[a-z0-9.:-]+$", var.model_id))
    error_message = "Utiliser un profil d'inference eu. (format eu.<fournisseur>.<modele>)."
  }
}

variable "grounding_check" {
  description = "Controle d'ancrage de la reponse finale (output grounding_check du socle)."
  type        = bool
}

variable "guardrail_id" {
  description = "Garde-fou d'ancrage (output guardrail_id du socle). Obligatoire si grounding_check = true."
  type        = string

  validation {
    condition     = !var.grounding_check || var.guardrail_id != ""
    error_message = "grounding_check = true : guardrail_id est obligatoire."
  }

  validation {
    condition     = var.guardrail_id == "" || can(regex("^[a-z0-9]{1,64}$", var.guardrail_id))
    error_message = "Identifiant de garde-fou attendu (minuscules et chiffres)."
  }
}

variable "guardrail_version" {
  description = "Version publiee du garde-fou (output guardrail_version du socle)."
  type        = string

  validation {
    condition     = (var.guardrail_id == "") == (var.guardrail_version == "")
    error_message = "guardrail_id et guardrail_version vont ensemble : les deux renseignes ou les deux vides."
  }

  validation {
    condition     = var.guardrail_version == "" || can(regex("^[0-9]{1,8}$", var.guardrail_version))
    error_message = "Version publiee du garde-fou attendue (nombre, pas DRAFT)."
  }
}
