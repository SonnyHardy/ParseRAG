# ParseRAG landing page: design brief

> Hand this file to Claude Design, together with the five brand images listed under **Assets**.
> Everything the design needs is in here: the copy is final, the palette is measured from the
> existing brand plates, and the constraints come from the framework the page will be built in.

---

## 1. What to produce

A design canvas for **one long single-page site**, plus the tokens needed to build it.

Artboards, in this order:

| # | Artboard | Width | Contents |
|---|---|---|---|
| 1 | Desktop, full page | 1440 | Every section, top to bottom, in the order of section 6 |
| 2 | Mobile, full page | 390 | The same sections, reflowed |
| 3 | Tablet, hero + one dense section | 768 | Enough to show how the two-column blocks collapse |
| 4 | Dark theme, hero + two sections | 1440 | Proves the palette inverts without losing the red and ochre |
| 5 | Style sheet | any | Colour tokens, type scale, spacing scale, button and link states |

Artboard 5 is not decoration. It is the handoff: it is read directly to produce the theme tokens,
so every value that appears on artboards 1 to 4 must be named there.

---

## 2. The product, in one paragraph

ParseRAG is a REST API that turns a PDF into RAG-ready JSON chunks. You upload a PDF to one
endpoint and get back an array of chunks, each with its text, its page number, a type
(`PARAGRAPH`, `TABLE`, `FIGURE_CAPTION`, `HEADER_ARTIFACT`), a `confidence` score and a
`manual_review_needed` flag. It reads multi-column layouts in real reading order, strips repeated
headers and footers, returns tables as structured JSON rather than flattened text, and sends
image-only pages through a vision model instead of returning an empty string. It is sold through
the RapidAPI marketplace.

---

## 3. Who reads this page, and what it must achieve

**The reader is a developer building a retrieval pipeline.** They already have a PDF parser that
technically works, and they are starting to suspect their retrieval quality is bad because of it.
They are sceptical, they skim, and they have seen a hundred API landing pages that promise
"AI-powered document intelligence" and deliver a wrapper around an open-source library.

**The page has exactly one conversion: clicking through to the RapidAPI listing.** There is no
sign-up form, no email capture, no demo to run. Every element either moves the reader toward that
click or is dead weight.

Three consequences for the design:

- **Evidence outranks polish.** Every claim on this page is measured, and the numbers are the
  argument. Give the measured figures real typographic weight; they are the hero content, not
  garnish.
- **Show the output.** A developer decides on the shape of the JSON they will receive. Code and
  data are primary content here, not an afterthought in a small grey box.
- **No stock imagery, no illustrated people, no abstract gradients, no floating 3-D shapes.** The
  only images on this page are the four technical plates listed under Assets. If a section seems to
  need decoration, it needs better content instead.

---

## 4. Brand

### Palette, sampled from the existing plates

These are the actual pixel values from the shipped brand images. Build the light theme from them
rather than inventing a new palette.

| Role | Hex | Where it comes from |
|---|---|---|
| Ink | `#1e1b16` | Headings, body text, the dark panels, the logo |
| Ink, softer | `#3a3630` | Secondary text on light surfaces |
| Muted | `#69645d` | Captions, labels, metadata |
| Paper | `#fbf9f4` | Page background |
| Surface | `#f4f1ea` | Cards, panels, alternating section bands |
| Surface, deeper | `#efe9dc` | Nested panels, code block backgrounds |
| Hairline | `#d2cabb` | Borders, rules, table lines |
| Red | `#8c2f26` | The accent. The "wrong" state, the confidence bar, the primary CTA |
| Ochre | `#a8752c` | The second accent. Column two in the before/after, secondary highlights |

**The red is scarce on purpose.** In the logo it is a single bar out of six. It marks the one thing
that matters in a given view: the primary CTA, or the broken half of a before/after. If red appears
three times in one viewport, it has stopped meaning anything.

Derive the dark theme from these rather than from a generic dark palette: warm near-black ground,
paper-coloured text, and the red and ochre lifted enough to hold AA contrast on a dark ground.

### Typography

Three roles, and the third is not optional:

- **Headings.** A confident sans or a low-contrast serif. Not a geometric startup sans.
- **Body.** A neutral, highly readable sans at a generous size. Developers read this page as prose.
- **Monospace.** Used heavily: JSON samples, field names, error codes, endpoint paths, the
  before/after plates. It carries a third of the page's content, so pick it deliberately and give
  it a real type scale, not just `font-family: monospace`.

Prefer families available on Google Fonts. Name exact families and weights on artboard 5.

### The logo

`logo-flat-500.png`: a full-width band, then two columns split by a gutter, then a full-width band.
It is a literal picture of what the parser does, detecting a page as a stack of bands rather than a
fixed number of columns. One bar is red: that is the chunk carrying a confidence score.

It must stay legible at 32 px in a browser tab and at 40 px in the page header. Do not add a
gradient, a shadow or a rounded container to it.

