#!/usr/bin/env bash
# Supprime toutes les ressources creees par deploy.sh (bucket et images compris).
set -euo pipefail
REGION="${AWS_REGION:-eu-west-1}"
STACK="${STACK_NAME:-techassist-agent}"

BUCKET=$(aws cloudformation describe-stacks --region "$REGION" --stack-name "$STACK" \
  --query "Stacks[0].Outputs[?OutputKey=='DocsBucketName'].OutputValue" --output text)

read -r -p "Supprimer le stack $STACK, le runtime, les images et le bucket $BUCKET ? [oui/non] " CONFIRM
[ "$CONFIRM" = "oui" ] || { echo "Annule."; exit 0; }

# Bucket versionne : supprimer toutes les versions avant le stack.
while true; do
  BATCH=$(aws s3api list-object-versions --region "$REGION" --bucket "$BUCKET" --max-items 500 \
    --query '{Objects: [Versions, DeleteMarkers][][].{Key: Key, VersionId: VersionId}}' --output json)
  [ "$(echo "$BATCH" | jq '.Objects | length')" -eq 0 ] && break
  aws s3api delete-objects --region "$REGION" --bucket "$BUCKET" --delete "$BATCH" > /dev/null
done

aws cloudformation delete-stack --region "$REGION" --stack-name "$STACK"
aws cloudformation wait stack-delete-complete --region "$REGION" --stack-name "$STACK"
echo "Stack $STACK supprime."
