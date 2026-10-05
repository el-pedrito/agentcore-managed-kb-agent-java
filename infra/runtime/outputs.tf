output "agent_runtime_arn" {
  value = aws_bedrockagentcore_agent_runtime.agent.agent_runtime_arn
}

output "image_uri" {
  description = "Deployed image (read by deploy.sh and destroy.sh)."
  value       = var.image_uri
}

# Runtime inputs, read by destroy.sh: deleting the runtime does not depend on the base state
# (a base already deleted or lost does not leave a billed runtime behind).
output "inputs" {
  value = {
    role_arn          = var.role_arn
    knowledge_base_id = var.knowledge_base_id
    model_id          = var.model_id
    guardrail_id      = var.guardrail_id
    guardrail_version = var.guardrail_version
  }
}
