#!/usr/bin/env bash
# Appelle l'agent deploye sur AgentCore Runtime (authentification IAM SigV4 via l'AWS CLI).
# Usage : ./scripts/invoke.sh "question" [INT-2026-0412] [session-id]
#   Reutiliser le meme session-id permet d'enchainer les questions (memoire de conversation).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/.deploy/outputs.sh"

PROMPT="${1:?question requise}"
INTERVENTION="${2:-}"
# AgentCore impose un identifiant de session d'au moins 33 caracteres.
SESSION="${3:-technicien-demo-$(date +%s)-$(uuidgen | tr -d '-' | cut -c1-12)}"

PAYLOAD=$(jq -n --arg p "$PROMPT" --arg i "$INTERVENTION" \
  'if $i == "" then {prompt: $p} else {prompt: $p, interventionId: $i} end')
OUT=$(mktemp)
aws bedrock-agentcore invoke-agent-runtime --region "$AWS_REGION" \
  --agent-runtime-arn "$AGENT_RUNTIME_ARN" \
  --runtime-session-id "$SESSION" \
  --content-type application/json \
  --payload "$(printf '%s' "$PAYLOAD" | base64)" \
  "$OUT" > /dev/null
jq . "$OUT"
rm "$OUT"
echo "session : $SESSION"
