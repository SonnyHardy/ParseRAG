# ParseRAG

**ParseRAG turns any PDF into clean, structured JSON for your RAG pipeline.**

One endpoint, one upload, and you get back embedding-ready chunks: text extracted in real reading
order, repeated headers and footers stripped, tables returned as structured JSON, and scanned pages
handled by a vision model. Every chunk carries a confidence score, so a bad page never slips into
your index unnoticed.

---

## Quick Start

### 1. Get an API key

ParseRAG is served through RapidAPI — see [Pricing](#pricing). Prefer to run it yourself? Jump to
[Run it yourself](#run-it-yourself); the dev key is seeded for you.

### 2. Send a PDF

```bash
export BASE_URL="http://localhost:8080"   # your deployment, or a local run
export API_KEY="your-api-key"

curl -X POST "$BASE_URL/api/v1/parse" \
  -H "X-API-Key: $API_KEY" \
  -F "file=@document.pdf"
```

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
  -H "X-API-Key: $API_KEY" \
  -F "file=@document.pdf" \
  | python3 -m json.tool
```

### Python

```python
import requests

with open("document.pdf", "rb") as f:
    response = requests.post(
        f"{BASE_URL}/api/v1/parse",
        headers={"X-API-Key": API_KEY},
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
        .addHeader("X-API-Key", apiKey)
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
| `POST` | `/api/v1/parse`  | Parse a PDF into chunks. Consumes one document from your quota on success. |
| `GET`  | `/api/v1/usage`  | Current cycle consumption. Never blocked by the quota — you can always read it. |

Every request needs the `X-API-Key` header, `/api/v1/usage` included. The machine-readable contract
is [`docs/openapi.json`](docs/openapi.json) (OpenAPI 3.1) — import it into Postman, Insomnia or your
own client generator.

### `POST /api/v1/parse`

`multipart/form-data`, one part named `file`, holding a PDF.

| Constraint     | Value                                                              |
|----------------|--------------------------------------------------------------------|
| Max file size  | 50 MB                                                              |
| Max pages      | Plan-dependent — see [Rate limits & quotas](#rate-limits--quotas)  |
| Content type   | `application/pdf` (or `application/octet-stream`); the PDF signature is verified regardless |

Parsing is synchronous: the response comes back when the document is done. Budget generous client
timeouts — a large scanned document goes through a vision model page by page.

### `GET /api/v1/usage`

No parameters. Returns the current billing cycle, which is **anniversary-based**: it is anchored on
the day your key was created, so `reset_date` is the next occurrence of that day, not the first of
the month.

```json
{
  "plan": "free",
  "docs_used": 37,
  "docs_limit": 100,
  "docs_remaining": 63,
  "reset_date": "2026-09-14",
  "period": "2026-08"
}
```

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
| `MISSING_API_KEY`      | 401  | No `X-API-Key` header.                                            |
| `INVALID_API_KEY`      | 403  | Unknown or deactivated key.                                       |
| `MISSING_FILE`         | 400  | No `file` part, or an empty one.                                  |
| `INVALID_FILE_FORMAT`  | 415  | Content type is neither `application/pdf` nor `application/octet-stream`. |
| `INVALID_FILE_FORMAT`  | 400  | Content type was right, but the bytes do not start with the `%PDF` signature. |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | The request itself is not `multipart/form-data`.                 |
| `FILE_TOO_LARGE`       | 413  | Over the 50 MB limit.                                             |
| `PDF_UNREADABLE`       | 400  | Corrupted, or encrypted with a password.                          |
| `DOCUMENT_TOO_LONG`    | 422  | More pages than your plan allows.                                 |
| `RATE_LIMIT_EXCEEDED`  | 429  | Too many requests per minute. Wait `Retry-After` seconds.         |
| `QUOTA_EXCEEDED`       | 429  | Monthly document quota reached. `message` carries the reset date. |
| `NOT_FOUND`            | 404  | No such endpoint.                                                 |
| `INTERNAL_ERROR`       | 500  | Unexpected server-side failure. Safe to retry.                    |
| `DATABASE_UNAVAILABLE` | 503  | The service could not verify your key. Retry shortly.             |

Both 429s share a status code but not a remedy: `RATE_LIMIT_EXCEEDED` clears within the minute,
`QUOTA_EXCEEDED` needs the cycle to reset or a plan upgrade.

---

## Rate Limits & Quotas

| Plan      | Requests / min | Documents / month | Pages / document |
|-----------|----------------|-------------------|------------------|
| `FREE`    | 10             | 100               | 100              |
| `STARTER` | 30             | 1 000             | 300              |
| `PRO`     | 100            | 5 000             | 500              |
| `SCALE`   | 300            | 20 000            | 1 000            |

The 50 MB file cap applies on every plan.

**Every response** carries the rate-limit state, so you can pace yourself without waiting for a 429:

| Header                  | Meaning                                                   |
|-------------------------|-----------------------------------------------------------|
| `X-RateLimit-Limit`     | Requests per minute allowed on your plan.                 |
| `X-RateLimit-Remaining` | Requests left in the current window.                      |
| `X-RateLimit-Reset`     | Unix timestamp (seconds) at which the bucket refills.     |
| `Retry-After`           | **On a 429 only** — seconds to wait before retrying.      |

Quota consumption counts **successful parses only**: a rejected upload does not cost a document.
Read your remaining balance at any time with `GET /api/v1/usage`.

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

# Optional
SERVER_PORT=8080
GOOGLE_API_KEY=          # vision fallback for scanned pages; without it those pages are flagged for manual review
```

Then:

```bash
./mvnw spring-boot:run
```

Flyway creates the schema on startup and seeds a development key: **`test-key-dev-123`**. That is
enough for a first call:

```bash
curl -X POST http://localhost:8080/api/v1/parse \
  -H "X-API-Key: test-key-dev-123" \
  -F "file=@document.pdf"
```

The seeded key is on the `FREE` plan, with the limits listed above.

### Build and test

```bash
./mvnw clean package   # build + run tests
./mvnw test            # tests only
```

### Regenerating the OpenAPI spec

[`docs/openapi.json`](docs/openapi.json) is a committed snapshot, generated from the code by
springdoc. Springdoc is **disabled by default** — the spec is served only under the `docs` profile,
so no documentation endpoint is exposed in production:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=docs

# in another shell — the API key is required, no path is exempt from authentication
curl -sS -H "X-API-Key: test-key-dev-123" http://localhost:8080/v3/api-docs   | python3 -m json.tool > docs/openapi.json
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
