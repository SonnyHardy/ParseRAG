#!/usr/bin/env bash
#
# ParseRAG — parse a PDF with cURL.
#
#   export API_KEY="your-api-key"
#   ./curl.sh document.pdf
#
# BASE_URL defaults to a local run; point it at your deployment.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:?Set API_KEY first: export API_KEY=your-api-key}"
FILE="${1:?Usage: ./curl.sh <file.pdf>}"

# ── Parse ─────────────────────────────────────────────────────────────────────
# -D: dump the response headers to a file so the rate-limit budget can be read
#     alongside the body. --fail-with-body keeps the JSON error object on a 4xx,
#     which plain --fail would throw away.
HEADERS="$(mktemp)"
trap 'rm -f "$HEADERS"' EXIT

HTTP_CODE="$(
  curl -sS -o response.json -D "$HEADERS" -w '%{http_code}' \
    -X POST "$BASE_URL/api/v1/parse" \
    -H "X-API-Key: $API_KEY" \
    -F "file=@${FILE};type=application/pdf" \
    --max-time 300
)"

echo "HTTP $HTTP_CODE"
grep -i -E '^(x-ratelimit-|retry-after)' "$HEADERS" || true

if [ "$HTTP_CODE" != "200" ]; then
  echo "Request failed:" >&2
  python3 -m json.tool < response.json >&2 || cat response.json >&2
  exit 1
fi

python3 -m json.tool < response.json

# ── Current quota ─────────────────────────────────────────────────────────────
# Never blocked by the quota: readable even once it is exhausted.
echo
echo "Usage:"
curl -sS "$BASE_URL/api/v1/usage" -H "X-API-Key: $API_KEY" | python3 -m json.tool
