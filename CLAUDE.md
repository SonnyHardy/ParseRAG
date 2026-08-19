# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

ParseRAG is a Spring Boot REST API that turns a PDF into RAG-ready chunks. A single endpoint
(`POST /api/v1/parse`, multipart) runs an uploaded PDF through an extraction → cleaning → chunking
pipeline and returns structured chunks as JSON. Authentication is by `X-API-Key` header; usage is
metered and rate-limited per plan tier.

Code comments and Javadoc are written in French. Match that convention when editing existing files.

## Build & run

Maven wrapper is committed; use it.

```bash
./mvnw clean package          # build + run tests
./mvnw spring-boot:run        # run locally (needs Postgres + .env, see below)
./mvnw test                   # all tests
./mvnw test -Dtest=ParseRagApplicationTests#contextLoads   # single test method
```

Java 25 toolchain, Spring Boot 4.0.6. Lombok is an annotation processor (configured explicitly in
`pom.xml`) — `@Data`/`@Slf4j`/`@RequiredArgsConstructor` are pervasive.

### Required environment

`application.yaml` imports an optional `.env` and reads these vars (no safe defaults — the app won't
start a DB connection without them): `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`,
`POSTGRES_PASSWORD`. Optional: `SERVER_PORT` (default 8080), `GOOGLE_API_KEY` (vision fallback,
empty in dev), `OPENAI_API_KEY` (only for the legacy vision provider, empty in dev).

`GOOGLE_API_KEY` is the name the Google Gen AI SDK reads by default; `GEMINI_API_KEY` is its *legacy*
alias and is not used here. With no key, `VisionFallback.isAvailable()` is false, no SDK client is
ever built, and scanned pages degrade to `manual_review_needed` — the app runs fine without one.

Telemetry (issue #38) is off unless `OTEL_ENABLED=true`; when on it also needs
`OTEL_EXPORTER_OTLP_ENDPOINT` (base URL, no `/v1/...` suffix) and `OTEL_EXPORTER_OTLP_AUTH` (the full
`Authorization` header value — Grafana Cloud: `Basic <base64 of instanceID:token>`). `DEPLOY_ENV`
(default `dev`) tags the exported data.

Postgres must be reachable. Flyway runs migrations from `src/main/resources/db/migration` on
startup (`baseline-on-migrate: true`, `ddl-auto: none` — schema is owned by Flyway, never Hibernate).
`V1__create_api_keys_table.sql` seeds a dev key: the raw key is `test-key-dev-123` (its SHA-256 is
what's stored). Pass it as `X-API-Key: test-key-dev-123`. That key is also the **admin** key
(`V3__add_admin_to_api_keys.sql`) — the only one allowed on `/api/v1/health`. Note V1's stored hash
was actually SHA-256 of `123`, not of the documented key; V3 corrects it in place (V1 itself is left
untouched — editing an applied migration breaks Flyway checksum validation).

## Request flow

`ParseController` → `ParsePipelineService.process(file, apiKey)` is the spine. The pipeline:

1. **Validate** — magic bytes (`%PDF`), content-type, 50 MB cap (`ParsePipelineService`).
2. **Extract** — `PdfTextExtractorService.extract(bytes, plan)` parses with PDFBox once per page and
   re-assembles text in memory, **band by band** (see below). Page count is capped per plan
   (`AppProperties.PageLimits.forPlan`).
3. **Clean** — `HeaderFooterCleaningService.clean(bytes, doc)` strips repeating headers/footers.
4. **Chunk** — `ChunkingService.chunk(doc)` produces `List<Chunk>`.
5. Return `ParseResponse.ok(...)`.

The `apiKey` request attribute is set by `ApiKeyFilter` and read in the controller; when null the
pipeline defaults to `Plan.FREE`.

### Plan-driven behavior

`Plan` (FREE/STARTER/PRO/SCALE) is the central knob. It selects page limits, rate limits, and
monthly quotas — all defined in `AppProperties` and sourced from the `parserag.*` block of
`application.yaml`. When adding a plan-sensitive limit, add it to `AppProperties` with a `forPlan`-style
switch rather than branching on the enum at call sites.

### Security / filters

`SecurityConfig` is stateless (no sessions, CSRF disabled, `permitAll`) — real auth is the
`ApiKeyFilter`, inserted into the Spring Security chain via `addFilterBefore`. Note the
`FilterRegistrationBean` with `setEnabled(false)`: it deliberately disables Spring Boot's automatic
servlet registration so the filter does not run twice. Any new `OncePerRequestFilter` that should live
only in the security chain needs the same treatment. `ApiKeyFilter` hashes the incoming key with
SHA-256 before lookup and filters **every** request — there is no exempt path, `/api/v1/health`
included (issue #37). Because that lookup is a DB read, the filter catches `DataAccessException`
**and** `TransactionException` around it and writes a `503 DATABASE_UNAVAILABLE` itself: Spring
Data's repository is `@Transactional`, so a down Postgres usually surfaces as
`CannotCreateTransactionException`, which is *not* a `DataAccessException`. Without that catch the
exception escapes the DispatcherServlet and the client gets Spring Boot's default `/error` 500 —
including on `/api/v1/health`, whose own `503` is never reached since the controller never runs.
`RateLimitFilter` (token bucket per key, issue #14) and `QuotaEnforcementFilter` (monthly quota,
only on `POST /api/v1/parse`, issue #13) run after it and both read the `apiKey` attribute.

All three filters, plus `GlobalExceptionHandler`, render errors in one shape:
`{"error": "<CODE>", "message": "…", "status": <int>}`. A filter runs outside the DispatcherServlet,
so it writes that JSON itself rather than throwing.

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

- **`GeminiVisionFallbackService`** (`gemini`, **default**) — Gemini 2.5 Flash-Lite via the official
  `com.google.genai:google-genai` SDK. Passes a `responseSchema` (structured outputs) so the JSON
  shape is enforced by the API, and sets `thinkingBudget = 0` (transcription, not reasoning).
- **`OpenAiVisionFallbackService`** (`openai`) — the historical GPT-4o mini path, kept only to compare
  extraction quality on the same corpus before being deleted (issue #28).

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
  `looksLikeTable` always returns `false` (table extraction deferred to issue #9), chunk confidence
  is hardcoded to `1.0` (issue #8). `TableDetectorService` and `ConfidenceCalculatorService` exist for
  upcoming sprints — don't assume they're live. The rate-limit and quota filters, by contrast, are no
  longer stubs (issues #14/#13), and neither is the vision fallback (see below).
- **Tests are plain unit tests** (JUnit 5 + Mockito + `spring-test` mocks), no Spring context —
  collaborators are mocked and web plumbing uses `MockHttpServletRequest`/`MockFilterChain`. Keep new
  tests in that style. The one exception is `ParseRagApplicationTests.contextLoads`: `@SpringBootTest`
  needs a working datasource, so it requires the env vars above.
