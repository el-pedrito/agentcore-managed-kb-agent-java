# ---------- Runtime AgentCore ----------
# Configuration separee du socle (infra/base) : le runtime a toujours une image, sans count.
# Un apply sans image_uri echoue au plan, il ne peut plus detruire le runtime.
# Un changement d'image publie une nouvelle version du runtime.

resource "aws_bedrockagentcore_agent_runtime" "agent" {
  agent_runtime_name = replace(var.project_name, "-", "_")
  description        = "Agent technicien (Spring AI) avec recherche dans la Managed Knowledge Base"
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
    # Le Runtime joint le conteneur sur le port 8080 : ecoute sur toutes les interfaces du
    # conteneur (l'acces est authentifie en SigV4 par AgentCore).
    SERVER_ADDRESS    = "0.0.0.0"
    AWS_REGION        = var.region
    KNOWLEDGE_BASE_ID = var.knowledge_base_id
    MODEL_ID          = var.model_id
    # Jamais deduit d'une valeur vide : desactiver le controle passe par grounding_check = false.
    GROUNDING_CHECK   = tostring(var.grounding_check)
    GUARDRAIL_ID      = var.guardrail_id
    GUARDRAIL_VERSION = var.guardrail_version
  }
}
