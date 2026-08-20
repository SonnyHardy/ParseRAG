#!/usr/bin/env bash
#
# ParseRAG — parse a PDF with cURL.
#
#   export API_KEY="your-rapidapi-key"
#   ./curl.sh document.pdf
#
# BASE_URL defaults to a local run, where the self-hosted key header is used instead.
# Through the marketplace: BASE_URL=https://parserag.p.rapidapi.com and API_KEY=your RapidAPI key.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:?Set API_KEY first: export API_KEY=your-api-key}"
FILE="${1:?Usage: ./curl.sh <file.pdf>}"

# Through RapidAPI the key travels as X-RapidAPI-Key; a self-hosted instance expects X-API-Key.
case "$BASE_URL" in
  *rapidapi.com*) KEY_HEADER="X-RapidAPI-Key" ;;
  *)              KEY_HEADER="X-API-Key" ;;
esac

# ── Parse ─────────────────────────────────────────────────────────────────────
# -D: dump the response headers to a file so the rate-limit budget can be read
#     alongside the body. --fail-with-body keeps the JSON error object on a 4xx,
#     which plain --fail would throw away.
HEADERS="$(mktemp)"
trap 'rm -f "$HEADERS"' EXIT

HTTP_CODE="$(
  curl -sS -o response.json -D "$HEADERS" -w '%{http_code}' \
    -X POST "$BASE_URL/api/v1/parse" \
    -H "$KEY_HEADER: $API_KEY" \
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

# Quota consumption is tracked by RapidAPI: read it on your marketplace dashboard, or from the
# x-ratelimit-requests-remaining header the proxy adds to every response.
