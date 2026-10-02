# ---------- AgentCore runtime ----------
# Separate from the base (infra/base): the runtime always has an image, no count.
# An apply without image_uri fails at plan time, so it cannot destroy the runtime.
# An image change publishes a new runtime version.

resource "aws_bedrockagentcore_agent_runtime" "agent" {
  agent_runtime_name = replace(var.project_name, "-", "_")
  description        = "Technician agent (Spring AI) with Managed Knowledge Base search"
  role_arn           = var.role_arn

  agent_runtime_artifact {
    container_configuration {
      container_uri = var.image_uri
    }
  }

  network_configuration {
    network_mode = "PUBLIC"
  }

  protocol_configuration {
    server_protocol = "HTTP"
  }

  environment_variables = {
    # The Runtime reaches the container on port 8080: listen on all container interfaces
    # (access is authenticated with SigV4 by AgentCore).
    SERVER_ADDRESS    = "0.0.0.0"
    AWS_REGION        = var.region
    KNOWLEDGE_BASE_ID = var.knowledge_base_id
    MODEL_ID          = var.model_id
    GUARDRAIL_ID      = var.guardrail_id
    GUARDRAIL_VERSION = var.guardrail_version
  }
}
