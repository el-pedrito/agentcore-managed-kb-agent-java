# Outputs du socle, relus par scripts/deploy.sh pour alimenter infra/runtime.

output "region" {
  value = var.region
}

output "repository_url" {
  value = aws_ecr_repository.agent.repository_url
}

output "agent_role_arn" {
  description = "Role d'execution du runtime (variable role_arn de infra/runtime)."
  # Expose une fois les permissions attachees : le runtime ne demarre pas avec un role vide.
  value      = aws_iam_role.agent.arn
  depends_on = [aws_iam_role_policy.agent, aws_iam_role_policy.agent_guardrail]
}

output "knowledge_base_id" {
  value = var.knowledge_base_id
}

output "model_id" {
  value = var.model_id
}

output "log_retention_days" {
  value = var.log_retention_days
}

output "guardrail_id" {
  value = var.guardrail_id
}

output "guardrail_version" {
  value = var.guardrail_version
}

output "grounding_check" {
  description = "Controle d'ancrage actif sur le runtime."
  value       = var.grounding_check
}
