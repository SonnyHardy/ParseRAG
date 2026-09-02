import { Component, ElementRef, OnDestroy, afterNextRender, inject } from '@angular/core';
import { Motion, type MotionApi } from './motion';

/**
 * Les quatre PDF qui cassent les parseurs, et la formule qui ferme la section.
 *
 * Les deux premieres cartes s'animent : un balayage detecte les regions de la page scannee puis
 * en fait sortir le texte, et la grille du tableau se separe en lignes et colonnes avant de
 * devenir du JSON. Contrairement aux autres sections, elles ne bouclent pas : une passe a
 * l'entree, et une relecture au survol. Une carte qui s'agite en permanence dans un coin de
 * l'ecran est une nuisance ; la meme, rejouable a la demande, est une demonstration.
 */
@Component({
  selector: 'pr-edge-cases',
  template: `
    <section id="edge" class="pr-shell ec">
      <div class="ec__intro">
        <div class="pr-kicker">Edge cases</div>
        <h2 class="pr-h2">Built for the PDFs that break parsers.</h2>
      </div>

      <div class="ec__grid">
        <div class="ec__card" data-card="scan">
          <div class="art art--scan" aria-hidden="true">
            <span class="ocr"></span><span class="ocr"></span><span class="ocr w62"></span>
            <span class="det det--a"></span>
            <span class="det det--b"></span>
            <span class="det det--c"></span>
            <span class="sweep"></span>
          </div>
          <div class="ec__name">Scanned pages</div>
          <div class="ec__field pr-mono">
            <span class="ec__key">fallback_used</span><span class="ec__punct">: </span
            ><span class="ec__true">true</span>
          </div>
        </div>

        <div class="ec__card" data-card="table">
          <div class="art art--table-wrap" aria-hidden="true">
            <div class="art--table">
              <span class="cell hdr"></span><span class="cell hdr"></span><span class="cell hdr"></span>
              <span class="cell"></span><span class="cell"></span><span class="cell"></span>
              <span class="cell"></span><span class="cell"></span><span class="cell"></span>
            </div>
            <div class="tbl-json pr-mono">
              <span>"headers": [ ... ]</span>
              <span>"rows": [ [ ... ], ... ]</span>
            </div>
          </div>
          <div class="ec__name">Borderless tables</div>
          <div class="ec__field pr-mono">
            <span class="ec__key">type</span><span class="ec__punct">: </span
            ><span class="ec__ochre">"TABLE"</span>
          </div>
        </div>

        <div class="ec__card">
          <div class="art art--strike">
            <span class="strike pr-mono">J. Retrieval Systems, Vol. 12</span>
            <span class="strike pr-mono">doi:10.0000/jrs.2024.0312</span>
          </div>
          <div class="ec__name">Repeated headers</div>
          <div class="ec__field pr-mono">
            <span class="ec__key">type</span><span class="ec__punct">: </span
            ><span class="ec__ochre">"HEADER_ARTIFACT"</span>
          </div>
        </div>

        <div class="ec__card ec__card--low">
          <div class="art art--bars" aria-hidden="true">
            @for (bar of bars; track $index) {
              <span [class.low]="bar.low" [style.height.%]="bar.height"></span>
            }
          </div>
          <div class="ec__name">Low confidence</div>
          <div class="ec__field pr-mono">
            <span class="ec__key">manual_review_needed</span><span class="ec__punct">: </span
            ><span class="ec__true">true</span>
          </div>
        </div>
      </div>

      <div class="ec__punch">
        <p>Silent failures poison indexes. Honest flags protect them.</p>
      </div>
    </section>
  `,
  styles: `
    .ec { padding-block: 96px; }

    .ec__intro {
      display: flex;
      flex-direction: column;
      gap: 14px;
      max-width: 52ch;
      margin-bottom: 40px;
    }

    /* Quatre cartes en une seule grille auto-fit : le design les groupait deux par deux dans des
       grilles imbriquees, ce qui donne le meme rendu a 1440 mais un empilement moins previsible
       aux largeurs intermediaires. */
    .ec__grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(230px, 100%), 1fr));
      gap: 16px;
      margin-bottom: 44px;
    }

    .ec__card {
      box-sizing: border-box;
      border: 1px solid var(--pr-hairline);
      background: var(--pr-surface);
      padding: 22px;
      display: flex;
      flex-direction: column;
      gap: 16px;
    }

    .ec__card--low {
      border-color: var(--pr-red);
      background: var(--pr-low-bg);
    }

    .art { height: 48px; }

    .art--scan {
      position: relative;
      overflow: hidden;
      display: flex;
      flex-direction: column;
      justify-content: center;
      gap: 5px;
      padding: 0 10px;
      background: var(--pr-mock-scan);
      border: 1px dashed var(--pr-mock-scan-border);

      .ocr { height: 4px; background: var(--pr-mock-scan-line); }
      .w62 { width: 62%; }
    }

    /* Cadres de detection et barre de balayage. Ceux-la sont caches en CSS, contrairement au
       reste de la page : ce ne sont pas des contenus mais des artefacts d'animation, et leur etat
       de repos est bien l'absence. La regle « aucun etat initial en CSS » protege le texte, pas
       les decors. */
    .det {
      position: absolute;
      left: 8px;
      right: 8px;
      height: 8px;
      border: 1px solid var(--pr-red);
      opacity: 0;
    }

    .det--a { top: 11px; }
    .det--b { top: 22px; }
    .det--c { top: 33px; right: 44%; }

    .sweep {
      position: absolute;
      left: 0;
      right: 0;
      top: 0;
      height: 2px;
      background: var(--pr-red);
      opacity: 0;
    }

    .art--table-wrap { position: relative; }

    .art--table {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: 4px;
      align-content: center;
      height: 48px;

      .cell { height: 6px; background: var(--pr-mock); }
      .hdr { background: var(--pr-ochre); }
    }

    .tbl-json {
      position: absolute;
      inset: 0;
      display: flex;
      flex-direction: column;
      justify-content: center;
      gap: 3px;
      font-size: 12px;
      color: var(--pr-ochre-text);
      background: var(--pr-surface);
      opacity: 0;
    }

    .art--strike {
      display: flex;
      flex-direction: column;
      justify-content: center;
      gap: 7px;
    }

    .strike {
      font-size: 12px;
      color: var(--pr-muted-strike);
      text-decoration: line-through;
      text-decoration-color: var(--pr-red);
      text-decoration-thickness: 2px;
    }

    .art--bars {
      display: flex;
      align-items: flex-end;
      gap: 5px;

      span { flex: 1; background: var(--pr-low-bar); }
      .low { background: var(--pr-red); }
    }

    .ec__name {
      font-size: 19px;
      font-weight: 600;
      letter-spacing: -0.01em;
    }

    .ec__field { font-size: 14px; }
    .ec__key { color: var(--pr-ink-soft); }
    .ec__punct { color: var(--pr-muted); }
    .ec__true { color: var(--pr-red); font-weight: 700; }
    .ec__ochre { color: var(--pr-ochre-text); font-weight: 700; }

    .ec__punch {
      padding: 34px 36px;
      background: var(--pr-dark);

      p {
        margin: 0;
        font-size: clamp(24px, 3vw, 38px);
        font-weight: 500;
        letter-spacing: -0.022em;
        line-height: 1.2;
        color: var(--pr-on-dark);
        max-width: 34ch;
        text-wrap: balance;
      }
    }
  `,
})
export class EdgeCases implements OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly motion = inject(Motion);
  private api: MotionApi | null = null;
  private timelines: gsap.core.Timeline[] = [];

  constructor() {
    afterNextRender(async () => {
      this.api = await this.motion.load();
      if (!this.api) {
        return;
      }
      this.wire('[data-card="scan"]', () => this.runScan());
      this.wire('[data-card="table"]', () => this.runTable());
    });
  }

  ngOnDestroy(): void {
    this.timelines.forEach((tl) => tl.kill());
  }

  private wire(selector: string, run: () => void): void {
    const card = this.host.nativeElement.querySelector<HTMLElement>(selector);
    if (!card || !this.api) {
      return;
    }
    this.motion.onEnter(this.api, card, run, { once: true });
    card.addEventListener('mouseenter', run);
  }

  private track(tl: gsap.core.Timeline): gsap.core.Timeline {
    this.timelines.push(tl);
    return tl;
  }

  /** Balayage, regions detectees, puis le texte transcrit apparait. */
  private runScan(): void {
    const api = this.api;
    const card = this.host.nativeElement.querySelector('[data-card="scan"]');
    if (!api || !card) {
      return;
    }
    const { gsap } = api;
    const sweep = card.querySelector('.sweep');
    const lines = Array.from(card.querySelectorAll('.ocr'));
    const boxes = Array.from(card.querySelectorAll('.det'));

    const tl = this.track(gsap.timeline());
    gsap.set(lines, { opacity: 0 });
    gsap.set(boxes, { opacity: 0, scaleX: 0.9, transformOrigin: '0% 50%' });
    gsap.set(sweep, { y: 0, opacity: 0 });

    tl.to(sweep, { opacity: 1, duration: 0.12 }, 0)
      .to(sweep, { y: 46, duration: 1.15, ease: 'power1.inOut' }, 0.12)
      .to(boxes, { opacity: 1, scaleX: 1, duration: 0.26, stagger: 0.3, ease: 'power2.out' }, 0.34)
      .to(sweep, { opacity: 0, duration: 0.2 }, 1.3)
      .to(lines, { opacity: 1, duration: 0.3, stagger: 0.16 }, 1.5)
      .to(boxes, { opacity: 0, duration: 0.4, stagger: 0.08 }, 2.1);
  }

  /** Les cellules se detachent en lignes, s'alignent en colonnes, l'en-tete se separe, le JSON se forme. */
  private runTable(): void {
    const api = this.api;
    const card = this.host.nativeElement.querySelector('[data-card="table"]');
    if (!api || !card) {
      return;
    }
    const { gsap } = api;
    const cells = Array.from(card.querySelectorAll<HTMLElement>('.cell'));
    const json = card.querySelector('.tbl-json');
    const column = (i: number) => i % 3;
    const row = (i: number) => Math.floor(i / 3);

    const tl = this.track(gsap.timeline());
    gsap.set(json, { opacity: 0 });
    gsap.set(cells, { x: 0, y: 0, opacity: 1 });

    tl.to(cells, { y: (i: number) => row(i) * 4 - 4, duration: 0.4, ease: 'power2.out', stagger: { each: 0.04 } }, 0.2)
      .to(cells, { x: (i: number) => (column(i) - 1) * 7, duration: 0.45, ease: 'power3.inOut', stagger: 0.03 }, 0.7)
      .to(cells.filter((_, i) => row(i) === 0), { y: -9, duration: 0.35, ease: 'power2.out', stagger: 0.04 }, 1.2)
      .to(cells, { opacity: 0.12, duration: 0.4, stagger: 0.02 }, 1.5)
      .to(json, { opacity: 1, duration: 0.4, ease: 'power2.out' }, 1.8)
      .to(json, { opacity: 0, duration: 0.4 }, 3.4)
      .to(cells, { x: 0, y: 0, opacity: 1, duration: 0.55, ease: 'power3.inOut', stagger: 0.025 }, 3.5);
  }

  protected readonly bars = [
    { height: 78, low: false },
    { height: 92, low: false },
    { height: 34, low: true },
    { height: 85, low: false },
    { height: 70, low: false },
  ];
}
