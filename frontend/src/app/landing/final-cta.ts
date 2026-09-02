import { Component } from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { RAPIDAPI_URL } from './rapidapi';

/** Le dernier appel a l'action, sur fond sombre. */
@Component({
  selector: 'pr-final-cta',
  imports: [ButtonModule],
  template: `
    <section id="rapidapi" class="fc">
      <div class="pr-shell fc__inner">
        <img
          src="brand/wordmark-paper.png"
          alt="ParseRAG"
          width="632"
          height="208"
          loading="lazy"
          class="fc__mark"
        />
        <h2 class="fc__title">Give your RAG pipeline cleaner input.</h2>
        <p class="fc__sub">Subscribe on RapidAPI, grab your key, send your first PDF.</p>
        <a pButton class="pr-cta pr-cta--lg fc__cta" [href]="rapidapi" target="_blank" rel="noopener">
          Try ParseRAG on RapidAPI
          <span class="pr-cta__arrow pr-mono" aria-hidden="true">&rarr;</span>
        </a>
      </div>
    </section>
  `,
  styles: `
    .fc { background: var(--pr-dark); }

    .fc__inner {
      padding-block: 104px;
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 26px;
    }

    .fc__mark {
      display: block;
      height: 36px;
      width: auto;
      aspect-ratio: 632 / 208;
      flex: none;
    }

    .fc__title {
      margin: 0;
      font-size: clamp(32px, 4.6vw, 58px);
      font-weight: 600;
      letter-spacing: -0.032em;
      line-height: 1.04;
      color: var(--pr-on-dark);
      max-width: 22ch;
      text-wrap: balance;
    }

    .fc__sub {
      margin: 0;
      font-size: 20px;
      line-height: 1.55;
      color: var(--pr-on-dark-dim);
      max-width: 44ch;
    }

    .fc__cta { margin-top: 6px; }

  `,
})
export class FinalCta {
  protected readonly rapidapi = RAPIDAPI_URL;
}
