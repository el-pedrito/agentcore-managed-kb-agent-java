#!/usr/bin/env bash
# Supprime le runtime, le depot ECR (images comprises) et le role de l'agent.
# La Knowledge Base (demo 1) n'est pas touchee.
# Usage : AWS_PROFILE=<profil> EXPECTED_ACCOUNT_ID=<compte> ./scripts/destroy.sh
set -euo pipefail

# Garde-fou de compte : on ne deploie (ni ne supprime) que dans le compte attendu. Terraform le
# verifie aussi (allowed_account_ids), y compris pour un apply lance a la main.
: "${EXPECTED_ACCOUNT_ID:?Fournir EXPECTED_ACCOUNT_ID, le compte AWS cible (12 chiffres)}"
read -r CALLER_ACCOUNT CALLER_ARN <<< "$(aws sts get-caller-identity --query '[Account,Arn]' --output text)"
if [ "$CALLER_ACCOUNT" != "$EXPECTED_ACCOUNT_ID" ]; then
  echo "Compte courant $CALLER_ACCOUNT different du compte attendu $EXPECTED_ACCOUNT_ID : arret." >&2
  exit 1
fi
echo "Compte $CALLER_ACCOUNT, identite $CALLER_ARN"
export TF_VAR_account_id="$EXPECTED_ACCOUNT_ID"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

read -r -p "Supprimer le runtime AgentCore, le depot ECR et le role de l'agent ? [oui/non] " CONFIRM
[ "$CONFIRM" = "oui" ] || { echo "Annule."; exit 0; }

BASE="$ROOT/infra/base"
RUNTIME="$ROOT/infra/runtime"
terraform -chdir="$BASE" init -input=false > /dev/null
terraform -chdir="$RUNTIME" init -input=false > /dev/null

# Runtime d'abord (il utilise le role du socle), avec ses propres outputs : il est supprime meme si
# le state du socle est vide ou perdu.
if terraform -chdir="$RUNTIME" state list | grep -q .; then
  INPUTS="$(terraform -chdir="$RUNTIME" output -json inputs)"
  input() { jq -er --arg k "$1" 'if .[$k] == null then "" else (.[$k] | tostring) end' <<< "$INPUTS"; }
  # Affectation puis usage : sous set -e, une valeur illisible arrete le script.
  R_IMAGE="$(terraform -chdir="$RUNTIME" output -raw image_uri)"
  R_ROLE="$(input role_arn)"
  R_KB="$(input knowledge_base_id)"
  R_MODEL="$(input model_id)"
  R_GROUNDING="$(input grounding_check)"
  R_GUARDRAIL="$(input guardrail_id)"
  R_GUARDRAIL_VERSION="$(input guardrail_version)"
  terraform -chdir="$RUNTIME" destroy -input=false -auto-approve \
    -var "image_uri=$R_IMAGE" -var "role_arn=$R_ROLE" -var "knowledge_base_id=$R_KB" \
    -var "model_id=$R_MODEL" -var "grounding_check=$R_GROUNDING" -var "guardrail_id=$R_GUARDRAIL" \
    -var "guardrail_version=$R_GUARDRAIL_VERSION"
else
  echo "Runtime absent du state."
fi

if terraform -chdir="$BASE" state list | grep -q .; then
  out() { terraform -chdir="$BASE" output -raw "$1"; }
  # Affectation puis export : sous set -e, un output illisible arrete le script.
  TF_VAR_knowledge_base_id="$(out knowledge_base_id)"
  TF_VAR_model_id="$(out model_id)"
  TF_VAR_grounding_check="$(out grounding_check)"
  TF_VAR_guardrail_id="$(out guardrail_id)"
  TF_VAR_guardrail_version="$(out guardrail_version)"
  export TF_VAR_knowledge_base_id TF_VAR_model_id TF_VAR_grounding_check TF_VAR_guardrail_id \
    TF_VAR_guardrail_version
  terraform -chdir="$BASE" destroy -input=false -auto-approve
else
  echo "Socle absent du state."
fi
rm -f "$ROOT/.deploy/outputs.sh"
echo "Ressources de l'agent supprimees."
