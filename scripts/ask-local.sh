#!/usr/bin/env bash
# Calls the agent running locally (mvn spring-boot:run), same contract as AgentCore Runtime.
# The question is read from standard input and the JSON is passed to curl through stdin: nothing
# of the question shows up in `ps` or in the shell history.
# Usage: ./scripts/ask-local.sh [INT-2026-0412] [session-id]   then type the question
#        ./scripts/ask-local.sh INT-2026-0412 <<< "J'ai un code F28, que dois-je faire ?"
set -euo pipefail
INTERVENTION="${1:-}"
# One session per call by default: pass the same id to chain questions.
SESSION="${2:-local-$(uuidgen)}"
[ -t 0 ] && printf 'Question: ' >&2
IFS= read -r PROMPT || true
[ -n "$PROMPT" ] || { echo "Empty question." >&2; exit 1; }

printf '%s' "$PROMPT" | jq -Rs --arg i "$INTERVENTION" \
  'if $i == "" then {prompt: .} else {prompt: ., interventionId: $i} end' \
  | curl -s --max-time 120 -X POST http://localhost:8080/invocations \
      -H "Content-Type: application/json" \
      -H "X-Amzn-Bedrock-AgentCore-Runtime-Session-Id: $SESSION" \
      --data-binary @- | jq .
echo "session: $SESSION"
