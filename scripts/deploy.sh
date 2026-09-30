#!/usr/bin/env bash
# Deploie la Knowledge Base, indexe la documentation, construit l'image de l'agent et cree
# le runtime AgentCore. Prerequis : AWS CLI v2, Docker (ou Finch), Java 21+, Maven, jq.
# Usage : ./scripts/deploy.sh   (variables optionnelles : AWS_REGION, AWS_PROFILE, STACK_NAME)
set -euo pipefail

REGION="${AWS_REGION:-eu-west-1}"
STACK="${STACK_NAME:-techassist-agent}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$ROOT/.deploy"
mkdir -p "$OUT_DIR"

cfn_deploy() {
  aws cloudformation deploy --region "$REGION" --stack-name "$STACK" \
    --template-file "$ROOT/infra/template.yaml" --capabilities CAPABILITY_IAM \
    --no-fail-on-empty-changeset --parameter-overrides "ImageUri=$1"
}
output() {
  aws cloudformation describe-stacks --region "$REGION" --stack-name "$STACK" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text
}

echo "1/5 Socle : bucket, Knowledge Base managee, depot ECR, role d'execution"
EXISTING_IMAGE=$(aws cloudformation describe-stacks --region "$REGION" --stack-name "$STACK" \
  --query "Stacks[0].Parameters[?ParameterKey=='ImageUri'].ParameterValue" --output text 2>/dev/null || true)
[ "$EXISTING_IMAGE" = "None" ] && EXISTING_IMAGE=""
cfn_deploy "$EXISTING_IMAGE"

BUCKET=$(output DocsBucketName); KB_ID=$(output KnowledgeBaseId); DS_ID=$(output DataSourceId)
REPO=$(output RepositoryUri)

echo "2/5 Documentation et indexation"
aws s3 sync "$ROOT/sample-docs/" "s3://$BUCKET/docs/" --region "$REGION" --delete
JOB_ID=$(aws bedrock-agent start-ingestion-job --region "$REGION" \
  --knowledge-base-id "$KB_ID" --data-source-id "$DS_ID" --query ingestionJob.ingestionJobId --output text)
while true; do
  STATUS=$(aws bedrock-agent get-ingestion-job --region "$REGION" --knowledge-base-id "$KB_ID" \
    --data-source-id "$DS_ID" --ingestion-job-id "$JOB_ID" --query ingestionJob.status --output text)
  echo "   statut : $STATUS"
  case "$STATUS" in COMPLETE) break ;; FAILED|STOPPED) echo "Indexation en echec"; exit 1 ;; esac
  sleep 15
done

echo "3/5 Build du jar"
(cd "$ROOT" && mvn -q -B package -DskipTests)

echo "4/5 Image ARM64 et push vers ECR"
TAG="$(date +%Y%m%d-%H%M%S)"
IMAGE="$REPO:$TAG"
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "${REPO%%/*}"
docker build --platform linux/arm64 -t "$IMAGE" "$ROOT"
docker push "$IMAGE"

echo "5/5 Runtime AgentCore avec l'image $TAG"
cfn_deploy "$IMAGE"

cat > "$OUT_DIR/outputs.sh" <<EOF
export AWS_REGION=$REGION
export KNOWLEDGE_BASE_ID=$KB_ID
export MODEL_ID=$(output ModelId)
export AGENT_RUNTIME_ARN=$(output AgentRuntimeArn)
EOF
echo "Pret. Tester : ./scripts/invoke.sh \"J'ai un code F28\" INT-2026-0412"
