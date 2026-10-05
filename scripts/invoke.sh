#!/usr/bin/env bash
# Calls the agent deployed on AgentCore Runtime (IAM SigV4 authentication through the AWS CLI).
# The question is read from standard input, never as an argument: it shows up neither in `ps`
# nor in the shell history.
# Usage: ./scripts/invoke.sh [INT-2026-0412] [session-id]   then type the question
#        ./scripts/invoke.sh INT-2026-0412 <<< "J'ai un code F28, que dois-je faire ?"
#   Reusing the same session-id (and the same intervention) chains questions.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/.deploy/outputs.sh"

INTERVENTION="${1:-}"
# AgentCore requires a session ID of at least 33 characters.
SESSION="${2:-technician-demo-$(date +%s)-$(uuidgen | tr -d '-' | cut -c1-12)}"
[ -t 0 ] && printf 'Question: ' >&2
IFS= read -r PROMPT || true
[ -n "$PROMPT" ] || { echo "Empty question." >&2; exit 1; }

# Request and response in private files (0600).
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
echo "session: $SESSION"
