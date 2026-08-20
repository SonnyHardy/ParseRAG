# ParseRAG

**ParseRAG turns any PDF into clean, structured JSON for your RAG pipeline.**

One endpoint, one upload, and you get back embedding-ready chunks: text extracted in real reading
order, repeated headers and footers stripped, tables returned as structured JSON, and scanned pages
handled by a vision model. Every chunk carries a confidence score, so a bad page never slips into
your index unnoticed.

---

## Quick Start

### 1. Get an API key

ParseRAG is served through RapidAPI — subscribe to a plan and copy your key. See
[Pricing](#pricing). Prefer to run it yourself? Jump to [Run it yourself](#run-it-yourself); a dev
key is seeded for you.

### 2. Send a PDF

```bash
export BASE_URL="https://parserag.p.rapidapi.com"
export API_KEY="your-rapidapi-key"

curl -X POST "$BASE_URL/api/v1/parse" \
  -H "X-RapidAPI-Key: $API_KEY" \
  -H "X-RapidAPI-Host: parserag.p.rapidapi.com" \
  -F "file=@document.pdf"
```

RapidAPI authenticates the call, applies your plan's quota and forwards it to ParseRAG. Running the
API yourself instead? Use `-H "X-API-Key: …"` against your own host — everything else is identical.

### 3. Read the chunks

```json
{
  "document_id": "doc_9f3c1a7b",
  "pages": 12,
  "language": "en",
  "processing_ms": 1843,
  "status": "ok",
  "chunks": [
    {
      "id": "chunk_doc_9f3c1a7b_000",
      "text": "Retrieval-augmented generation grounds a language model in an external corpus...",
      "type": "PARAGRAPH",
      "page": 1,
      "char_start": 0,
      "char_end": 1874,
      "confidence": 0.94,
      "fallback_used": false,
      "manual_review_needed": false
    }
  ]
}
```

Each element of `chunks` is one embedding call away from your vector store. That is the whole
integration.

---

## Code examples

Full, runnable versions live in [`docs/examples/`](docs/examples). Below is the short form.

### cURL

```bash
curl -X POST "$BASE_URL/api/v1/parse" \
  -H "X-RapidAPI-Key: $API_KEY" \
  -F "file=@document.pdf" \
  | python3 -m json.tool
```

### Python

```python
import requests

with open("document.pdf", "rb") as f:
    response = requests.post(
        f"{BASE_URL}/api/v1/parse",
        headers={"X-RapidAPI-Key": API_KEY},
        files={"file": ("document.pdf", f, "application/pdf")},
        timeout=300,
    )

response.raise_for_status()
for chunk in response.json()["chunks"]:
    print(chunk["page"], chunk["type"], chunk["text"][:80])
```

### Java (OkHttp)

```java
OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(Duration.ofMinutes(5))   // large PDFs take a while
        .build();

RequestBody body = new MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("file", "document.pdf",
                RequestBody.create(new File("document.pdf"), MediaType.parse("application/pdf")))
        .build();

Request request = new Request.Builder()
        .url(baseUrl + "/api/v1/parse")
        .addHeader("X-RapidAPI-Key", apiKey)
        .post(body)
        .build();

try (Response response = client.newCall(request).execute()) {
    System.out.println(response.body().string());
}
```

---

## Endpoints

| Method | Path             | Description                                          |
|--------|------------------|------------------------------------------------------|
| `POST` | `/api/v1/parse`  | Parse a PDF into chunks. Counts as one request against your plan. |

Every request must carry your key. The machine-readable contract is
[`docs/openapi.json`](docs/openapi.json) (OpenAPI 3.1) — import it into Postman, Insomnia or your own
client generator.

Your remaining balance is on your RapidAPI dashboard, and every response carries
`x-ratelimit-requests-remaining` / `-reset` from the marketplace proxy.

### `POST /api/v1/parse`

`multipart/form-data`, one part named `file`, holding a PDF.

| Constraint     | Value                                                              |
|----------------|--------------------------------------------------------------------|
| Max file size  | 50 MB                                                              |
| Max pages      | Plan-dependent — see [Rate limits & quotas](#rate-limits--quotas)  |
| Content type   | `application/pdf` (or `application/octet-stream`); the PDF signature is verified regardless |

Parsing is synchronous: the response comes back when the document is done. Budget generous client
timeouts — a large scanned document goes through a vision model page by page.

---

## Response Reference

### `ParseResponse`

| Field           | Type     | Description                                                                 |
|-----------------|----------|-----------------------------------------------------------------------------|
| `document_id`   | string   | Identifier generated for this parse (`doc_` + 8 hex chars). Prefixes every chunk id. |
| `pages`         | integer  | Number of pages read.                                                       |
| `language`      | string   | ISO 639-1 code, from the PDF metadata when present, otherwise guessed from stop words. One of `fr`, `en`, `de`, `es`, `unknown`. |
| `processing_ms` | integer  | Server-side processing time, in milliseconds.                               |
| `chunks`        | array    | The chunks, sorted by page.                                                 |
| `status`        | string   | `"ok"` on a 200. Failures return an [error object](#error-codes) instead.    |

### `Chunk`

| Field                  | Type    | Description                                                          |
|------------------------|---------|----------------------------------------------------------------------|
| `id`                   | string  | `chunk_<document_id>_<nnn>`, sequential within the document.          |
| `text`                 | string  | The chunk text. On a `TABLE` chunk, the table linearised as pipe-separated rows, embeddable as-is. |
| `type`                 | enum    | `PARAGRAPH`, `TABLE`, `FIGURE_CAPTION` or `HEADER_ARTIFACT`.          |
| `page`                 | integer | 1-based page the chunk came from.                                     |
| `char_start`           | integer | Start offset in the page text. `0` on `TABLE` chunks.                 |
| `char_end`             | integer | End offset in the page text. `0` on `TABLE` chunks.                   |
| `confidence`           | number  | Between 0 and 1. Lowered when the reading order looks interleaved or the text carries extraction artefacts. |
| `fallback_used`        | boolean | `true` when a vision model produced this chunk — a scanned page, or a table grid Tabula could not read. |
| `manual_review_needed` | boolean | `true` when confidence fell under the review threshold. Treat these as suspect rather than indexing them blindly. |
| `table_json`           | object  | **Only on `TABLE` chunks**, omitted otherwise. See below.             |

### `table_json`

```json
{
  "caption": "Table 2: Accuracy by model",
  "headers": ["Model", "SNLI", "MultiNLI"],
  "rows": [
    ["ESIM+GloVe", "51.9", "52.7"],
    ["BERT-base",  "56.3", "58.1"]
  ]
}
```

`caption` is present only when one was found next to the table. `headers` is an array of strings,
`rows` an array of equal-length row arrays.

### Chunk types

| Type              | What it is                                                              |
|-------------------|-------------------------------------------------------------------------|
| `PARAGRAPH`       | Body text, the common case.                                             |
| `TABLE`           | A table: structured in `table_json`, linearised in `text`.               |
| `FIGURE_CAPTION`  | A figure caption or title.                                              |
| `HEADER_ARTIFACT` | A repeated header/footer line that survived cleaning. Usually safe to drop. |

---

## Error Codes

Every error — from the auth filter, the rate limiter or the pipeline — comes back in the same shape:

```json
{
  "error": "FILE_TOO_LARGE",
  "message": "File size 63.2 MB exceeds the 50 MB limit.",
  "status": 413
}
```

Branch on `error`, which is stable. `message` is for humans and may change between releases.

| Code                   | HTTP | When                                                              |
|------------------------|------|-------------------------------------------------------------------|
| `MISSING_API_KEY`      | 401  | No key on the request (self-hosted: no `X-API-Key` header).        |
| `INVALID_API_KEY`      | 403  | Unknown or deactivated key.                                       |
| `MISSING_FILE`         | 400  | No `file` part, or an empty one.                                  |
| `INVALID_FILE_FORMAT`  | 415  | Content type is neither `application/pdf` nor `application/octet-stream`. |
| `INVALID_FILE_FORMAT`  | 400  | Content type was right, but the bytes do not start with the `%PDF` signature. |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | The request itself is not `multipart/form-data`.                 |
| `FILE_TOO_LARGE`       | 413  | Over the 50 MB limit.                                             |
| `PDF_UNREADABLE`       | 400  | Corrupted, or encrypted with a password.                          |
| `DOCUMENT_TOO_LONG`    | 422  | More pages than your plan allows.                                 |
| `RATE_LIMIT_EXCEEDED`  | 429  | You are sending too fast. Wait `Retry-After` seconds.             |
| `INVALID_PROXY_SECRET` | 403  | The call did not come through the RapidAPI marketplace.            |
| `MARKETPLACE_REQUIRED` | 403  | A direct API key was used while the marketplace is serving traffic. Go through RapidAPI. |
| `NOT_FOUND`            | 404  | No such endpoint.                                                 |
| `INTERNAL_ERROR`       | 500  | Unexpected server-side failure. Safe to retry.                    |
| `SERVICE_BUSY`         | 503  | The server is at capacity. Retry shortly — parses are bounded to protect memory. |
| `DATABASE_UNAVAILABLE` | 503  | The service could not verify your key. Retry shortly.             |

Quota rejections do not appear in this table: they are returned by the RapidAPI proxy before the
request ever reaches ParseRAG, in the marketplace's own format.

---

## Rate Limits & Quotas

**Requests per minute and documents per month are set by your RapidAPI plan** and enforced by the
marketplace proxy — see the listing for the current tiers. Every response carries
`x-ratelimit-requests-remaining` and `x-ratelimit-requests-reset`.

ParseRAG enforces two limits of its own, per plan — the first because the marketplace counts
requests and cannot see how big a job is, the second as a second barrier behind the proxy:

| Plan      | Pages per document | Requests per minute |
|-----------|--------------------|---------------------|
| `FREE`    | 100                | 10                  |
| `STARTER` | 300                | 30                  |
| `PRO`     | 500                | 100                 |
| `SCALE`   | 1 000              | 300                 |

Over the page cap, the call is rejected with `DOCUMENT_TOO_LONG` (422); the 50 MB file cap applies on
every plan. Over the rate, `RATE_LIMIT_EXCEEDED` (429) with a `Retry-After` — the budget is yours
alone, so another customer's traffic never costs you a rejection.

---

## Pricing

ParseRAG is distributed through RapidAPI, with a free tier to get started.

> **Coming soon** — the RapidAPI listing is not published yet. Until then, run ParseRAG yourself
> (below); the plans above are the ones the API enforces today.

---

## Run it yourself

**Requirements**: Java 25, a reachable PostgreSQL instance. The Maven wrapper is committed, so no
Maven install is needed.

```bash
git clone https://github.com/SonnyHardy/ParseRAG.git
cd ParseRAG
```

Create a `.env` file at the project root:

```properties
POSTGRES_PORT=5432
POSTGRES_DB=parserag
POSTGRES_USER=postgres
POSTGRES_PASSWORD=your-password

# Your own admin key, as a SHA-256 hash — see below
ADMIN_KEY_HASH=

# Optional
SERVER_PORT=8080
GOOGLE_API_KEY=          # vision fallback for scanned pages; without it those pages are flagged for manual review
RAPIDAPI_PROXY_SECRET=   # marketplace integration; empty locally, where the internal key authenticates
```

Then:

```bash
./mvnw spring-boot:run
```

**No key is seeded** — pick your own and give ParseRAG its hash, never the key itself:

```bash
KEY="$(openssl rand -hex 24)"                    # your key; keep it
printf '%s' "$KEY" | sha256sum                   # put this hash in ADMIN_KEY_HASH
```

On startup, ParseRAG creates the matching admin key if it does not exist yet. Then:

```bash
curl -X POST http://localhost:8080/api/v1/parse \
  -H "X-API-Key: $KEY" \
  -F "file=@document.pdf"
```

Leave `ADMIN_KEY_HASH` empty and no key is created at all: the API starts, says so in the logs, and
`GET /api/v1/health` stays unreachable. A missing setting should cost you a diagnostic, never mint a
credential.

Once `RAPIDAPI_PROXY_SECRET` is set — that is, once the marketplace is serving your traffic — direct
keys are **restricted to administration**: a non-admin key calling the origin gets
`403 MARKETPLACE_REQUIRED`.

### Build and test

```bash
./mvnw clean package   # build + run tests
./mvnw test            # tests only
```

### Run it in Docker

```bash
docker build -t parserag .
docker run -p 8080:8080 \
  -e POSTGRES_HOST=host.docker.internal -e POSTGRES_PORT=5432 \
  -e POSTGRES_DB=parserag -e POSTGRES_USER=parserag -e POSTGRES_PASSWORD=… \
  -e ADMIN_KEY_HASH=… -e HEALTH_DISK_PATH=/tmp \
  parserag
```

The image runs as a non-root user and carries `fontconfig` plus a base font: PDFBox renders pages to
images for table extraction and the scanned-page fallback, and a fontless image fails at exactly that
point — on scanned documents only, in production only.

### Deploy

| Variable | Required | Notes |
|---|---|---|
| `POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | yes | Flyway migrates on startup |
| `ADMIN_KEY_HASH` | yes | SHA-256 of your admin key; without it no admin key exists |
| `RAPIDAPI_PROXY_SECRET` | yes in production | Without it the origin serves anyone who finds its URL |
| `GOOGLE_API_KEY` | no | Vision fallback; scanned pages are flagged for review without it |
| `HEALTH_DISK_PATH` | no | Set to `/tmp` in a container — the disk probe must watch the volume PDFBox writes to |
| `OTEL_ENABLED`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_AUTH`, `DEPLOY_ENV` | no | Telemetry export |

`railway.json` points Railway at the Dockerfile and at `/actuator/health` — the only unauthenticated
endpoint, returning a bare `UP`/`DOWN` with no details. The detailed `/api/v1/health` stays
admin-only.

**Migrations run at startup**, so a deploy that rolls out two instances at once has them both call
Flyway; Flyway takes a lock, and the second waits. Rolling *back* the application over an already
migrated schema is not covered by that lock: a migration does not un-apply itself, so plan a
forward-fix rather than a version rollback.

### Regenerating the OpenAPI spec

[`docs/openapi.json`](docs/openapi.json) is a committed snapshot, generated from the code by
springdoc. Springdoc is **disabled by default** — the spec is served only under the `docs` profile,
so no documentation endpoint is exposed in production:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=docs

# in another shell — $KEY is your admin key (see ADMIN_KEY_HASH); no path is exempt
curl -sS -H "X-API-Key: $KEY" http://localhost:8080/v3/api-docs   | python3 -m json.tool > docs/openapi.json
```

The `json.tool` pass is what keeps the committed file readable and its diffs reviewable — springdoc
serves the spec on a single line.

Swagger UI is available at `http://localhost:8080/swagger-ui.html` under that same profile, for
reading the spec in a browser.

Regenerate it whenever you change an endpoint, a response model or an error code.

---

## How it works

```
PDF ──▶ Validate ──▶ Extract ──▶ Clean ──▶ Chunk ──▶ JSON
                        │           │         │
             column-aware,     repeated   overlapping
             tables + vision   headers/   windows with
             fallback          footers    confidence scores
```

- **Extract** — text is assembled band by band, following the page's actual column layout, then
  verified: if the output shows the signature of interleaved columns, the page is re-assembled with
  different splits. Tables are pulled out separately, and a quality gate decides whether a vision
  model should re-read a broken grid.
- **Clean** — repeated headers and footers are detected by three stacked layers (geometric
  recurrence, boilerplate patterns, density clustering) and stripped.
- **Chunk** — text is cut into overlapping windows sized for embedding, each scored, and anything
  under the confidence threshold is flagged `manual_review_needed`.

Scanned or image-only pages skip native extraction and go to the vision model directly. When no
vision provider is configured, those pages come back flagged rather than empty.

---

## License

Proprietary. All rights reserved.
