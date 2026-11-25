#!/usr/bin/env bash
# Runs the server tests in Docker (Node 22): local D1, fake Google, wrangler dev, then test/api.test.mjs.
set -euo pipefail
cd "$(dirname "$0")/.."
docker run --rm -v "$PWD":/app -w /app -e HOME=/tmp node:22-slim bash -c '
  set -e
  npm install --silent --no-audit --no-fund >/dev/null
  rm -rf .wrangler/state
  npx wrangler d1 execute pp --local --file schema.sql >/dev/null
  node test/fake-google.mjs & 
  npx wrangler dev --local --port 8787 --ip 127.0.0.1 \
    --var DEV_SHOW_CODE:1 --var GOOGLE_CLIENT_ID:test-client --var GOOGLE_CLIENT_SECRET:test-secret \
    --var GOOGLE_AUTH_URL:http://127.0.0.1:9999/auth --var GOOGLE_TOKEN_URL:http://127.0.0.1:9999/token --var ZEFFY_API_KEY:test-zeffy --var ZEFFY_API_URL:http://127.0.0.1:9999/zeffy >/tmp/dev.log 2>&1 &
  for i in $(seq 60); do curl -s 127.0.0.1:8787/ >/dev/null 2>&1 && break; node -e "fetch(\"http://127.0.0.1:8787/\").then(()=>process.exit(0)).catch(()=>process.exit(1))" && break; sleep 1; done
  node test/api.test.mjs || { tail -30 /tmp/dev.log; exit 1; }'
