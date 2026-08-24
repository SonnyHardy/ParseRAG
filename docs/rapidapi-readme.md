# ParseRAG

**Turn any PDF into clean, structured JSON for your RAG pipeline.**

One endpoint, one upload. You get back embedding-ready chunks: text extracted in real reading order,
repeated headers and footers stripped, tables returned as structured JSON, and scanned pages handled
by a vision model. Every chunk carries a confidence score, so a bad page never slips into your index
unnoticed.

---

## Why not just extract the text?

A plain PDF-to-text call gives you a string. That string is often wrong in ways you will not notice
until your retrieval quality is already bad:

| What goes wrong | What it does to your index |
|---|---|
| Two-column papers are stitched line by line | Every chunk mixes two unrelated sentences. Embeddings become noise. |
| Running titles, DOI lines and page numbers repeat on every page | The same boilerplate dilutes every single embedding in the document. |
| Tables collapse into a wall of numbers | Row and column relationships are lost, so the table can never be answered from. |
| Scanned pages return an empty string | The document silently indexes as blank, and nobody finds out. |

ParseRAG handles all four, and tells you when it is unsure.

---

## Quick start

Subscribe to a plan, then send a PDF.

### cURL

```bash
curl -X POST "https://parserag.p.rapidapi.com/api/v1/parse" \
  -H "X-RapidAPI-Key: YOUR_RAPIDAPI_KEY" \
  -H "X-RapidAPI-Host: parserag.p.rapidapi.com" \
  -F "file=@document.pdf"
```

### Python

```python
import requests

url = "https://parserag.p.rapidapi.com/api/v1/parse"
headers = {
    "X-RapidAPI-Key": "YOUR_RAPIDAPI_KEY",
    "X-RapidAPI-Host": "parserag.p.rapidapi.com",
}

with open("document.pdf", "rb") as f:
    response = requests.post(
        url,
        headers=headers,
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
        .url("https://parserag.p.rapidapi.com/api/v1/parse")
        .addHeader("X-RapidAPI-Key", "YOUR_RAPIDAPI_KEY")
        .addHeader("X-RapidAPI-Host", "parserag.p.rapidapi.com")
        .post(body)
        .build();

try (Response response = client.newCall(request).execute()) {
    System.out.println(response.body().string());
}
```

### Node.js

```javascript
import fs from "node:fs";

const form = new FormData();
form.append("file", new Blob([fs.readFileSync("document.pdf")], { type: "application/pdf" }),
            "document.pdf");

const response = await fetch("https://parserag.p.rapidapi.com/api/v1/parse", {
  method: "POST",
  headers: {
    "X-RapidAPI-Key": "YOUR_RAPIDAPI_KEY",
    "X-RapidAPI-Host": "parserag.p.rapidapi.com",
  },
  body: form,
});

const { chunks } = await response.json();
console.log(chunks.length, "chunks");
```

---

## The endpoint

### `POST /api/v1/parse`

`multipart/form-data`, one part named `file`, holding a PDF. Counts as one request against your plan,
whatever the size of the document.

