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
`POSTGRES_PASSWORD`. Optional: `SERVER_PORT` (default 8080), `OPENAI_API_KEY` (empty in dev).

Postgres must be reachable. Flyway runs migrations from `src/main/resources/db/migration` on
startup (`baseline-on-migrate: true`, `ddl-auto: none` — schema is owned by Flyway, never Hibernate).
`V1__create_api_keys_table.sql` seeds a dev key: the raw key is `test-key-dev-123` (its SHA-256 is
what's stored). Pass it as `X-API-Key: test-key-dev-123`.

## Request flow

`ParseController` → `ParsePipelineService.process(file, apiKey)` is the spine. The pipeline:

1. **Validate** — magic bytes (`%PDF`), content-type, 50 MB cap (`ParsePipelineService`).
2. **Extract** — `PdfTextExtractorService.extract(bytes, plan)` parses with PDFBox once per page and
   re-assembles text in memory. Page count is capped per plan (`AppProperties.PageLimits.forPlan`).
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
only in the security chain needs the same treatment. `ApiKeyFilter` skips `/api/v1/health` and hashes
the incoming key with SHA-256 before lookup. `RateLimitFilter` and `QuotaEnforcementFilter` are
currently pass-through stubs.

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
  is hardcoded to `1.0` (issue #8), and the rate-limit/quota filters are no-ops. `TableDetectorService`,
  `TableExtractorService`, `VisionFallbackService`, `ConfidenceCalculatorService`,
  `UsageTrackingService`, and the OpenAI config exist for upcoming sprints — don't assume they're live.
- Only `ParseRagApplicationTests.contextLoads` exists today; `@SpringBootTest` needs a working
  datasource, so the context-load test requires the env vars above.
