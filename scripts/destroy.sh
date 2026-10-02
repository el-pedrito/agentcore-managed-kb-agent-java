#!/usr/bin/env bash
# Deletes the runtime, the ECR repository (including images) and the agent role.
# The Knowledge Base (demo 1) is not touched.
# Usage: AWS_PROFILE=<profile> EXPECTED_ACCOUNT_ID=<account> ./scripts/destroy.sh
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

read -r -p "Delete the AgentCore runtime, the ECR repository and the agent role? [yes/no] " CONFIRM
[ "$CONFIRM" = "yes" ] || { echo "Cancelled."; exit 0; }

BASE="$ROOT/infra/base"
RUNTIME="$ROOT/infra/runtime"
terraform -chdir="$BASE" init -input=false > /dev/null
terraform -chdir="$RUNTIME" init -input=false > /dev/null

# Runtime first (it uses the base role), with its own outputs: it is deleted even if
# the base state is empty or lost.
if terraform -chdir="$RUNTIME" state list | grep -q .; then
  INPUTS="$(terraform -chdir="$RUNTIME" output -json inputs)"
  input() { jq -er --arg k "$1" 'if .[$k] == null then "" else (.[$k] | tostring) end' <<< "$INPUTS"; }
  # Assign then use: under set -e, an unreadable value stops the script.
  R_IMAGE="$(terraform -chdir="$RUNTIME" output -raw image_uri)"
  R_ROLE="$(input role_arn)"
  R_KB="$(input knowledge_base_id)"
  R_MODEL="$(input model_id)"
  R_GUARDRAIL="$(input guardrail_id)"
  R_GUARDRAIL_VERSION="$(input guardrail_version)"
  terraform -chdir="$RUNTIME" destroy -input=false -auto-approve \
    -var "image_uri=$R_IMAGE" -var "role_arn=$R_ROLE" -var "knowledge_base_id=$R_KB" \
    -var "model_id=$R_MODEL" -var "guardrail_id=$R_GUARDRAIL" -var "guardrail_version=$R_GUARDRAIL_VERSION"
else
  echo "No runtime in the state."
fi

if terraform -chdir="$BASE" state list | grep -q .; then
  out() { terraform -chdir="$BASE" output -raw "$1"; }
  # Assign then export: under set -e, an unreadable output stops the script.
  TF_VAR_knowledge_base_id="$(out knowledge_base_id)"
  TF_VAR_model_id="$(out model_id)"
  TF_VAR_guardrail_id="$(out guardrail_id)"
  TF_VAR_guardrail_version="$(out guardrail_version)"
  export TF_VAR_knowledge_base_id TF_VAR_model_id TF_VAR_guardrail_id TF_VAR_guardrail_version
  terraform -chdir="$BASE" destroy -input=false -auto-approve
else
  echo "No base in the state."
fi
rm -f "$ROOT/.deploy/outputs.sh"
echo "Agent resources deleted."
