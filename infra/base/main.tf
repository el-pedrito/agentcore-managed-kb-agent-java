data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  account_id         = data.aws_caller_identity.current.account_id
  partition          = data.aws_partition.current.partition
  knowledge_base_arn = "arn:${local.partition}:bedrock:${var.region}:${local.account_id}:knowledge-base/${var.knowledge_base_id}"
  # "eu.anthropic.claude-haiku-4-5-20251001-v1:0" -> "anthropic.claude-haiku-4-5-20251001-v1:0"
  base_model_id = trimprefix(var.model_id, "eu.")
}

# ---------- Agent image repository ----------

resource "aws_ecr_repository" "agent" {
  #checkov:skip=CKV_AWS_136:demo, AES256 encryption managed by ECR (KMS CMK in production)
  name                 = var.project_name
  image_tag_mutability = "IMMUTABLE"
  # Demo: images are deleted by terraform destroy.
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
      description  = "Keep the last 10 images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 10 }
      action       = { type = "expire" }
    }]
  })
}

# ---------- Agent execution role (least privilege) ----------

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
    # Form prescribed by the AgentCore documentation (runtime-permissions): account and Region
    # are fixed, the runtime ARN does not exist yet when the role is created.
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
    # Converse = bedrock:InvokeModel (the agent does not stream).
    actions = ["bedrock:InvokeModel"]
    resources = [
      "arn:${local.partition}:bedrock:${var.region}:${local.account_id}:inference-profile/${var.model_id}",
      # An eu. profile only routes to European Regions.
      "arn:${local.partition}:bedrock:eu-*::foundation-model/${local.base_model_id}",
    ]
  }
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

# The AgentCore runtime lives in a separate configuration (infra/runtime), which receives the role
# and the parameters through the outputs below: an apply of the base cannot destroy it.
