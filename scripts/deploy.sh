#!/usr/bin/env bash
# Deploie l'agent sur AgentCore Runtime en deux configurations Terraform :
#   infra/base    : depot ECR, role d'execution et permissions (ne contient pas le runtime) ;
#   infra/runtime : le runtime seul, alimente par les outputs du socle et l'image poussee.
# Entre les deux, l'image ARM64 est construite et poussee avec Jib (sans daemon Docker).
#
# La Knowledge Base est celle de la demo 1. Deployer d'abord bedrock-managed-kb-rag-java, ou
# fournir KNOWLEDGE_BASE_ID.
# Prerequis : Terraform 1.9+, AWS CLI v2, Java 25, Maven, jq.
# Usage : AWS_PROFILE=<profil> EXPECTED_ACCOUNT_ID=<compte> ./scripts/deploy.sh
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
INFRA="$ROOT/infra"
OUT_DIR="$ROOT/.deploy"
DEMO1_OUTPUTS="${DEMO1_OUTPUTS:-$ROOT/../bedrock-managed-kb-rag-java/.deploy/outputs.sh}"
mkdir -p "$OUT_DIR"

# Lit une valeur des outputs de la demo 1 dans un sous-shell (fichier ecrit avec printf %q).
demo1() { ( set +u; source "$DEMO1_OUTPUTS"; printf '%s' "${!1:-}" ); }
if [ -f "$DEMO1_OUTPUTS" ]; then
  KNOWLEDGE_BASE_ID="${KNOWLEDGE_BASE_ID:-$(demo1 KNOWLEDGE_BASE_ID)}"
  GUARDRAIL_ID="${GUARDRAIL_ID:-$(demo1 GUARDRAIL_ID)}"
  GUARDRAIL_VERSION="${GUARDRAIL_VERSION:-$(demo1 GUARDRAIL_VERSION)}"
fi
: "${KNOWLEDGE_BASE_ID:?Fournir KNOWLEDGE_BASE_ID ou deployer la demo 1 (bedrock-managed-kb-rag-java)}"
export TF_VAR_knowledge_base_id="$KNOWLEDGE_BASE_ID"
# Controle d'ancrage actif par defaut : le garde-fou de la demo 1 est alors obligatoire.
# Le desactiver est un choix explicite : GROUNDING_CHECK=false.
if [ "${GROUNDING_CHECK:-true}" = "false" ]; then
  echo "ATTENTION : controle d'ancrage desactive (GROUNDING_CHECK=false)"
  export TF_VAR_grounding_check=false
else
  : "${GUARDRAIL_ID:?Garde-fou absent : deployer la demo 1, fournir GUARDRAIL_ID, ou GROUNDING_CHECK=false}"
  : "${GUARDRAIL_VERSION:?Version du garde-fou absente : deployer la demo 1 ou fournir GUARDRAIL_VERSION}"
  export TF_VAR_grounding_check=true
fi
export TF_VAR_guardrail_id="${GUARDRAIL_ID:-}"
export TF_VAR_guardrail_version="${GUARDRAIL_VERSION:-}"
[ -n "${MODEL_ID:-}" ] && export TF_VAR_model_id="$MODEL_ID"

BASE="$INFRA/base"
RUNTIME="$INFRA/runtime"
tf() { terraform -chdir="$BASE" output -raw "$1"; }
terraform -chdir="$BASE" init -input=false > /dev/null
terraform -chdir="$RUNTIME" init -input=false > /dev/null

echo "1/3 Socle : depot ECR, role d'execution et permissions (le runtime n'est pas touche)"
terraform -chdir="$BASE" apply -input=false -auto-approve > /dev/null
REGION=$(tf region)
REPO=$(tf repository_url)

echo "2/3 Tests puis image ARM64 poussee vers ECR (Jib)"
TAG="$(date +%Y%m%d-%H%M%S)"
IMAGE="$REPO:$TAG"
# Jetons ECR dans un config.json Docker temporaire (droits 600, supprime en sortie) : ils ne
# passent jamais sur la ligne de commande (visible avec ps). Jib lit $DOCKER_CONFIG.
# Image de base sur ECR Public : un pull authentifie evite la limite de debit des pulls anonymes.
# Le jeton ECR Public s'obtient uniquement en us-east-1.
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

echo "3/3 Runtime AgentCore avec l'image $TAG"
# Toutes les valeurs viennent des outputs du socle : le runtime ne peut pas diverger du role
# (modele et garde-fou autorises) ni de la Knowledge Base.
# Affectation puis export : sous set -e, un output illisible arrete le script.
TF_VAR_role_arn="$(tf agent_role_arn)"
TF_VAR_knowledge_base_id="$(tf knowledge_base_id)"
TF_VAR_model_id="$(tf model_id)"
TF_VAR_grounding_check="$(tf grounding_check)"
TF_VAR_guardrail_id="$(tf guardrail_id)"
TF_VAR_guardrail_version="$(tf guardrail_version)"
export TF_VAR_role_arn TF_VAR_knowledge_base_id TF_VAR_model_id TF_VAR_grounding_check \
  TF_VAR_guardrail_id TF_VAR_guardrail_version
terraform -chdir="$RUNTIME" apply -input=false -auto-approve -var "image_uri=$IMAGE"
AGENT_RUNTIME_ARN="$(terraform -chdir="$RUNTIME" output -raw agent_runtime_arn)"

# Le groupe de logs est cree par AgentCore au premier demarrage : la retention est posee ici
# (sinon les logs sont conserves sans limite).
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
  echo "   retention $(tf log_retention_days) jours : $LG"
done
[ -n "$LOG_GROUPS" ] || echo "   groupe de logs pas encore cree : relancer deploy.sh apres le premier appel"

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
echo "Pret. Tester : ./scripts/invoke.sh INT-2026-0412 <<< \"J'ai un code F28, que faire ?\""
