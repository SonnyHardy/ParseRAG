import {
  Component,
  ElementRef,
  Injector,
  OnDestroy,
  PLATFORM_ID,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  viewChildren,
} from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { Motion, type MotionApi } from './motion';

type RowKind = 'normal' | 'muted' | 'result';

interface StageRow {
  readonly text: string;
  readonly kind: RowKind;
}

interface Stage {
  readonly num: string;
  readonly label: string;
  readonly title: string;
  readonly body: string;
  readonly meta: string;
  readonly rows: readonly StageRow[];
}

/**
 * Les cinq etapes du pipeline, panneau colle a gauche, recit deroulant a droite.
 *
 * Le design pilotait l'etape active avec ScrollTrigger de GSAP, charge depuis un CDN. Ici c'est un
 * IntersectionObserver, qui fait le meme travail sans dependance et sans script tiers.
 *
 * Ce qui compte davantage : **les cinq etapes sont toutes dans le HTML prerendu**, avec leur titre
 * et leur texte. Le defilement ne fait que mettre en avant celle qu'on lit ; il n'en revele
 * aucune. Sans JavaScript la page reste complete, seule l'etape 01 restant affichee dans le
 * panneau.
 */
@Component({
  selector: 'pr-how-it-works',
  template: `
    <section id="how" class="pr-shell hw">
      <div class="hw__intro">
        <div class="pr-kicker">How it works</div>
        <h2 class="pr-h2">From PDF to RAG-ready chunks.</h2>
        <p class="pr-lede">One request. Five stages. Scroll to follow the document through.</p>
      </div>

      <div class="hw__grid">
        <div class="hw__sticky">
          <div class="picker" role="tablist" aria-label="Pipeline stages">
            @for (stage of stages; track stage.num; let i = $index) {
              <button
                type="button"
                role="tab"
                class="picker__btn"
                [class.picker__btn--on]="active() === i"
                [attr.aria-selected]="active() === i"
                (click)="select(i)"
                (mouseenter)="select(i)"
              >
                <span class="picker__num pr-mono">{{ stage.num }}</span>
                <span class="picker__label pr-mono">{{ stage.label }}</span>
              </button>
            }
          </div>

          <div class="stage">
            <div class="stage__head">
              <span class="stage__meta pr-mono">{{ current().meta }}</span>
              <span class="stage__count pr-mono">stage {{ current().num }} / 05</span>
            </div>

            <div class="stage__doc-wrap">
              <div class="doc" aria-hidden="true">
                <div class="doc__art"></div>
                <div class="doc__title"></div>
                <div class="doc__cols">
                  <div class="doc__col">
                    <span></span><span></span><span></span><span class="w66"></span>
                  </div>
                  <div class="doc__col">
                    <span></span><span></span><span></span><span class="w54"></span>
                  </div>
                </div>
                <div class="doc__table">
                  <span class="strong"></span><span class="strong"></span><span class="strong"></span>
                  <span class="soft"></span><span class="soft"></span><span class="soft"></span>
                </div>
                <div class="doc__art doc__art--short"></div>

                @switch (active()) {
                  @case (0) {
                    <div class="doc__stamp pr-mono">accepted</div>
                  }
                  @case (1) {
                    <div class="doc__band doc__band--a"></div>
                    <div class="doc__band doc__band--b"></div>
                    <div class="doc__band doc__band--c"></div>
                  }
                  @case (3) {
                    <div class="doc__chunk doc__chunk--a pr-mono">chunk_000</div>
                    <div class="doc__chunk doc__chunk--b pr-mono">chunk_001</div>
                    <div class="doc__chunk doc__chunk--c pr-mono">chunk_002 &middot; TABLE</div>
                  }
                  @case (4) {
                    <div class="doc__json pr-mono">
                      <div>&#123;</div>
                      <div class="i"><span class="k">"pages"</span>: <span class="v">12</span>,</div>
                      <div class="i"><span class="k">"chunks"</span>: [ <span class="v">47</span> ],</div>
                      <div class="i"><span class="c">"confidence"</span>: <span class="c">0.94</span></div>
                      <div>&#125;</div>
                    </div>
                  }
                }
              </div>
            </div>

            <ul class="stage__rows pr-mono">
              @for (row of current().rows; track row.text) {
                <li class="srow" [class]="'srow--' + row.kind">
                  <span class="srow__dot" aria-hidden="true"></span>
                  <span>{{ row.text }}</span>
                </li>
              }
            </ul>
          </div>
        </div>

        <div class="hw__story">
          @for (stage of stages; track stage.num; let i = $index) {
            <div #block class="block" [class.block--on]="active() === i">
              <div class="block__kicker pr-mono">{{ stage.num }} &mdash; {{ stage.label }}</div>
              <h3 class="block__title">{{ stage.title }}</h3>
              <p class="block__body">{{ stage.body }}</p>
            </div>
          }
        </div>
      </div>
    </section>
  `,
  styles: `
    .hw { padding-block: 96px 40px; }

    .hw__intro {
      display: flex;
      flex-direction: column;
      gap: 14px;
      max-width: 52ch;
      margin-bottom: 40px;
    }

    .hw__grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(400px, 100%), 1fr));
      gap: 32px;
      align-items: start;
    }

    .hw__sticky {
      position: sticky;
      top: 96px;
    }

    .picker {
      display: flex;
      flex-wrap: wrap;
      gap: 5px;
    }

    .picker__btn {
      appearance: none;
      cursor: pointer;
      flex: 1 1 76px;
      box-sizing: border-box;
      display: flex;
      flex-direction: column;
      gap: 6px;
      align-items: flex-start;
      padding: 11px 12px;
      border: 1px solid var(--pr-hairline);
      background: var(--pr-surface);
      color: var(--pr-ink-soft);
      font-family: inherit;
      text-align: left;
      transition: border-color 0.2s ease, background 0.2s ease;
    }

    .picker__btn:hover {
      border-color: var(--pr-red);
      background: var(--pr-surface-deep);
    }

    .picker__btn--on {
      border-color: var(--pr-ink);
      background: var(--pr-ink);
      color: var(--pr-surface);
    }

    .picker__num {
      font-size: 11px;
      font-weight: 700;
      color: var(--pr-red);
    }

    .picker__btn--on .picker__num { color: var(--pr-amber); }

    .picker__label {
      font-size: 13px;
      font-weight: 700;
      letter-spacing: 0.04em;
    }

    .stage {
      border: 1px solid var(--pr-ink);
      border-top: 0;
      background: var(--pr-surface);
    }

    .stage__head {
      padding: 16px 20px;
      border-bottom: 1px solid var(--pr-hairline);
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }

    .stage__meta {
      font-size: 12px;
      letter-spacing: 0.14em;
      text-transform: uppercase;
      color: var(--pr-red);
    }

    .stage__count {
      margin-left: auto;
      font-size: 12px;
      color: var(--pr-muted);
    }

    .stage__doc-wrap { padding: 22px 22px 0; }

    .doc {
      position: relative;
      box-sizing: border-box;
      height: 186px;
      border: 1px solid var(--pr-hairline);
      background: var(--pr-paper);
      padding: 14px 16px;
      display: flex;
      flex-direction: column;
      gap: 8px;
      overflow: hidden;
    }

    .doc__art { height: 5px; background: var(--pr-mock); width: 52%; }
    .doc__art--short { width: 38%; }
    .doc__title { height: 8px; background: var(--pr-mock-strong); width: 74%; }

    .doc__cols { display: flex; gap: 14px; flex: 1; }

    .doc__col {
      flex: 1;
      display: flex;
      flex-direction: column;
      gap: 5px;

      span { height: 5px; background: var(--pr-mock); }
      .w66 { width: 66%; }
      .w54 { width: 54%; }
    }

    .doc__table {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: 3px;
      border: 1px solid var(--pr-hairline);
      padding: 4px;

      span { height: 5px; }
      .strong { background: var(--pr-mock-strong); }
      .soft { background: var(--pr-mock-soft); }
    }

    .doc__stamp {
      position: absolute;
      right: 12px;
      top: 10px;
      font-size: 10px;
      letter-spacing: 0.12em;
      text-transform: uppercase;
      color: var(--pr-red);
      border: 1px solid var(--pr-red);
      padding: 3px 7px;
      background: var(--pr-paper);
    }

    .doc__band {
      position: absolute;
      left: 10px;
      right: 10px;
      border: 1px solid var(--pr-red);
    }

    .doc__band--a { top: 28px; height: 14px; }
    .doc__band--b { top: 48px; height: 66px; }
    .doc__band--c { top: 120px; height: 30px; border-color: var(--pr-ochre); }

    .doc__chunk {
      position: absolute;
      left: 10px;
      right: 10px;
      border: 1px solid var(--pr-ink);
      background: rgb(30 27 22 / 5%);
      display: flex;
      align-items: flex-end;
      justify-content: flex-end;
      padding: 3px 5px;
      font-size: 9px;
      color: var(--pr-ink-soft);
    }

    .doc__chunk--a { top: 26px; height: 44px; }
    .doc__chunk--b { top: 74px; height: 40px; }

    .doc__chunk--c {
      top: 118px;
      height: 32px;
      border-color: var(--pr-red);
      background: rgb(140 47 38 / 5%);
      color: var(--pr-red);
    }

    .doc__json {
      position: absolute;
      inset: 0;
      background: var(--pr-dark);
      padding: 14px 16px;
      font-size: 11px;
      line-height: 1.7;
      color: var(--pr-code-text);

      .i { padding-left: 12px; }
      .k { color: var(--pr-code-punct); }
      .v { color: var(--pr-on-dark-soft); }
      .c { color: var(--pr-code-keyword); }
    }

    .stage__rows {
      list-style: none;
      margin: 0;
      padding: 22px 22px 26px;
      min-height: 150px;
      display: flex;
      flex-direction: column;
      gap: 8px;
      font-size: 14px;
      line-height: 1.65;
    }

    .srow {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .srow__dot { flex: none; width: 4px; height: 4px; }

    .srow--normal { color: var(--pr-ink-soft); }
    .srow--normal .srow__dot { background: var(--pr-ochre); }

    /* Les lignes barrees de l'etape CLEAN : le trait est rouge sur un texte gris, ce qui montre
       ce qui a ete retire sans laisser croire que le texte lui-meme etait faux. */
    .srow--muted {
      color: var(--pr-muted-strike);
      text-decoration: line-through;
      text-decoration-color: var(--pr-red);
    }

    .srow--muted .srow__dot { background: var(--pr-mock-strong); }

    .srow--result {
      margin-top: 5px;
      padding-top: 11px;
      border-top: 1px solid var(--pr-hairline);
      color: var(--pr-red);
      font-weight: 700;
    }

    .srow--result .srow__dot { background: var(--pr-red); }

    .hw__story {
      display: flex;
      flex-direction: column;
    }

    .block {
      box-sizing: border-box;
      min-height: 60vh;
      display: flex;
      flex-direction: column;
      justify-content: center;
      gap: 14px;
      padding: 36px 0 36px 26px;
      border-left: 2px solid var(--pr-hairline-soft);
      transition: border-color 0.3s ease;
    }

    .block--on { border-left-color: var(--pr-red); }

    .block__kicker {
      font-size: 12px;
      letter-spacing: 0.16em;
      text-transform: uppercase;
      color: var(--pr-mock-inactive);
      transition: color 0.3s ease;
    }

    .block--on .block__kicker { color: var(--pr-red); }

    .block__title {
      margin: 0;
      font-size: clamp(24px, 2.6vw, 32px);
      font-weight: 600;
      letter-spacing: -0.02em;
      line-height: 1.16;
      max-width: 26ch;
      color: var(--pr-mock-inactive-text);
      transition: color 0.3s ease;
    }

    .block--on .block__title { color: var(--pr-ink); }

    .block__body {
      margin: 0;
      font-size: 18px;
      line-height: 1.55;
      color: var(--pr-on-dark-muted);
      max-width: 42ch;
      transition: color 0.3s ease;
    }

    .block--on .block__body { color: var(--pr-ink-soft); }
  `,
})
export class HowItWorks implements OnDestroy {
  private readonly blocks = viewChildren<ElementRef<HTMLElement>>('block');
  private readonly platformId = inject(PLATFORM_ID);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly motion = inject(Motion);
  private api: MotionApi | null = null;
  private observer?: IntersectionObserver;
  private morph?: gsap.core.Timeline;
  private readonly injector = inject(Injector);

