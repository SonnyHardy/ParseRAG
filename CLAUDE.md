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
what's stored). Pass it as `X-API-Key: test-key-dev-123`. That key is also the **admin** key
(`V3__add_admin_to_api_keys.sql`) — the only one allowed on `/api/v1/health`. Note V1's stored hash
was actually SHA-256 of `123`, not of the documented key; V3 corrects it in place (V1 itself is left
untouched — editing an applied migration breaks Flyway checksum validation).

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
  is hardcoded to `1.0` (issue #8). `TableDetectorService`, `TableExtractorService`,
  `VisionFallbackService`, and `ConfidenceCalculatorService` exist for upcoming sprints — don't assume
  they're live. The rate-limit and quota filters, by contrast, are no longer stubs (issues #14/#13).
- **Tests are plain unit tests** (JUnit 5 + Mockito + `spring-test` mocks), no Spring context —
  collaborators are mocked and web plumbing uses `MockHttpServletRequest`/`MockFilterChain`. Keep new
  tests in that style. The one exception is `ParseRagApplicationTests.contextLoads`: `@SpringBootTest`
  needs a working datasource, so it requires the env vars above.