| Constraint | Value |
|---|---|
| Max file size | 50 MB, on every plan |
| Max pages per document | Plan-dependent, see [Plans](#plans-and-limits) |
| Content type | `application/pdf` or `application/octet-stream`. The PDF signature is verified regardless of the header. |

Parsing is **synchronous**: the response comes back when the document is done. Set a generous client
timeout. A large scanned document goes through a vision model page by page, and a 5 minute read
timeout is a sensible default.

---

## The response

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

### Top level

| Field | Type | Description |
|---|---|---|
| `document_id` | string | Identifier generated for this parse (`doc_` + 8 hex chars). Prefixes every chunk id. |
| `pages` | integer | Number of pages read. |
| `language` | string | ISO 639-1 code, taken from the PDF metadata when present, otherwise guessed from stop words. One of `fr`, `en`, `de`, `es`, `unknown`. |
| `processing_ms` | integer | Server-side processing time in milliseconds. |
| `chunks` | array | The chunks, sorted by page. |
| `status` | string | `"ok"` on a 200. Failures return an error object instead. |

### Chunk

| Field | Type | Description |
|---|---|---|
| `id` | string | `chunk_<document_id>_<nnn>`, sequential within the document. |
| `text` | string | The chunk text. On a `TABLE` chunk, the table linearised as pipe-separated rows, embeddable as-is. |
| `type` | enum | `PARAGRAPH`, `TABLE`, `FIGURE_CAPTION` or `HEADER_ARTIFACT`. |
| `page` | integer | 1-based page the chunk came from. |
| `char_start` | integer | Start offset in the page text. `0` on `TABLE` chunks. |
| `char_end` | integer | End offset in the page text. `0` on `TABLE` chunks. |
| `confidence` | number | Between 0 and 1. Lowered when the reading order looks interleaved or the text carries extraction artefacts. |
| `fallback_used` | boolean | `true` when a vision model produced this chunk: a scanned page, or a table grid the native extractor could not read. |
| `manual_review_needed` | boolean | `true` when confidence fell under the review threshold. Treat these as suspect rather than indexing them blindly. |
| `table_json` | object | **Only on `TABLE` chunks**, omitted otherwise. See below. |

### Chunk types

| Type | What it is |
|---|---|
| `PARAGRAPH` | Body text, the common case. |
| `TABLE` | A table: structured in `table_json`, linearised in `text`. |
| `FIGURE_CAPTION` | A figure caption or title. |
| `HEADER_ARTIFACT` | A repeated header or footer line that survived cleaning. Usually safe to drop. |

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

`caption` is present only when one was found next to the table. `headers` is an array of strings and
`rows` an array of equal-length row arrays, so you can rebuild the grid without parsing text.

---

## Using the output well

**Filter on confidence before you index.** This is the whole point of the score. The simplest useful
policy is one line:

```python
good = [c for c in chunks if not c["manual_review_needed"]]
```

Chunks flagged for review are not necessarily wrong, but they are the ones worth looking at before
they reach your vector store.

**Keep `page` and `document_id` in your metadata.** They are what lets you cite a source back to the
user, and what lets you re-parse a single document later without rebuilding the whole index.

**Handle `TABLE` chunks on purpose.** `text` is ready to embed as-is. `table_json` is there when you
want to render the table back to the user, or feed the grid to a model that answers numeric
questions. Embedding the linearised text and keeping the JSON alongside it works well.

**`HEADER_ARTIFACT` chunks are droppable.** They are the boilerplate that survived cleaning. Keep
them only if you have a reason to.

**Do not retry on `422`.** `DOCUMENT_TOO_LONG` means the document is longer than your plan allows, and
the same call will fail again. Split the PDF, or move up a plan.

---

## Errors

Every error comes back in the same shape:

```json
{
  "error": "FILE_TOO_LARGE",
  "message": "File size 63.2 MB exceeds the 50 MB limit.",
  "status": 413
}
```

Branch on `error`, which is stable across releases. `message` is written for humans and may change.

| Code | HTTP | When | Retry? |
|---|---|---|---|
| `MISSING_FILE` | 400 | No `file` part, or an empty one. | No, fix the request |
| `INVALID_FILE_FORMAT` | 415 | Content type is neither `application/pdf` nor `application/octet-stream`. | No |
| `INVALID_FILE_FORMAT` | 400 | Content type was right, but the bytes do not start with the `%PDF` signature. | No |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | The request itself is not `multipart/form-data`. | No |
| `FILE_TOO_LARGE` | 413 | Over the 50 MB limit. | No |
| `PDF_UNREADABLE` | 400 | Corrupted, or encrypted with a password. | No |
| `DOCUMENT_TOO_LONG` | 422 | More pages than your plan allows. | No, split or upgrade |
| `RATE_LIMIT_EXCEEDED` | 429 | You are sending too fast. Wait the number of seconds in `Retry-After`. | Yes, after the delay |
| `SERVICE_BUSY` | 503 | The service is at capacity. Parses are bounded to protect memory. | Yes, shortly |
| `DATABASE_UNAVAILABLE` | 503 | A dependency is temporarily down. | Yes, shortly |
| `INTERNAL_ERROR` | 500 | Unexpected server-side failure. | Yes |
| `NOT_FOUND` | 404 | No such endpoint. | No |

Quota rejections are not in this table. They are returned by the RapidAPI proxy before the request
reaches ParseRAG, in the marketplace's own format.

**A note on 429.** Back off for the number of seconds given in `Retry-After` rather than retrying
immediately. Parsing is expensive and a tight retry loop will simply collect more 429s.

---

## Plans and limits

Requests per month and requests per minute are set by your subscription and enforced by the
marketplace. Every response carries `x-ratelimit-requests-remaining` and
`x-ratelimit-requests-reset`.

ParseRAG enforces one limit of its own, because the marketplace counts requests and cannot see how
big a job is: **pages per document**.

| Plan | Requests / month | Requests / minute | Pages per document |
|---|---|---|---|
| **BASIC** | 50 | 2 | 100 |
| **PRO** | 1,000 | 5 | 300 |
| **ULTRA** | 7,500 | 10 | 500 |
| **MEGA** | 50,000 | 20 | 1,000 |

The 50 MB file cap applies on every plan. Over the page cap, the call is rejected with
`DOCUMENT_TOO_LONG` (422) rather than truncated: a silently half-parsed document is worse than a
clear refusal.

---

## Good to know

**The call always returns a result.** Vision work for scanned pages runs under a time budget. If the
vision provider slows down, the remaining pages come back flagged `manual_review_needed` instead of
the request hanging until a gateway timeout. A partial answer you can act on beats a 504 you cannot.

**Your documents are not stored.** They are processed in memory for the duration of the request, and
are not logged or used for training. Pages that need optical character recognition are sent to a
third-party vision model provider for transcription during that same request, and not retained
beyond it.

**Chunk sizes are tuned for embedding.** Chunks are cut into overlapping windows of roughly 2,000
characters, about 512 tokens for most models, with a 100 character overlap so a sentence split
across two chunks stays retrievable from either one.

---

## FAQ

**Does it work on scanned documents?**
Yes. Image-only pages skip native extraction and go to a vision model. Those chunks come back with
`fallback_used: true`. Quality depends on the scan; check `confidence` before indexing.

**What languages are supported?**
Text extraction is language-agnostic. The reported `language` field detects `fr`, `en`, `de` and
`es`, and returns `unknown` otherwise. An unknown language does not affect extraction.

**Can I get the raw page text instead of chunks?**
Not today. Chunks carry `page`, `char_start` and `char_end`, so page text can be reassembled from
them if you need it.

**Why is a chunk flagged when the text looks fine?**
The flag is deliberately cautious. It is raised on the signature of a layout problem, not on proof of
one. A false positive costs you a review; a false negative costs you a poisoned index.

**How long does a parse take?**
A native-text document of 200 pages parses in a few seconds. A scanned document is far slower,
because each page goes through a vision model. Budget accordingly and keep your client timeout high.

**Is there a batch endpoint?**
Not today. Send documents one at a time, and stay inside your plan's requests-per-minute limit.

---

## Support

Questions, bug reports and feature requests go to the **Discussions** tab of this listing.

When reporting a parsing problem, include the `document_id` from the response. It identifies the
parse and makes the issue reproducible.