  protected readonly active = signal(0);
  protected readonly current = computed(() => this.stages[this.active()]);

  protected readonly stages: readonly Stage[] = [
    {
      num: '01',
      label: 'VALIDATE',
      title: 'Rejected before it costs you anything.',
      body: 'File signature and content type checked, 50 MB cap enforced, before a single page is read.',
      meta: 'PDF signature + 50 MB cap',
      rows: [
        { text: '%PDF-1.7', kind: 'normal' },
        { text: 'content-type: application/pdf', kind: 'normal' },
        { text: 'size: 4.2 MB / 50 MB', kind: 'normal' },
        { text: 'accepted', kind: 'result' },
      ],
    },
    {
      num: '02',
      label: 'EXTRACT',
      title: 'Correct reading order, even on multi-column pages.',
      body: 'Read as a stack of bands rather than a fixed column count, so a spanning title cannot scramble the text beneath it. Tables keep their structure; pages with no text layer go to a vision model.',
      meta: 'column-aware bands + vision fallback',
      rows: [
        { text: 'band 1   full width   title', kind: 'normal' },
        { text: 'band 2   2 columns   body', kind: 'normal' },
        { text: 'band 3   full width   caption', kind: 'normal' },
        { text: 'band 4   2 columns   body', kind: 'normal' },
        { text: 'reading order resolved', kind: 'result' },
      ],
    },
    {
      num: '03',
      label: 'CLEAN',
      title: 'Boilerplate stops diluting your embeddings.',
      body: 'Journal titles, DOI lines and page numbers that repeat across pages are detected and stripped.',
      meta: 'type: HEADER_ARTIFACT',
      rows: [
        { text: 'J. Retrieval Systems, Vol. 12', kind: 'muted' },
        { text: 'doi:10.0000/jrs.2024.0312', kind: 'muted' },
        { text: 'page 4 of 12', kind: 'muted' },
        { text: '3 artifacts removed', kind: 'result' },
      ],
    },
    {
      num: '04',
      label: 'CHUNK',
      title: 'Sized for embedding, scored for trust.',
      body: 'Overlapping windows, each with a type, a confidence score, and a flag when the parser is not sure.',
      meta: 'confidence + manual_review_needed',
      rows: [
        { text: 'chunk_000  PARAGRAPH  0.94', kind: 'normal' },
        { text: 'chunk_001  PARAGRAPH  0.91', kind: 'normal' },
        { text: 'chunk_002  TABLE      0.88', kind: 'normal' },
        { text: 'chunk_003  PARAGRAPH  0.41  flagged', kind: 'result' },
      ],
    },
    {
      num: '05',
      label: 'JSON',
      title: 'One structured response.',
      body: 'Document metadata and an ordered array of chunks, ready to embed.',
      meta: '200 OK  application/json',
      rows: [
        { text: 'document_id: doc_9f3c1a7b', kind: 'normal' },
        { text: 'pages: 12', kind: 'normal' },
        { text: 'processing_ms: 1843', kind: 'normal' },
        { text: 'chunks: [ 47 ]', kind: 'result' },
      ],
    },
  ];

