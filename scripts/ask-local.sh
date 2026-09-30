#!/usr/bin/env bash
# Appelle l'agent lance en local (mvn spring-boot:run), meme contrat qu'AgentCore Runtime.
# Usage : ./scripts/ask-local.sh "question" [INT-2026-0412] [session-id]
set -euo pipefail
PROMPT="${1:?question requise}"
INTERVENTION="${2:-}"
SESSION="${3:-local-session}"
PAYLOAD=$(jq -n --arg p "$PROMPT" --arg i "$INTERVENTION" \
  'if $i == "" then {prompt: $p} else {prompt: $p, interventionId: $i} end')
curl -s -X POST http://localhost:8080/invocations \
  -H "Content-Type: application/json" \
  -H "X-Amzn-Bedrock-AgentCore-Runtime-Session-Id: $SESSION" \
  -d "$PAYLOAD" | jq .
