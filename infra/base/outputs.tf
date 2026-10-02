# Base outputs, read by scripts/deploy.sh to feed infra/runtime.

output "region" {
  value = var.region
}

output "repository_url" {
  value = aws_ecr_repository.agent.repository_url
}

output "agent_role_arn" {
  description = "Runtime execution role (role_arn variable of infra/runtime)."
  # Exposed once the permissions are attached: the runtime does not start with an empty role.
  value      = aws_iam_role.agent.arn
  depends_on = [aws_iam_role_policy.agent]
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

