import { Component, ElementRef, OnDestroy, afterNextRender, inject } from '@angular/core';
import { Motion, type MotionApi } from './motion';

/**
 * La reponse, et les deux champs qui font la difference.
 *
 * Le JSON est du texte selectionnable dans un <pre>, pas une image : c'est la forme exacte de
 * la reponse qu'un developpeur vient chercher, et un agent doit pouvoir la citer (issue #72).
 * Il est decoupe en lignes pour porter la coloration et les deux surlignages du design.
 */
@Component({
  selector: 'pr-output',
  template: `
    <section id="output" class="out">
      <div class="pr-shell out__inner">
        <div class="out__intro">
          <div class="pr-kicker">The output</div>
          <h2 class="pr-h2">Every chunk tells you how much to trust it.</h2>
          <p class="pr-lede">Uncertain content is flagged instead of silently indexed.</p>
        </div>

        <div class="out__grid">
          <div class="out__json">
            <div class="out__json-head pr-mono">
              <span>POST /api/v1/parse</span>
              <span class="out__ok">200 OK</span>
            </div>
            <div class="pr-scroll-x">
              <div class="code pr-mono">
                <div class="p">&#123;</div>
                <div class="i1"><span class="k">"document_id"</span><span class="p">: </span><span class="s">"doc_9f3c1a7b"</span><span class="p">,</span></div>
                <div class="i1"><span class="k">"pages"</span><span class="p">: </span><span class="n">12</span><span class="p">,</span></div>
                <div class="i1"><span class="k">"processing_ms"</span><span class="p">: </span><span class="n">1843</span><span class="p">,</span></div>
                <div class="i1"><span class="k">"chunks"</span><span class="p">: [</span></div>
                <div class="i2 p">&#123;</div>
                <div class="i3"><span class="k">"text"</span><span class="p">: </span><span class="s">"Retrieval-augmented generation..."</span><span class="p">,</span></div>
                <div class="i3"><span class="k">"type"</span><span class="p">: </span><span class="s">"PARAGRAPH"</span><span class="p">,</span></div>
                <div class="i3"><span class="k">"page"</span><span class="p">: </span><span class="n">1</span><span class="p">,</span></div>
                <div class="i3 hl hl--conf"><span class="hl__key">"confidence"</span><span class="p">: </span><span class="hl__big">0.94</span><span class="p">,</span></div>
                <div class="i3"><span class="k">"fallback_used"</span><span class="p">: </span><span class="n">false</span><span class="p">,</span></div>
                <div class="i3 hl hl--flag"><span class="hl__key hl__key--blue">"manual_review_needed"</span><span class="p">: </span><span class="hl__blue">false</span></div>
                <div class="i2 p">&#125;</div>
                <div class="i1 p">]</div>
                <div class="p">&#125;</div>
              </div>
            </div>
          </div>

          <div class="out__fields">
            <div class="field field--conf">
              <div class="field__row">
                <div class="field__name pr-mono">confidence</div>
                <div class="field__val pr-mono">0.94</div>
              </div>
              <p class="field__desc">Resolved per chunk, 0 to 1.</p>
              <div class="meter"><div class="meter__fill" style="width: 94%"></div></div>
              <div class="field__tag pr-mono">safe to index</div>
            </div>

            <div class="field field--flag">
              <div class="field__name field__name--blue pr-mono">manual_review_needed</div>
              <p class="field__desc field__desc--gap">The field to filter on.</p>

              <div class="low">
                <div class="low__row pr-mono">
                  <span>chunk_003</span>
                  <span class="low__val">0.41</span>
                </div>
                <div class="meter meter--sm"><div class="meter__fill" style="width: 41%"></div></div>
                <div class="low__route pr-mono">
                  <span class="low__flag">manual_review_needed: <span class="low__true">true</span></span>
                  <span class="low__arrow" aria-hidden="true">&rarr;</span>
                  <span class="low__box">manual review</span>
                </div>
              </div>
            </div>

            <div class="field field--table">
              <div class="field__name field__name--plain pr-mono">table_json</div>
              <p class="field__desc">
                Headers and rows, on <span class="pr-mono inline-code">TABLE</span> chunks.
              </p>
            </div>
          </div>
        </div>
      </div>
    </section>
  `,
  styles: `
    .out {
      background: var(--pr-surface);
      border-top: 1px solid var(--pr-hairline);
      border-bottom: 1px solid var(--pr-hairline);
    }

    .out__inner { padding-block: 96px; }

    .out__intro {
      display: flex;
      flex-direction: column;
      gap: 14px;
      max-width: 52ch;
      margin-bottom: 40px;
    }

    .out__grid {
      display: flex;
      gap: 32px;
      align-items: flex-start;
      flex-wrap: wrap;
    }

    .out__json {
      flex: 1 1 500px;
      min-width: 0;
      border: 1px solid var(--pr-ink);
      background: var(--pr-paper);
    }

    .out__json-head {
      display: flex;
      align-items: center;
      gap: 14px;
      padding: 12px 20px;
      background: var(--pr-ink);
      font-size: 14px;
      color: var(--pr-surface);
    }

    .out__ok {
      font-size: 13px;
      color: var(--pr-on-dark-muted);
      margin-left: auto;
    }

    .code {
      padding: 24px 26px;
      font-size: 15px;
      line-height: 1.8;
    }

    .i1 { padding-left: 18px; }
    .i2 { padding-left: 36px; }
    .i3 { padding-left: 54px; }
    .p { color: var(--pr-muted); }
    .k { color: var(--pr-ink-soft); }
    .s { color: var(--pr-ochre-text); }
    .n { color: var(--pr-ink); font-weight: 500; }

    .hl {
      margin: 3px -26px;
      padding-right: 26px;
    }

    .hl__key { padding-left: 26px; font-weight: 700; }

    .hl--conf {
      background: rgb(140 47 38 / 10%);
      box-shadow: inset 4px 0 0 var(--pr-red);

      .hl__key { color: var(--pr-red); }
    }

    .hl__big {
      color: var(--pr-red);
      font-weight: 700;
      font-size: 17px;
    }

    .hl--flag {
      background: rgb(47 74 110 / 10%);
      box-shadow: inset 4px 0 0 var(--pr-blue);
    }

    .hl__key--blue { color: var(--pr-blue); }
    .hl__blue { color: var(--pr-blue); font-weight: 700; }

    .out__fields {
      flex: 1 1 280px;
      min-width: 0;
      display: flex;
      flex-direction: column;
      gap: 22px;
    }

    .field--conf { border-top: 2px solid var(--pr-red); padding-top: 14px; }
    .field--flag { border-top: 2px solid var(--pr-blue); padding-top: 14px; }
    .field--table { border-top: 1px solid var(--pr-hairline); padding-top: 14px; }

    .field__row {
      display: flex;
      align-items: baseline;
      gap: 12px;
      margin-bottom: 6px;
    }

    .field__name {
      font-size: 16px;
      font-weight: 700;
      color: var(--pr-red);
      margin-bottom: 6px;
    }

    .field__name--blue { color: var(--pr-blue); }
    .field__name--plain { color: var(--pr-ink); font-weight: 500; }

    .field__row .field__name { margin-bottom: 0; }

    .field__val {
      margin-left: auto;
      font-size: 22px;
      font-weight: 700;
      color: var(--pr-red);
    }

    .field__desc {
      margin: 0;
      font-size: 17px;
      line-height: 1.5;
      color: var(--pr-ink-soft);
    }

    .field--conf .field__desc { margin-bottom: 10px; }
    .field__desc--gap { margin-bottom: 14px; }

    .meter {
      height: 8px;
      background: var(--pr-hairline-soft);
      overflow: hidden;
    }

    .meter--sm { height: 6px; }

    .meter__fill {
      height: 100%;
      background: var(--pr-red);
    }

    .field__tag {
      margin-top: 10px;
      font-size: 12px;
      letter-spacing: 0.12em;
      text-transform: uppercase;
      color: var(--pr-blue);
    }

    .low {
      border: 1px solid var(--pr-hairline);
      background: var(--pr-paper);
      padding: 14px 16px;
      display: flex;
      flex-direction: column;
      gap: 10px;
    }

    .low__row {
      display: flex;
      align-items: baseline;
      gap: 10px;
      font-size: 14px;
      color: var(--pr-ink-soft);
    }

    .low__val {
      margin-left: auto;
      font-weight: 700;
      color: var(--pr-red);
    }

    .low__route {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
      font-size: 13px;
    }

    .low__flag { color: var(--pr-ink-soft); }
    .low__true { color: var(--pr-red); font-weight: 700; }
    .low__arrow { color: var(--pr-red); }

    .low__box {
      border: 1px solid var(--pr-red);
      color: var(--pr-red);
      letter-spacing: 0.1em;
      text-transform: uppercase;
      padding: 4px 9px;
      font-size: 11px;
      font-weight: 700;
    }

    .inline-code { font-size: 15px; }
  `,
})
export class Output implements OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly motion = inject(Motion);
  private api: MotionApi | null = null;
  private timeline?: gsap.core.Timeline;

  constructor() {
    afterNextRender(async () => {
      this.api = await this.motion.load();
      if (!this.api) {
        return;
      }
      this.motion.onEnter(this.api, this.host.nativeElement.querySelector('.out__grid'), () =>
        this.run(),
      );
      this.linkHovers();
    });
  }

  ngOnDestroy(): void {
    this.timeline?.kill();
  }

  private q<T extends Element>(selector: string): T | null {
    return this.host.nativeElement.querySelector<T>(selector);
  }

  /**
   * Le score se resout **par paliers visibles** (0.31, 0.58, 0.76, 0.94) et non par une rampe
   * lisse. C'est un choix du design qui dit quelque chose de juste sur le produit : la confiance
   * n'est pas une jauge qui monte, c'est une suite de verifications qui aboutissent.
   */
  private run(): void {
    const api = this.api;
    if (!api) {
      return;
    }
    const { gsap } = api;
    const high = this.q<HTMLElement>('.field--conf .field__val');
    const inline = this.q<HTMLElement>('.hl--conf .hl__big');
    const low = this.q<HTMLElement>('.low__val');
    const bar = this.q<HTMLElement>('.field--conf .meter__fill');
    const barLow = this.q<HTMLElement>('.low .meter__fill');
    const flagHigh = this.q('.hl--flag .hl__blue');
    const safe = this.q('.field__tag');
    const flagLow = this.q('.low__true');
    const arrow = this.q('.low__arrow');
    const box = this.q('.low__box');

    this.timeline?.kill();
    const tl = gsap.timeline();
    this.timeline = tl;

    gsap.set([flagHigh, flagLow, arrow, box].filter(Boolean), { opacity: 0 });
    gsap.set([bar, barLow].filter(Boolean), { width: '0%' });
    gsap.set(safe, { opacity: 0.25 });

    const write = (value: number) => {
      const text = value.toFixed(2);
      if (high) high.textContent = text;
      if (inline) inline.textContent = text;
    };

    const counter = { v: 0 };
    const steps: readonly (readonly [number, number])[] = [
      [0.31, 0.28],
      [0.58, 0.3],
      [0.76, 0.26],
      [0.94, 0.34],
    ];
    steps.forEach(([value, duration], i) => {
      const at = 0.15 + i * 0.42;
      tl.to(counter, { v: value, duration, ease: 'power2.out', onUpdate: () => write(counter.v) }, at).to(
        bar,
        { width: `${value * 100}%`, duration, ease: 'power2.out' },
        at,
      );
    });

    tl.fromTo(
      high,
      { scale: 1 },
      { scale: 1.16, duration: 0.15, yoyo: true, repeat: 1, ease: 'power2.out', transformOrigin: '100% 50%' },
      1.95,
    )
      .to(flagHigh, { opacity: 1, duration: 0.32 }, 2.15)
      .to(safe, { opacity: 1, duration: 0.4, ease: 'power2.out' }, 2.35);

    // le chunk incertain : il se resout bas, puis il est ecarte de l'index
    const lowCounter = { v: 0 };
    tl.to(
      lowCounter,
      {
        v: 0.41,
        duration: 0.75,
        ease: 'power2.out',
        onUpdate: () => {
          if (low) low.textContent = lowCounter.v.toFixed(2);
        },
      },
      2.7,
    )
      .to(barLow, { width: '41%', duration: 0.75, ease: 'power2.out' }, 2.7)
      .to(flagLow, { opacity: 1, duration: 0.3 }, 3.75)
      .fromTo(arrow, { opacity: 0, x: -8 }, { opacity: 1, x: 0, duration: 0.32, ease: 'power2.out' }, 3.95)
      .fromTo(
        box,
        { opacity: 0, x: -14, scale: 0.96 },
        { opacity: 1, x: 0, scale: 1, duration: 0.45, ease: 'power3.out' },
        4.1,
      )
      .fromTo(
        box,
        { boxShadow: '0 0 0 0 rgba(140,47,38,0)' },
        { boxShadow: '0 0 0 3px rgba(140,47,38,.14)', duration: 0.3, yoyo: true, repeat: 1 },
        4.55,
      );
  }

  /**
   * Survoler une etiquette de la colonne de droite met en avant la ligne correspondante du JSON.
   * C'est le seul endroit de la page ou le survol enseigne quelque chose : il relie un nom de
   * champ a sa place dans la reponse.
   */
  private linkHovers(): void {
    const api = this.api;
    if (!api) {
      return;
    }
    const { gsap } = api;
    const pairs: readonly (readonly [string, string, string, string])[] = [
      ['.field--conf', '.hl--conf', 'rgba(140,47,38,.2)', 'rgba(140,47,38,.1)'],
      ['.field--flag', '.hl--flag', 'rgba(47,74,110,.2)', 'rgba(47,74,110,.1)'],
    ];
    for (const [labelSelector, rowSelector, hover, base] of pairs) {
      const label = this.q<HTMLElement>(labelSelector);
      const row = this.q<HTMLElement>(rowSelector);
      if (!label || !row) {
        continue;
      }
      label.addEventListener('mouseenter', () =>
        gsap.to(row, { backgroundColor: hover, x: 3, duration: 0.28, ease: 'power2.out' }),
      );
      label.addEventListener('mouseleave', () =>
        gsap.to(row, { backgroundColor: base, x: 0, duration: 0.35, ease: 'power2.out' }),
      );
    }
  }
}