### Assets to attach with this brief

All under `docs/brand/ParseRAG_iconography/export/`:

| File | Use |
|---|---|
| `logo-flat-500.png` | Header, footer, favicon |
| `wordmark-light.png` / `wordmark-dark.png` | Header lockup, one per theme |
| `01-problem.png` | The before/after plate. The single most important image on the page |
| `03-pipeline.png` | The "how it works" plate |
| `04-hard-cases.png` | The hard-cases section |
| `02-response.png` | Optional, next to the response section |

These plates are already designed and carry the brand. **Design the page around them rather than
redrawing them**, and make sure the surrounding surface does not clash with their cream ground.

---

## 5. Hard constraints from the build

The page will be built in Angular 22 with PrimeNG 22, prerendered to static HTML and served from
Vercel. That is not background information, it changes what is buildable:

- **Everything must be readable in the served HTML before any JavaScript runs.** No design that
  depends on a scroll-triggered reveal to become legible, no content that only exists inside an
  opened accordion. Animation may enhance; it may never be the thing that makes text appear.
- **The FAQ will be a PrimeNG Accordion**, the CTAs PrimeNG Buttons, and cards and tags may be
  PrimeNG too. Design shapes that a themeable component library can produce: consistent radii,
  consistent border weights, states that come from tokens. A bespoke button per section will be
  rebuilt as a generic one anyway.
- **Both themes ship.** The viewer's `prefers-color-scheme` picks one. There is no theme toggle
  unless artboard 5 specifies its exact placement and states.
- **Breakpoints: 360, 768, 1440.** Anything wider is the 1440 layout centred. Nothing may scroll
  horizontally, so specify how the wide items (the JSON block, the plan table, the four-row problem
  table, the plates) behave on a 360 px screen. Each of them scrolls inside its own container or
  reflows; say which.
- **The plates are raster images.** Give them explicit aspect ratios so the layout does not shift
  while they load, and say what happens to them at 360 px, where their internal text will be too
  small to read.
- **WCAG AA contrast on both themes**, including the ochre, which is the risky one on cream.

---

## 6. Sections, in order, with final copy

The copy below is final and reviewed. Use it as-is. Where a section says *"design decides"*, the
wording is yours.

### 6.1 Header

Logo plus wordmark on the left. One link on the right: `Docs` (anchors to the response section) and
the primary CTA button, `View on RapidAPI`. Nothing else. No hamburger menu on mobile for two
links.

### 6.2 Hero

- **H1:** Turn any PDF into embedding-ready JSON chunks
- **Sub:** One endpoint, one upload. Text in real reading order, repeated headers and footers
  stripped, tables as structured JSON, scanned pages handled by a vision model, and a confidence
  score on every chunk.
- **Primary CTA:** View on RapidAPI
- **Secondary CTA:** See the response
- **Under the buttons, small:** Free plan available. 50 MB per file on every plan.

The hero must contain one piece of evidence, not just the promise. Either the measured figure
(`87 → 8` chunks needing manual review, 542-page corpus) or a compact fragment of the response
JSON. Pick one and give it presence; do not put both in the hero.

### 6.3 The problem

- **H2:** Text extraction is quietly wrong
- **Intro:** A plain PDF-to-text call gives you a string. That string is often wrong in ways you
  will not notice until your retrieval quality is already bad.

A four-row table:

| What goes wrong | What it does to your index |
|---|---|
| Two-column papers are stitched line by line | Every chunk mixes two unrelated sentences. Embeddings become noise. |
| Running titles, DOI lines and page numbers repeat on every page | The same boilerplate dilutes every single embedding in the document. |
| Tables collapse into a wall of numbers | Row and column relationships are lost, so the table can never be answered from. |
| Scanned pages return an empty string | The document silently indexes as blank, and nobody finds out. |

Then `01-problem.png` at full section width, and under it:

- **Caption:** Measured across a 542-page corpus: chunks needing manual review fell from 87 to 8.

This section is the emotional centre of the page. Give it the most vertical room.

### 6.4 How it works

- **H2:** How it works
- `03-pipeline.png`, plus four short steps, *design decides* the layout:
  1. **Validate.** Magic bytes, content type, 50 MB cap.
  2. **Extract.** The page is read as a stack of bands, not as a fixed number of columns, so a
     title spanning two columns does not scramble the text under it.
  3. **Clean.** Three stacked detectors remove running headers, footers, DOI lines and page numbers.
  4. **Chunk.** Chunks come out sized for embedding, each with a type and a confidence score.

- **Pull quote, given real weight:** The geometry can be wrong and the output still right. Every
  page is re-read after assembly, and re-assembled differently if the text shows the signature of
  interleaved columns.

### 6.5 What you get back

- **H2:** What you get back
- **Intro:** One `POST`, `multipart/form-data`, one part named `file`.

