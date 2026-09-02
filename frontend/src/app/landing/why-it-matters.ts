import { Component } from '@angular/core';

/** Les deux chaines de consequences, cassee et saine, face a face. */
@Component({
  selector: 'pr-why-it-matters',
  template: `
    <section class="wm">
      <div class="pr-shell wm__inner">
        <h2 class="pr-h2 wm__title">Bad extraction becomes bad retrieval.</h2>

        <div class="wm__grid">
          <div class="chain chain--bad">
            <div class="chain__head pr-mono">Broken extraction</div>
            <ol class="chain__body pr-mono">
              @for (step of broken; track step; let last = $last) {
                <li class="chain__step" [class.chain__step--last]="last">{{ step }}</li>
                @if (!last) {
                  <li class="chain__arrow" aria-hidden="true">&darr;</li>
                }
              }
            </ol>
          </div>

          <div class="chain chain--good">
            <div class="chain__head chain__head--dark pr-mono">ParseRAG extraction</div>
            <ol class="chain__body pr-mono">
              @for (step of clean; track step; let last = $last) {
                <li class="chain__step" [class.chain__step--last]="last">{{ step }}</li>
                @if (!last) {
                  <li class="chain__arrow" aria-hidden="true">&darr;</li>
                }
              }
            </ol>
          </div>
        </div>

        <div class="wm__close">
          <p class="wm__punch">Bad chunks in. Bad context out.</p>
          <p class="wm__note">Your RAG system can only retrieve what you give it.</p>
        </div>
      </div>
    </section>
  `,
  styles: `
    .wm {
      background: var(--pr-surface);
      border-top: 1px solid var(--pr-hairline);
      border-bottom: 1px solid var(--pr-hairline);
    }

    .wm__inner { padding-block: 88px; }

    .wm__title {
      margin-bottom: 40px;
      max-width: 24ch;
      font-size: clamp(28px, 3.4vw, 44px);
    }

    .wm__grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(280px, 100%), 1fr));
      gap: 20px;
      margin-bottom: 32px;
    }

    .chain {
      box-sizing: border-box;
      display: flex;
      flex-direction: column;
      background: var(--pr-paper);
    }

    .chain--bad { border: 1px solid var(--pr-hairline); }
    .chain--good { border: 1px solid var(--pr-ink); }

    .chain__head {
      padding: 12px 20px;
      border-bottom: 1px solid var(--pr-hairline);
      font-size: 12px;
      letter-spacing: 0.14em;
      text-transform: uppercase;
      color: var(--pr-red);
    }

    .chain__head--dark {
      background: var(--pr-ink);
      border-bottom: 0;
      color: var(--pr-amber);
    }

    /* Une liste ordonnee et non des div : la sequence est le propos de ce bloc, et c'est ce qui
       la rend lisible par un lecteur d'ecran comme par un extracteur. */
    .chain__body {
      list-style: none;
      margin: 0;
      padding: 22px 20px;
      display: flex;
      flex-direction: column;
      gap: 10px;
      font-size: 16px;
    }

    .chain--bad .chain__body { color: var(--pr-red); }
    .chain--good .chain__body { color: var(--pr-ink); }

    .chain__step--last { font-weight: 700; }

    .chain--bad .chain__arrow { color: var(--pr-red); }
    .chain--good .chain__arrow { color: var(--pr-ochre); }

    .wm__close {
      display: flex;
      gap: 36px;
      align-items: baseline;
      flex-wrap: wrap;
    }

    .wm__punch {
      margin: 0;
      font-size: clamp(22px, 2.6vw, 32px);
      font-weight: 500;
      letter-spacing: -0.018em;
      line-height: 1.25;
    }

    .wm__note {
      margin: 0;
      font-size: 17px;
      line-height: 1.55;
      color: var(--pr-muted);
      max-width: 38ch;
    }
  `,
})
export class WhyItMatters {
  protected readonly broken = [
    'interleaved text',
    'noisy chunks',
    'polluted embeddings',
    'wrong answers',
  ];

  protected readonly clean = [
    'real reading order',
    'clean chunks',
    'faithful embeddings',
    'answers you can cite',
  ];
}
