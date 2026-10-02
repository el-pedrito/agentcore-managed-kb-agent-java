#!/usr/bin/env bash
# Appelle l'agent deploye sur AgentCore Runtime (authentification IAM SigV4 via l'AWS CLI).
# La question est lue sur l'entree standard, jamais en argument : elle n'apparait ni dans `ps`
# ni dans l'historique du shell.
# Usage : ./scripts/invoke.sh [INT-2026-0412] [session-id]   puis taper la question
#         ./scripts/invoke.sh INT-2026-0412 <<< "J'ai un code F28, que dois-je faire ?"
#   Reutiliser le meme session-id (et la meme intervention) enchaine les questions.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/.deploy/outputs.sh"

INTERVENTION="${1:-}"
# AgentCore impose un identifiant de session d'au moins 33 caracteres.
SESSION="${2:-technicien-demo-$(date +%s)-$(uuidgen | tr -d '-' | cut -c1-12)}"
[ -t 0 ] && printf 'Question : ' >&2
IFS= read -r PROMPT || true
[ -n "$PROMPT" ] || { echo "Question vide." >&2; exit 1; }

# Requete et reponse dans des fichiers prives (0600).
umask 077
OUT=$(mktemp)
IN=$(mktemp)
trap 'rm -f "$OUT" "$IN"' EXIT
printf '%s' "$PROMPT" | jq -Rs --arg i "$INTERVENTION" \
  'if $i == "" then {prompt: .} else {prompt: ., interventionId: $i} end' > "$IN"
aws bedrock-agentcore invoke-agent-runtime --region "$AWS_REGION" \
  --agent-runtime-arn "$AGENT_RUNTIME_ARN" \
  --runtime-session-id "$SESSION" \
  --content-type application/json \
  --payload "fileb://$IN" \
  "$OUT" > /dev/null
jq . "$OUT"
echo "session : $SESSION"