A real JSON block, monospace, syntax-coloured, the primary content of this section:

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
      "confidence": 0.94,
      "fallback_used": false,
      "manual_review_needed": false
    }
  ]
}
```

Beside or under it, four field notes, *design decides* the layout:

- **`type`** is one of `PARAGRAPH`, `TABLE`, `FIGURE_CAPTION`, `HEADER_ARTIFACT`.
- **`confidence`** is per chunk, not per document.
- **`manual_review_needed`** is the flag to filter on before indexing.
- **`table_json`** appears on `TABLE` chunks, carrying `headers`, `rows` and an optional `caption`.

### 6.6 The hard cases

- **H2:** The cases that break plain extraction
- `04-hard-cases.png`, plus three items:
  - **Scanned pages.** Image-only pages go through a vision model instead of coming back empty.
    Those chunks are marked `fallback_used`.
  - **Tables.** Returned as `headers` and `rows`, not as a wall of numbers. A quality gate decides
    when a broken grid is worth re-reading with a vision model.
  - **Multi-column layouts.** Read column by column, band by band, and verified afterwards.
- **Closing line, its own emphasis:** It always returns a result. If the vision provider slows down,
  the remaining pages come back flagged for review rather than the request hanging until a gateway
  timeout. A partial answer you can act on beats a 504 you cannot.

### 6.7 Plans

- **H2:** Plans
- **Intro:** Requests are metered by RapidAPI. ParseRAG enforces one limit of its own, because the
  marketplace counts requests and cannot see how big a job is: pages per document.

| Plan | Requests / month | Requests / minute | Pages per document |
|---|---|---|---|
| BASIC | 50 | 2 | 100 |
| PRO | 1,000 | 5 | 300 |
| ULTRA | 7,500 | 10 | 500 |
| MEGA | 50,000 | 20 | 1,000 |

- **Under the table:** The 50 MB file cap applies on every plan. Over the page cap, the call is
  rejected with `DOCUMENT_TOO_LONG` rather than truncated: a silently half-parsed document is worse
  than a clear refusal.

Prices are deliberately absent. They live on the RapidAPI listing and would go stale here. Do not
design price cards.

### 6.8 FAQ

- **H2:** Questions

Six items, in a PrimeNG Accordion. Questions are worded exactly as a developer would type them,
because this section is also what an AI assistant will quote:

1. **Does it work on scanned documents?** Yes. Image-only pages skip native extraction and go to a
   vision model. Those chunks come back with `fallback_used: true`. Quality depends on the scan, so
   check `confidence` before indexing.
2. **What languages are supported?** Text extraction is language-agnostic. The reported `language`
   field detects `fr`, `en`, `de` and `es`, and returns `unknown` otherwise. An unknown language
   does not affect extraction.
3. **How do I extract tables from a PDF as JSON?** `TABLE` chunks carry a `table_json` object with
   `headers`, `rows` and an optional `caption`. No separate endpoint or parameter is needed.
4. **Why is a chunk flagged when the text looks fine?** The flag is deliberately cautious. It is
   raised on the signature of a layout problem, not on proof of one. A false positive costs you a
   review; a false negative costs you a poisoned index.
5. **How long does a parse take?** A native-text document of 200 pages parses in a few seconds. A
   scanned document is far slower, because each page goes through a vision model. Keep your client
   timeout high.
6. **Is there a batch endpoint?** Not today. Send documents one at a time, and stay inside your
   plan's requests-per-minute limit.

The first line of each answer must stand on its own, without the question. That is a design
constraint as much as a writing one: do not design an answer body that only makes sense as a
continuation of its heading.

### 6.9 Closing CTA

- **H2:** *design decides*, one line, direct.
- **Primary CTA:** View on RapidAPI
- **Under it:** Free plan available.

### 6.10 Footer

Minimal. Logo, one line of copyright, and links: RapidAPI listing, Terms, Privacy. No newsletter,
no social icons, no sitemap columns.

---

## 7. What not to do

- No invented numbers. Every figure on this page traces to a measurement: `87 → 8`, `542 pages`,
  `50 MB`, the plan table. If a design needs a statistic that is not in this brief, leave the slot
  empty and flag it.
- No fake social proof. No logo wall, no testimonials, no "trusted by" counter. The product has no
  public customers yet, and inventing them would be the fastest way to lose a sceptical reader.
- No pricing in currency anywhere on the page.
- No cookie banner in the design. The analytics chosen for this site is cookie-free.
- No hero screenshot of a dashboard. There is no dashboard; the product is one endpoint.

---

## 8. What to hand back

1. The five artboards.
2. **A named token list** on artboard 5: colours with roles, the type scale with families, weights
   and sizes, the spacing scale, radii, border widths, and the states for buttons and links in both
   themes. Naming matters more than exhaustiveness, because these names become the theme variables.
3. **A note on anything you changed** from this brief and why. A section reordered, a claim cut, a
   plate used differently: all fine, but say so, because the copy and the numbers are load-bearing
   and the implementation will follow the design rather than this file.
