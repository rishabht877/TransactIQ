#!/usr/bin/env bash
#
# Pull the fraud-service LLM into the running Ollama container. Run once after `docker compose up`
# (the model is cached in the ollama-data volume afterwards).
#
# Usage: scripts/pull-ollama-model.sh [model]   (default: llama3.2)
set -euo pipefail

MODEL="${1:-llama3.2}"
echo "Pulling '$MODEL' into transactiq-ollama (this can take a few minutes on first run)..."
docker exec transactiq-ollama ollama pull "$MODEL"
echo "Done. Installed models:"
docker exec transactiq-ollama ollama list
