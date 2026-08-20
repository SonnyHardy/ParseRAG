# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

ParseRAG is a Spring Boot REST API that turns a PDF into RAG-ready chunks. A single endpoint
(`POST /api/v1/parse`, multipart) runs an uploaded PDF through an extraction → cleaning → chunking
pipeline and returns structured chunks as JSON. It is distributed through the **RapidAPI
marketplace**, which authenticates the consumer, enforces the quota of their plan and bills it
(issue #54); the internal `X-API-Key` remains for administration and local runs.

Code comments and Javadoc are written in French. Match that convention when editing existing files.

## Build & run

Maven wrapper is committed; use it.

```bash
./mvnw clean package          # build + run tests
./mvnw spring-boot:run        # run locally (needs Postgres + .env, see below)
./mvnw test                   # all tests
./mvnw test -Dtest=ParseRagApplicationTests#contextLoads   # single test method
```

Deployment artefacts live at the root: `Dockerfile` (multi-stage, non-root, `fontconfig` installed
because PDFBox renders pages), `.dockerignore`, `railway.json` and `.github/workflows/build.yml`
(issue #58). CI runs the full suite against a real Postgres service — `contextLoads` needs one — and
builds the image, so breaking either fails the PR.

Java 25 toolchain, Spring Boot 4.0.6. Lombok is an annotation processor (configured explicitly in
`pom.xml`) — `@Data`/`@Slf4j`/`@RequiredArgsConstructor` are pervasive.

### Required environment

`application.yaml` imports an optional `.env` and reads these vars (no safe defaults — the app won't
start a DB connection without them): `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`,
`POSTGRES_PASSWORD`. Optional: `SERVER_PORT` (default 8080), `GOOGLE_API_KEY` (vision fallback,
empty in dev), `OPENAI_API_KEY` (only for the legacy vision provider, empty in dev).

`RAPIDAPI_PROXY_SECRET` (and optionally `RAPIDAPI_PROXY_SECRET_PREVIOUS`, for rotation) enables the
marketplace integration. Empty in dev: the integration is then inactive and only the internal key
authenticates. **In production it is mandatory** — without it the origin serves anyone who finds its
URL, marketplace bypassed.

`GOOGLE_API_KEY` is the name the Google Gen AI SDK reads by default; `GEMINI_API_KEY` is its *legacy*
alias and is not used here. With no key, `VisionFallback.isAvailable()` is false, no SDK client is
ever built, and scanned pages degrade to `manual_review_needed` — the app runs fine without one.

Telemetry (issue #38) is off unless `OTEL_ENABLED=true`; when on it also needs
`OTEL_EXPORTER_OTLP_ENDPOINT` (base URL, no `/v1/...` suffix) and `OTEL_EXPORTER_OTLP_AUTH` (the full
`Authorization` header value — Grafana Cloud: `Basic <base64 of instanceID:token>`). `DEPLOY_ENV`
(default `dev`) tags the exported data.

Postgres must be reachable. Flyway runs migrations from `src/main/resources/db/migration` on
startup (`baseline-on-migrate: true`, `ddl-auto: none` — schema is owned by Flyway, never Hibernate).
**No key is seeded any more** (issue #56). V1 did seed one, and this file used to claim that V3
corrected its hash and granted it admin — **it never did**: V3 only adds the column with
`DEFAULT false`. The development database had been fixed by hand, so a fresh production database
would have carried a live key whose secret was literally `123`, its hash readable in this repo. That
gap between the documentation and the migrations is exactly how the defect survived; V4 deletes the
row (V1 is left untouched — editing an applied migration breaks Flyway checksum validation).

The admin key now comes from `ADMIN_KEY_HASH` at startup (`AdminKeyBootstrap`): the environment
provides the **SHA-256**, never the key. Empty means no admin key is created and `/api/v1/health`
stays unreachable — a configuration gap must cost a diagnostic, not mint a credential. The bootstrap
is idempotent, restores a key that lost its `admin`/`active` flag, and treats a concurrent insert as
someone else's success.

## Request flow

`ParseController` → `ParsePipelineService.process(file, apiKey)` is the spine. The pipeline:

1. **Validate** — magic bytes (`%PDF`), content-type, 50 MB cap (`ParsePipelineService`).
2. **Extract** — `PdfTextExtractorService.extract(bytes, plan)` parses with PDFBox once per page and
   re-assembles text in memory, **band by band** (see below). Page count is capped per plan
   (`AppProperties.PageLimits.forPlan`).
3. **Clean** — `HeaderFooterCleaningService.clean(bytes, doc)` strips repeating headers/footers.
4. **Chunk** — `ChunkingService.chunk(doc)` produces `List<Chunk>`.
5. Return `ParseResponse.ok(...)`.

The `plan` request attribute is set by whichever filter authenticated the call — `RapidApiProxyFilter`
from the marketplace header, `ApiKeyFilter` from the key's row — and read in the controller. The
pipeline takes a `Plan`, not an `ApiKey`: it has no business knowing where the call came from.

### Plan-driven behavior

`Plan` (FREE/STARTER/PRO/SCALE) is the central knob, now fed by `X-RapidAPI-Subscription` through
`AppProperties.RapidApi.planFor` (mapping in `application.yaml`, unknown value → `FREE`). It selects
the **page limits**, defined in `AppProperties` and sourced from the `parserag.*` block. When adding
a plan-sensitive limit, add it to `AppProperties` with a `forPlan`-style switch rather than branching
on the enum at call sites.

Requests per minute and documents per month are **no longer ours**: the marketplace enforces and
bills them. What stays here is what RapidAPI cannot see — it counts requests, not the pages inside a
PDF, so `PageLimits` is the only guard against a 1 000-page document billed as a single call.

### Security / filters

`SecurityConfig` is stateless (no sessions, CSRF disabled, `permitAll`) — real auth is the filter
chain, inserted via `addFilterBefore`/`addFilterAfter`. Note the `FilterRegistrationBean` with
`setEnabled(false)` on each: it deliberately disables Spring Boot's automatic servlet registration so
a filter does not run twice. Any new `OncePerRequestFilter` that should live only in the security
chain needs the same treatment.

Two authentication paths, one per request, in this order:

1. **`RapidApiProxyFilter`** (public traffic, issue #54) — verifies `X-RapidAPI-Proxy-Secret` in
   constant time (`MessageDigest.isEqual`; a naive compare leaks the correct prefix through response
   time) against the current *or* previous secret, the second slot making rotation possible without
   an outage. It then maps `X-RapidAPI-Subscription` to a `Plan`.
   **Secret first, plan second** is an invariant, not a style: the plan header is free text that a
   direct caller can forge, so it is read only after the secret checks out, and an unknown plan falls
   back to the most restrictive one. A *wrong* secret is rejected outright rather than falling
   through to the internal key — a bad secret is not a legitimate caller, and chaining would hand a
   second chance to whoever is probing. A *missing* secret header falls through, which is the dev,
   admin and pre-listing path.
2. **`ApiKeyFilter`** (internal path) — SHA-256 of the key, looked up in Postgres. It short-circuits
   when the proxy already set `plan`, so the marketplace path costs **no database read at all**.
   **This path closes itself** (issue #56): as soon as a proxy secret is configured — i.e. the
   marketplace is serving traffic — only an `admin` key is accepted here, everything else gets
   `403 MARKETPLACE_REQUIRED`. Without that lock, any internal key served the origin directly and
   bypassed quota and billing entirely. No flag to flip: the rule follows the configuration, so local
   development (empty secret) is unaffected.
   Because that lookup is a DB read, the filter catches `DataAccessException` **and**
   `TransactionException` around it and writes a `503 DATABASE_UNAVAILABLE` itself: Spring Data's
   repository is `@Transactional`, so a down Postgres usually surfaces as
   `CannotCreateTransactionException`, which is *not* a `DataAccessException`. Without that catch the
   exception escapes the DispatcherServlet and the client gets Spring Boot's default `/error` 500 —
   including on `/api/v1/health`, whose own `503` is never reached since the controller never runs.

`ParseConcurrencyLimiter` bounds **simultaneous** parses (`parserag.parse.max-concurrent`, issue
 #56) and refuses beyond it with `503 SERVICE_BUSY` after a short wait. Rate limiting counts requests
per minute; what saturates the origin is requests *in flight*: a parse holds 50 MB in memory, reopens
the PDF six times and renders pages at ~9 MB each. With Tomcat's default 200 threads, nothing stopped
200 concurrent parses from exhausting the heap — `server.tomcat.threads.max` is now aligned on that
bound, and `server.shutdown: graceful` keeps a deploy from killing parses in flight. The defaults are
**provisional**: they must be recalibrated against the memory of the target container.

`RateLimitFilter` runs last, **per consumer and per plan**: the bucket is keyed on `X-RapidAPI-User`
(or the key id on the internal path) and its capacity comes from `parserag.rate-limit.*`. It rebuilds
the bucket when the plan changes, so an upgrade takes effect without a restart. A *global* bucket was
tried first and rejected: at 600 req/min shared, 300 clients doing 10 req/min each — all within their
plan — would collect a 429.
Keep these tiers **at or above** the listing's own throttle: set lower, ours bites first and the
customer is refused a rate they paid RapidAPI for.
Note what this guard does **not** cover: an attacker holding the proxy secret can vary
`X-RapidAPI-User` and mint a fresh bucket per request. The rampart there is the secret itself. And on
`/parse` the scarce resource is concurrency, not request rate — bounding it is a separate chantier.
The filter no longer sets `X-RateLimit-*` on passing responses either — the proxy publishes its own,
and a second set carrying an infrastructure ceiling would only mislead the client.

Filters, plus `GlobalExceptionHandler`, render errors in one shape:
`{"error": "<CODE>", "message": "…", "status": <int>}`. A filter runs outside the DispatcherServlet,
so it writes that JSON itself rather than throwing.

Request attributes are constants in `RequestAttributes` — they are a contract between a filter that
writes and a controller that reads, and a typo would surface only as a silent `null`.

### Admin-only endpoints

`ApiKey.admin` (boolean, default `false`) gates endpoints reserved for the developer. Today only
`GET /api/v1/health` uses it: `HealthController` reads the `apiKey` attribute and throws
`ParseRagException(NOT_FOUND, "NOT_FOUND", "Endpoint not found")` when the key isn't admin — a
deliberate `404`, not the `403` issue #37 asked for: a `403` would confirm to any legitimate
key-holder that an admin endpoint exists there. For non-admins the endpoint simply doesn't exist.
The check sits in the controller, not a filter — one endpoint doesn't justify a fourth filter, and
going through `ParseRagException` reuses the standard error rendering.

`HealthService` probes only what the app **cannot** guarantee about itself; a self-check run by the
app is a tautology (if it answers, it's alive), which is why there is no "application" component and
why heap/memory is deliberately absent — memory is a sawtooth metric, not a binary state, and a
threshold on it would report `DOWN` on a healthy JVM while missing the real OOM. Three components,
all reported flat as `UP`/`DOWN`:

- `db` — `SELECT 1`.
- `flyway` — `flyway.info()`; `DOWN` on any pending or failed migration, i.e. "is this jar running
  on the schema it expects?", a question `SELECT 1` doesn't ask. Skipped (reported `DOWN` outright)
  when `db` is already `DOWN`, since it needs the same connection.
- `disk` — free space on `parserag.health.disk-path` against `parserag.health.min-free-disk-mb`.
  A real binary state: uploads reach 50 MB and PDFBox spills to temp files.

`status` is the conjunction: `200` when all three are `UP`, `503` otherwise (same body, the faulty
component carrying `DOWN`). Both DB-backed probes run on a single daemon thread bounded by
`parserag.health.db-ping-timeout-seconds`, so a hung Postgres can't make the check wait out Hikari's
`connection-timeout` (10 s, sized for parsing rather than for a health probe). The `Flyway` bean is injected through `ObjectProvider` — it's absent when
`spring.flyway.enabled=false`, in which case `flyway` reports `UP` (nothing to verify).

## Vision fallback (package `service.fallback`)

Two consumers reach for a vision model when PDFBox/Tabula can't do the job: borderless tables whose
Tabula grid scores poorly (`TableExtractorService`, issue #9) and scanned/image-only pages
(`ScannedDocumentFallbackService`, issue #11). Both depend on the **port** `VisionFallback`
(`isAvailable` / `extractTable` / `extractPage`), never on a concrete provider.

Its contract is **graceful degradation, no exceptions**: vision disabled, key missing, call failed or
response unusable all return `null`, and the caller turns that into `manual_review_needed`. Every
implementation must honour it — the pipeline has no other safety net.

Two implementations, selected by `parserag.vision.provider` via `@ConditionalOnProperty` (exactly one
bean at startup, so a typo surfaces as `NoSuchBeanDefinitionException` rather than silent fallthrough):

- **`GeminiVisionFallbackService`** (`gemini`, the only implementation since issue #58 removed the
  OpenAI provider and its SDK — 144 MB of jar became 100 MB) — Gemini 3.5 Flash-Lite via the official
  `com.google.genai:google-genai` SDK. Passes a `responseSchema` (structured outputs) so the JSON
  shape is enforced by the API, and sets `thinkingLevel = minimal` (transcription, not reasoning).
  Mind the generation gap: 3.x wants `thinkingLevel` and rejects the `thinkingBudget` of the 2.5
  era with a `400 INVALID_ARGUMENT` — measured, both ways round, in issue #48.

  **Do not retry `gemini-2.5-flash-lite`** for the sake of its lower price (issue #48, 20/08/2026):
  `generateContent` answers `404 — no longer available to new users`. The whole 2.5 line is closed
  to accounts that were not already using it. Note the trap that cost a false start: the metadata
  endpoint `GET /v1beta/models/gemini-2.5-flash-lite` answers **200** and the model is listed in the
  catalogue — only a real generation call reveals the refusal. Probe model access with
  `generateContent`, never with a metadata read.

Why the switch: Phase 4 of #11 showed the binding constraint was the **account-wide OpenAI RPM/TPM
ceiling**, not our code — a 25-page scan got 9 then 12 pages through, with a *different* set of pages
each run, while the `VisionBudget` cap (20/doc) never even engaged. See
`src/test/resources/sample-pdfs/results/PHASE4-scanned-vision-2026-06-26.md`.

Shared, provider-independent pieces: `VisionResponseParser` (JSON → domain: tolerates Markdown fences,
rectangularizes ragged rows **by padding only** — never truncates — and rejects grids under 2 columns)
and `VisionPrompts` (the prompts themselves, so tuning one doesn't silently apply to a single provider
and skew the comparison). `VisionBudget` caps vision pages per *document* and is shared by both
consumers — it is orthogonal to the provider.

**Adding a provider**: implement `VisionFallback`, reuse `VisionResponseParser` and `VisionPrompts`,
annotate with `@ConditionalOnProperty(... havingValue = "<name>")`. Do not mock the vendor SDK client
in tests — both SDKs expose `final` classes (and `com.google.genai.Client.models` is a public field,
so a Mockito mock leaves it null); inject a small functional seam instead, as `GeminiCall` does.

## Column detection: bands, not a page-wide gutter (issue #31)

`PageGeometryAnalyzer` is the single source of truth for page geometry, shared by text extraction
and table-region detection so both see the same columns.

A page is **not** "N columns" — it is a stack of zones: full-width title banner, then two columns,
then a full-width figure caption, then two columns again. `columnBands` returns one `Band` per zone,
each with its own `splits`; `PdfTextExtractorService` assembles band by band, top to bottom.
`columnSplits` is a convenience over it, returning the splits of the **dominant band** (the one
carrying the most fragments) — that is what `TableRegionDetector` consumes.

The algorithm runs two passes over a **fragment-level** X histogram (never over lines grouped by Y:
grouping by Y merges both columns into one full-width line, filling the very gutter being searched):

1. a tolerant pass over the page locates *candidate* gutters;
2. fragments crossing a candidate become band separators, and each band is then analysed on its own.

Three guards, each earned from a measured failure — do not remove one without re-running
`ColumnDetectionBenchmark`:

- **tolerance is never zero.** Real gutters are not perfectly empty (descenders, rules, figure
  bleed): resnet p1 has no 8 pt run of strictly-empty bins although its gutter plainly exists.
- **text required on both sides** of a split. Without it, a page margin or a bullet indent reads as
  a gutter — that is how a 1-column paper was being reported as 3 columns before #31.
- **minimum column width.** A wide gutter crossed by a thin residue otherwise yields two splits
  framing a 13 pt-wide "column".

Why it matters: before #31 a *single* element crossing the gutter — title, author line, arXiv stamp,
figure caption — made the whole page fall back to a global `(Y, X)` sort, **interleaving the two
columns** line by line. Measured across the corpus, the fix took manual-review chunks from 87 to 54
(BERT 17 → 0, EnnsDoc 4 → 0, resnet 14 → 0) while leaving 1-column documents byte-identical.

The thresholds were calibrated *on the corpus*, not chosen a priori — resnet p5 stayed interleaved
by a single fragment until the candidate tolerance went from 10 % to 20 %. Re-run
`ColumnDetectionBenchmark` (`@Disabled`, sweeps all 542 pages) after touching any of them.

## The reading-order oracle (issue #30, palier 3)

`PdfTextExtractorService.computeSuspectLines` is the *verifier*: given the line-start X sequence of
a mono-column assembly, it reports the lines that betray interleaving. It runs **only** on pages
assembled mono — a page assembled column by column is correct by construction.

It looks for the **signature** of interleaving: a repeated alternation between two *stable*,
well-separated X anchors. Not merely a backward jump — the first version used that, and it was right
one time in five (213 of 541 corpus pages flagged wrongly), because code listings, bullet lists,
centred equations and tables all jump backwards without any interleaving. Four conditions, all
required: two anchors ≥ 15 % of page width apart, each carrying ≥ 20 % of the lines, ≥ 4 alternations
between them, and only then the lines returning to the left anchor are flagged. Anchor grouping is
deliberately loose (20 pt) — at 5 pt a single column edge split into three anchors, each falling
below the share threshold.

Measured on the corpus against the band detector as ground truth: **precision 18 % → 97 %**, recall
70 %, one false positive left. Manual-review chunks fell from 54 to 8.

Recall matters less than precision *today* — genuinely multi-column pages are assembled by columns
and never reach the oracle, so its only job is to stay quiet on healthy mono pages. That balance
would shift if the oracle ever drives a retry loop, where a false positive costs only ~0.2 ms of
recomputation; the thresholds should be revisited then, with `ColumnDetectionBenchmark` re-run.

## The verification loop (issue #31)

Geometry proposes, reading order disposes. `extractPageText` assembles the page from the detected
bands, then runs the oracle on the result; if the text carries the interleaving signature, the
geometry was wrong and the page is **re-assembled** with alternative splits, keeping whichever
attempt the oracle scores best.

This is what makes extraction robust to layouts never seen before: a threshold is calibrated on a
corpus and bets the next document resembles it, whereas a verification checks the actual output.

- **The oracle localises, not just detects.** `analyseReadingOrder` returns the midpoint between the
  two anchors it found, so the first retry is a split derived from the interleaving itself; further
  attempts come from `PageGeometryAnalyzer.candidateGutters` (gutters the guards rejected).
- **Bounded** to `MAX_REASSEMBLY_ATTEMPTS` (3). Never "until it is clean", which would not terminate
  on a pathological page. If no attempt is clean, the least-bad one is kept **and its suspect lines
  survive** — the failure stays visible downstream (lowered confidence, `manual_review`) instead of
  being silently shipped.
- **Cost is negligible**: the PDF is not re-read. Only the histogram and the sort are replayed on
  fragments already in memory — ~0.2 ms per attempt against ~10 ms to parse the page, and only on
  pages that fail.

Demonstrated by deliberately degrading `CANDIDATE_TOLERANCE_RATIO` to 10 %, the setting that leaves
resnet p5 and p11 interleaved: with the loop, both come out clean and corpus manual-review chunks
stay at 8. The geometry can be wrong and the output is still right.

**Two signatures, both handled.** Stitched columns leave a different trace depending on whether
their baselines coincide:

- *baselines differ* → line starts **alternate** between two anchors (`analyseAlternatingColumns`);
- *baselines coincide* → the columns merge **inside** one line, separated by a wide internal blank
  (`analyseMergedColumns`) — the `customiza-␣␣␣␣lack the necessary` symptom quoted by the issue.

Only one shows at a time, so the analysis cascades. The second is detected geometrically, not from
the text: `assembleAsSingleColumn` records each line's widest internal gap, and a gap recurring at
the *same* abscissa across ≥ 50 % of lines is a gutter.

Tables produce aligned internal blanks too, and splitting one would destroy it. What protects them
is that 50 % share requirement alone — a gutter runs the whole body, a table spans a few lines. There
is **no** post-hoc guard on the re-assembled text: that would need a reliable tabular-structure
detector, and `looksLikeTable` is still a stub returning `false` (issue #9). Protection is therefore
empirical, verified on the `table1` and `tables_examples` fixtures (outputs unchanged), not
structural — worth re-checking whenever these thresholds move.

Corpus after both signatures: **0 pages with suspect lines**, manual-review chunks at 8 (all from
palier 1), table fixtures untouched.

## Table quality gate (issues #9, #26)

`TableExtractorService` extracts each detected region with Tabula, then decides whether to spend a
**vision call** on it — the call costs ~2.9 s, so the gate arbitrates between two opposite errors:
firing for nothing, and staying silent on a broken grid.

Two signals route a grid to vision: `semanticQuality` below `parserag.vision.quality-threshold`
(brevity of cells × absence of singleton rows), or `hasStructuralDefect`.

Structural defects, each earned from a measured failure — `TableQualityBenchmark` sweeps the corpus
and classifies every call as **INUTILE** (vision output identical to Tabula's) or **REPARATION**:

- **undecoded glyph** anywhere;
- **header that is really a data row** — two forms: first cell blank with mostly-numeric headers, or
  first cell a label followed by *decimals*. The decimal test is what separates `ESIM+GloVe | 51.9 |
  52.7` (a measurement row promoted to header) from `Year | 2018 | 2019` (a legitimate label). An
  earlier attempt used "contains a digit" and broke both guard tests in `TableExtractorServiceTest`;
- **hollow column** outside the first;
- **residual stacked cell** — see below.

### Stacked cells

Tabula joins a multi-line cell's lines with a carriage return, cramming several values into one
cell: a 4×3 table comes out 2×3, useless for RAG, and *invisible* to the brevity score because each
stacked piece is short. `buildFromGrid` splits such a row when the cut is **determined** — every
non-empty cell carries the same number of parts. When the counts diverge, the row mixes merged and
simple cells: guessing a distribution would be worse than the defect, so the row is left intact and
flagged, and the vision fallback decides on the image.

Measured on the corpus: stacked tables escaping the gate **4 → 0**, repairs **11 → 14**, needless
calls **2 → 1**, with 3 tables split at no API cost.

### Why the threshold stays at 0.75

Lowering it to ~0.72 would save the one remaining needless call, but the margin to the highest
*repair* (0.70) is **0.04 over 5 data points**. Trading a certain small latency gain for an uncertain
correctness loss is the wrong side of the "accuracy first" call this gate is calibrated on. Re-run
`TableQualityBenchmark` before revisiting — and note its verdicts depend on a non-deterministic model
output, so a borderline INUTILE may read REPARATION on another run.

## Key architectural detail: header/footer cleaning

Lives entirely in the package `com.sonny.parserag.service.headerfooter`, designed as **stacked
layers**. Entry point: `HeaderFooterCleaningService` (the orchestrator called by the pipeline).

Flow: `BlockExtractor` parses the PDF once into one `TextBlock` per *visual line*, **column-aware**
(same histogram/gutter logic as `PdfTextExtractorService`, so a block's text matches a real
`rawText` line — essential for multi-column stripping). The orchestrator then runs every
`HeaderFooterDetector` bean **in `@Order`**, unioning their confirmations and passing the running set
to later layers, then `LineStripper` removes the confirmed lines (whitespace-insensitive).

Layers shipped (each its own file):
1. `GeometryRecurrenceDetector` (`@Order(1)`, **primary**) — page-association: a block is confirmed if
   it sits in the header/footer margin zone **and** its digit-masked text recurs across pages. Has
   alternating-header support (even/odd parity on short docs) and a TOC/index guard. This is what
   catches body-like footers that DBSCAN absorbs (e.g. NIST's long DOI footer).
2. `BoilerplateDetector` (`@Order(2)`) — content regex for **single-page** boilerplate that recurrence
   can't see (ACM permissions/ISBN/copyright, DOI lines, arXiv stamps, page-number patterns),
   zone-gated.
3. `ClusterDetector` (`@Order(3)`, **secondary net**) — DBSCAN (`commons-math4-legacy`) over
   not-yet-confirmed edge-zone blocks; confirms non-body clusters spanning ≥2 pages. Conservative.

**To add a layer**: drop a new `@Component implements HeaderFooterDetector` with an `@Order` into the
package — nothing else to wire. Thresholds live under `parserag.header-footer-cleaning` in
`application.yaml` (`header-zone-ratio`, `footer-zone-ratio`, `min-recurrent-pages`, `dbscan-*`,
`block-vertical-gap-pt`). The legacy `*.processing.HeaderFooter*CleanerService` classes were removed.

Known open gaps (by design, separate chantiers): interleaved margin line-numbers (an *extraction*
issue in `PdfTextExtractorService`, not header/footer), and over-deletion risk on dense TOC/index
pages (mitigated by the index guard, may need tuning).

## API documentation (issue #16)

Three artefacts, all public-facing and all in English (the code's comments stay French):
`README.md`, `docs/examples/` (cURL, Java/OkHttp, Python) and `docs/openapi.json`.

`docs/openapi.json` is a **committed snapshot generated from the code** by springdoc, not a
hand-written file — regenerate it whenever an endpoint, a response model or an error code changes:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=docs
curl -sS -H "X-API-Key: $KEY" http://localhost:8080/v3/api-docs   | python3 -m json.tool > docs/openapi.json
```

springdoc is **off by default** (`springdoc.api-docs.enabled: false`); the `docs` profile
(`application-docs.yaml`) is the only thing that turns it on, along with `OpenApiConfig` — which
carries the same `@ConditionalOnProperty`. Two reasons not to expose it in production: `ApiKeyFilter`
exempts no path (issue #37), so `/v3/api-docs` would answer 401 without a key anyway; and the spec is
consumed from RapidAPI, not from a Swagger UI we host. Note the export therefore *requires* a
reachable Postgres — the filter's key lookup is a DB read.

`HealthController` is `@Hidden`: publishing an admin endpoint in the spec would announce the very
existence the 404 of issue #37 is designed to hide.

Two traps met while wiring it, both already fixed but worth knowing:

- **The swagger-annotations arbitration is gone** — and the reason it existed is worth keeping in
  mind. `openai-java` pulled `swagger-annotations` 2.2.31 while springdoc pulls
  `swagger-annotations-jakarta` 2.2.47: *the same package* `io.swagger.v3.oas.annotations` from two
  artefacts, the low version winning on the classpath and generation dying on
  `NoSuchMethodError: Schema.$dynamicRef()`. Removing the OpenAI SDK (issue #58) removed the
  conflict, so the `dependencyManagement` pin went with it. Any future SDK may bring it back.
- **Never put `@Schema` on the `MultipartFile` parameter.** It replaces the schema of the whole
  request *body*: the spec then describes a raw binary instead of a form carrying a `file` part, and
  a generated client posts the wrong thing.

## Conventions & current state

- **Domain models are Java records** under `model/domain` (`ExtractedDocument`, `ExtractedPage`,
  `Chunk`, `ChunkType`, `TableResult`). Cleaning produces a *new* `ExtractedDocument`/`ExtractedPage`
  rather than mutating — keep that immutability.
- **Errors** go through `ParseRagException(HttpStatus, code, message)` and are rendered by
  `GlobalExceptionHandler`. Throw it with a stable string error code (e.g. `FILE_TOO_LARGE`,
  `DOCUMENT_TOO_LONG`, `PDF_UNREADABLE`) rather than returning ad-hoc responses.
- **Tuning constants**: prefer `application.yaml` → `AppProperties` for anything operators might tune;
  algorithm-internal geometry (gutter widths, histogram resolution) lives as `private static final`
  in the relevant service.
- The codebase is built sprint by sprint. Several features are stubbed or disabled on purpose:
  `looksLikeTable` always returns `false` (table extraction deferred to issue #9). `TableDetectorService`
  exists for an upcoming sprint — don't assume it's live.
- **The monthly quota is not ours any more** (issue #54). `QuotaEnforcementFilter`,
  `UsageTrackingService`, `UsageRecord` and `GET /api/v1/usage` were removed: RapidAPI meters and
  bills. The `usage_records` table is deliberately still there — dropping it is a second migration,
  once the listing has run in production, since a Flyway migration cannot be replayed. Issue #53,
  which specified a home-grown subscription cycle, was closed as delegated.
- **Tests are plain unit tests** (JUnit 5 + Mockito + `spring-test` mocks), no Spring context —
  collaborators are mocked and web plumbing uses `MockHttpServletRequest`/`MockFilterChain`. Keep new
  tests in that style. The one exception is `ParseRagApplicationTests.contextLoads`: `@SpringBootTest`
  needs a working datasource, so it requires the env vars above.