  constructor() {
    afterNextRender(async () => {
      this.observeBlocks();
      this.api = await this.motion.load();
      if (!this.api) {
        return;
      }
      // Le changement d'etape rejoue les lignes du panneau et pose un leger tassement sur le
      // document : sans ce mouvement, le passage d'une etape a l'autre est un remplacement sec et
      // on ne voit pas que c'est le meme document qui evolue.
      effect(
        () => {
          this.active();
          queueMicrotask(() => this.morphStage());
        },
        { injector: this.injector },
      );
    });
  }

  ngOnDestroy(): void {
    this.observer?.disconnect();
    this.morph?.kill();
  }

  private morphStage(): void {
    const api = this.api;
    if (!api) {
      return;
    }
    const { gsap } = api;
    const root = this.host.nativeElement;
    const rows = Array.from(root.querySelectorAll('.srow'));
    const doc = root.querySelector('.doc');
    this.morph?.kill();
    const tl = gsap.timeline();
    this.morph = tl;
    if (rows.length) {
      tl.fromTo(
        rows,
        { opacity: 0, y: 8 },
        { opacity: 1, y: 0, duration: 0.38, ease: 'power2.out', stagger: 0.05, overwrite: true },
        0,
      );
    }
    if (doc) {
      tl.fromTo(doc, { scale: 0.988 }, { scale: 1, duration: 0.6, ease: 'power2.out' }, 0);
    }
  }

