#!/usr/bin/env bash
# Deploys the agent on AgentCore Runtime with two Terraform configurations:
#   infra/base    : ECR repository, execution role and permissions (no runtime);
#   infra/runtime : the runtime only, fed by the base outputs and the pushed image.
# In between, the ARM64 image is built and pushed with Jib (no Docker daemon).
#
# The Knowledge Base is the demo 1 one. Deploy bedrock-managed-kb-rag-java first, or
# set KNOWLEDGE_BASE_ID.
# Prerequisites: Terraform 1.9+, AWS CLI v2, Java 25, Maven, jq.
# Usage: AWS_PROFILE=<profile> EXPECTED_ACCOUNT_ID=<account> ./scripts/deploy.sh
set -euo pipefail

# Account guard: we only deploy (or destroy) in the expected account. Terraform checks it too
# (allowed_account_ids), including for an apply run by hand.
: "${EXPECTED_ACCOUNT_ID:?Set EXPECTED_ACCOUNT_ID, the target AWS account (12 digits)}"
read -r CALLER_ACCOUNT CALLER_ARN <<< "$(aws sts get-caller-identity --query '[Account,Arn]' --output text)"
if [ "$CALLER_ACCOUNT" != "$EXPECTED_ACCOUNT_ID" ]; then
  echo "Current account $CALLER_ACCOUNT differs from expected account $EXPECTED_ACCOUNT_ID: stopping." >&2
  exit 1
fi
echo "Account $CALLER_ACCOUNT, identity $CALLER_ARN"
export TF_VAR_account_id="$EXPECTED_ACCOUNT_ID"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INFRA="$ROOT/infra"
OUT_DIR="$ROOT/.deploy"
DEMO1_OUTPUTS="${DEMO1_OUTPUTS:-$ROOT/../bedrock-managed-kb-rag-java/.deploy/outputs.sh}"
mkdir -p "$OUT_DIR"

# Reads a value from the demo 1 outputs in a subshell (file written with printf %q).
demo1() { ( set +u; source "$DEMO1_OUTPUTS"; printf '%s' "${!1:-}" ); }
if [ -f "$DEMO1_OUTPUTS" ]; then
  KNOWLEDGE_BASE_ID="${KNOWLEDGE_BASE_ID:-$(demo1 KNOWLEDGE_BASE_ID)}"
  GUARDRAIL_ID="${GUARDRAIL_ID:-$(demo1 GUARDRAIL_ID)}"
  GUARDRAIL_VERSION="${GUARDRAIL_VERSION:-$(demo1 GUARDRAIL_VERSION)}"
fi
: "${KNOWLEDGE_BASE_ID:?Set KNOWLEDGE_BASE_ID or deploy demo 1 (bedrock-managed-kb-rag-java)}"
export TF_VAR_knowledge_base_id="$KNOWLEDGE_BASE_ID"
# Grounding guardrail of demo 1: mandatory.
: "${GUARDRAIL_ID:?No guardrail: deploy demo 1 or set GUARDRAIL_ID}"
: "${GUARDRAIL_VERSION:?No guardrail version: deploy demo 1 or set GUARDRAIL_VERSION}"
export TF_VAR_guardrail_id="$GUARDRAIL_ID"
export TF_VAR_guardrail_version="$GUARDRAIL_VERSION"
[ -n "${MODEL_ID:-}" ] && export TF_VAR_model_id="$MODEL_ID"

BASE="$INFRA/base"
RUNTIME="$INFRA/runtime"
tf() { terraform -chdir="$BASE" output -raw "$1"; }
terraform -chdir="$BASE" init -input=false > /dev/null
terraform -chdir="$RUNTIME" init -input=false > /dev/null

echo "1/3 Base: ECR repository, execution role and permissions (the runtime is not touched)"
terraform -chdir="$BASE" apply -input=false -auto-approve > /dev/null
REGION=$(tf region)
REPO=$(tf repository_url)

