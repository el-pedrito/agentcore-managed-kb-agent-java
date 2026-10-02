output "agent_runtime_arn" {
  value = aws_bedrockagentcore_agent_runtime.agent.agent_runtime_arn
}

output "image_uri" {
  description = "Image deployee (relue par deploy.sh et destroy.sh)."
  value       = var.image_uri
}

# Entrees du runtime, relues par destroy.sh : la suppression du runtime ne depend pas du state du
# socle (un socle deja supprime ou perdu ne laisse pas de runtime facture derriere lui).
output "inputs" {
  value = {
    role_arn          = var.role_arn
    knowledge_base_id = var.knowledge_base_id
    model_id          = var.model_id
    grounding_check   = var.grounding_check
    guardrail_id      = var.guardrail_id
    guardrail_version = var.guardrail_version
  }
}