  protected select(index: number): void {
    this.active.set(index);
  }

  /**
   * Synchronise l'etape sur le defilement. La bande d'observation est reduite au tiers median de
   * la fenetre : sans elle, deux blocs sont visibles en meme temps et l'etape active oscille.
   */
  private observeBlocks(): void {
    if (!isPlatformBrowser(this.platformId) || typeof IntersectionObserver === 'undefined') {
      return;
    }

    const elements = this.blocks().map((ref) => ref.nativeElement);
    this.observer = new IntersectionObserver(
      (entries) => {
        // On retient le bloc dont le centre est le plus proche de celui de la fenetre, et non le
        // dernier de la liste. En defilement continu un seul bloc traverse la bande a la fois et
        // les deux reviennent au meme ; mais sur un saut d'ancre, comme le lien « How it works »
        // de la barre de navigation, plusieurs entrees arrivent dans le meme appel et le dernier
        // gagnant est arbitraire : le selecteur affichait alors une etape sans rapport avec le
        // bloc a l'ecran.
        const middle = window.innerHeight / 2;
        let best: { index: number; distance: number } | undefined;

        for (const entry of entries) {
          if (!entry.isIntersecting) {
            continue;
          }
          const index = elements.indexOf(entry.target as HTMLElement);
          if (index < 0) {
            continue;
          }
          const rect = entry.boundingClientRect;
          const distance = Math.abs(rect.top + rect.height / 2 - middle);
          if (!best || distance < best.distance) {
            best = { index, distance };
          }
        }

        if (best) {
          this.active.set(best.index);
        }
      },
      { rootMargin: '-33% 0px -33% 0px', threshold: 0 },
    );

    elements.forEach((el) => this.observer?.observe(el));
  }
}
