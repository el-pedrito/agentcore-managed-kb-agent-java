data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  account_id         = data.aws_caller_identity.current.account_id
  partition          = data.aws_partition.current.partition
  knowledge_base_arn = "arn:${local.partition}:bedrock:${var.region}:${local.account_id}:knowledge-base/${var.knowledge_base_id}"
  # "eu.anthropic.claude-haiku-4-5-20251001-v1:0" -> "anthropic.claude-haiku-4-5-20251001-v1:0"
  base_model_id = trimprefix(var.model_id, "eu.")
}

# ---------- Depot de l'image de l'agent ----------

resource "aws_ecr_repository" "agent" {
  #checkov:skip=CKV_AWS_136:demo, chiffrement AES256 gere par ECR (KMS CMK en production)
  name                 = var.project_name
  image_tag_mutability = "IMMUTABLE"
  # Demo : les images sont supprimees par terraform destroy.
  force_delete = true

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_lifecycle_policy" "agent" {
  repository = aws_ecr_repository.agent.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Garder les 10 dernieres images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 10 }
      action       = { type = "expire" }
    }]
  })
}

# ---------- Role d'execution de l'agent (moindre privilege) ----------

data "aws_iam_policy_document" "agent_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["bedrock-agentcore.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }
    # Forme prescrite par la documentation AgentCore (runtime-permissions) : le compte et la
    # region sont fixes, l'ARN du runtime n'existe pas encore a la creation du role.
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${local.partition}:bedrock-agentcore:${var.region}:${local.account_id}:*"]
    }
  }
}

data "aws_iam_policy_document" "agent_permissions" {
  statement {
    sid       = "PullAgentImage"
    actions   = ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"]
    resources = [aws_ecr_repository.agent.arn]
  }
  statement {
    sid       = "EcrToken"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }
  statement {
    sid       = "Logs"
    actions   = ["logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
    resources = ["arn:${local.partition}:logs:${var.region}:${local.account_id}:log-group:/aws/bedrock-agentcore/runtimes/*"]
  }
  statement {
    sid       = "DescribeLogGroups"
    actions   = ["logs:DescribeLogGroups"]
    resources = ["arn:${local.partition}:logs:${var.region}:${local.account_id}:log-group:*"]
  }
  statement {
    sid       = "Traces"
    actions   = ["xray:PutTraceSegments", "xray:PutTelemetryRecords", "xray:GetSamplingRules", "xray:GetSamplingTargets"]
    resources = ["*"]
  }
  statement {
    sid       = "Metrics"
    actions   = ["cloudwatch:PutMetricData"]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "cloudwatch:namespace"
      values   = ["bedrock-agentcore"]
    }
  }
  statement {
    sid       = "RetrieveFromKnowledgeBase"
    actions   = ["bedrock:Retrieve"]
    resources = [local.knowledge_base_arn]
  }
  statement {
    sid = "InvokeEuropeanInferenceProfile"
    # Converse = bedrock:InvokeModel (l'agent ne fait pas de streaming).
    actions = ["bedrock:InvokeModel"]
    resources = [
      "arn:${local.partition}:bedrock:${var.region}:${local.account_id}:inference-profile/${var.model_id}",
      # Un profil eu. route uniquement vers des regions europeennes.
      "arn:${local.partition}:bedrock:eu-*::foundation-model/${local.base_model_id}",
    ]
  }
}

data "aws_iam_policy_document" "agent_guardrail" {
  count = var.guardrail_id == "" ? 0 : 1
  statement {
    sid       = "ApplyGroundingGuardrail"
    actions   = ["bedrock:ApplyGuardrail"]
    resources = ["arn:${local.partition}:bedrock:${var.region}:${local.account_id}:guardrail/${var.guardrail_id}"]
  }
}

resource "aws_iam_role" "agent" {
  name_prefix        = "${var.project_name}-run-"
  assume_role_policy = data.aws_iam_policy_document.agent_trust.json
}

resource "aws_iam_role_policy" "agent" {
  name   = "agent-runtime"
  role   = aws_iam_role.agent.id
  policy = data.aws_iam_policy_document.agent_permissions.json
}

resource "aws_iam_role_policy" "agent_guardrail" {
  count  = var.guardrail_id == "" ? 0 : 1
  name   = "apply-grounding-guardrail"
  role   = aws_iam_role.agent.id
  policy = data.aws_iam_policy_document.agent_guardrail[0].json
}

# Le runtime AgentCore est dans une configuration separee (infra/runtime), qui recoit le role
# et les parametres via les outputs ci-dessous : un apply du socle ne peut pas le detruire.