echo "2/3 Tests, then ARM64 image pushed to ECR (Jib)"
TAG="$(date +%Y%m%d-%H%M%S)"
IMAGE="$REPO:$TAG"
# ECR tokens in a temporary Docker config.json (mode 600, deleted on exit): they never go
# on the command line (visible with ps). Jib reads $DOCKER_CONFIG.
# Base image on ECR Public: an authenticated pull avoids the anonymous pull rate limit.
# The ECR Public token can only be obtained in us-east-1.
DOCKER_CONFIG="$(mktemp -d)"
export DOCKER_CONFIG
trap 'rm -r "$DOCKER_CONFIG"' EXIT
umask 077
python3 - "$DOCKER_CONFIG/config.json" "${REPO%%/*}" <<'PY'
import base64, json, subprocess, sys
def token(*args):
    pw = subprocess.run(["aws", *args], check=True, capture_output=True, text=True).stdout.strip()
    return base64.b64encode(("AWS:" + pw).encode()).decode()
auths = {
    sys.argv[2]: {"auth": token("ecr", "get-login-password", "--region", sys.argv[2].split(".")[3])},
    "public.ecr.aws": {"auth": token("ecr-public", "get-login-password", "--region", "us-east-1")},
}
with open(sys.argv[1], "w") as f:
    json.dump({"auths": auths}, f)
PY
(cd "$ROOT" && mvn -q -B package jib:build -Djib.to.image="$IMAGE")

echo "3/3 AgentCore runtime with image $TAG"
# Every value comes from the base outputs: the runtime cannot diverge from the role
# (allowed model and guardrail) nor from the Knowledge Base.
# Assign then export: under set -e, an unreadable output stops the script.
TF_VAR_role_arn="$(tf agent_role_arn)"
TF_VAR_knowledge_base_id="$(tf knowledge_base_id)"
TF_VAR_model_id="$(tf model_id)"
TF_VAR_guardrail_id="$(tf guardrail_id)"
TF_VAR_guardrail_version="$(tf guardrail_version)"
export TF_VAR_role_arn TF_VAR_knowledge_base_id TF_VAR_model_id TF_VAR_guardrail_id TF_VAR_guardrail_version
terraform -chdir="$RUNTIME" apply -input=false -auto-approve -var "image_uri=$IMAGE"
AGENT_RUNTIME_ARN="$(terraform -chdir="$RUNTIME" output -raw agent_runtime_arn)"

# The log group is created by AgentCore on first start: retention is set here
# (otherwise logs are kept forever).
RUNTIME_ID="${AGENT_RUNTIME_ARN##*/}"
for _ in $(seq 1 12); do
  LOG_GROUPS=$(aws logs describe-log-groups --region "$REGION" \
    --log-group-name-prefix "/aws/bedrock-agentcore/runtimes/$RUNTIME_ID" \
    --query 'logGroups[].logGroupName' --output text)
  [ -n "$LOG_GROUPS" ] && break
  sleep 10
done
for LG in $LOG_GROUPS; do
  aws logs put-retention-policy --region "$REGION" --log-group-name "$LG" \
    --retention-in-days "$(tf log_retention_days)"
  echo "   retention $(tf log_retention_days) days: $LG"
done
[ -n "$LOG_GROUPS" ] || echo "   log group not created yet: rerun deploy.sh after the first call"

{
  [ -n "${AWS_PROFILE:-}" ] && printf 'export AWS_PROFILE=%q\n' "$AWS_PROFILE"
  printf 'export AWS_REGION=%q\n' "$REGION"
  printf 'export KNOWLEDGE_BASE_ID=%q\n' "$KNOWLEDGE_BASE_ID"
  printf 'export MODEL_ID=%q\n' "$(tf model_id)"
  printf 'export AGENT_RUNTIME_ARN=%q\n' "$AGENT_RUNTIME_ARN"
  printf 'export GUARDRAIL_ID=%q\n' "${GUARDRAIL_ID:-}"
  printf 'export GUARDRAIL_VERSION=%q\n' "${GUARDRAIL_VERSION:-}"
  printf 'export AGENT_IMAGE=%q\n' "$IMAGE"
} > "$OUT_DIR/outputs.sh"
echo "Ready. Test: ./scripts/invoke.sh INT-2026-0412 <<< \"J'ai un code F28, que faire ?\""
