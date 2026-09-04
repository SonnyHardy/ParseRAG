import { Component } from '@angular/core';
import { SiteHeader } from '../landing/site-header';
import { SiteFooter } from '../landing/site-footer';

/**
 * Gabarit commun aux deux pages legales : la meme barre, le meme pied, et une colonne de texte.
 *
 * Un composant plutot qu'un copier-coller entre terms et privacy : ces deux pages doivent
 * rester visuellement identiques, et deux gabarits separes divergeraient au premier ajustement.
 *
 * Elles ne portent aucune animation. Ce sont des textes qu'on vient lire, souvent parce qu'on a
 * une question precise ; un titre qui apparait en fondu n'y aide personne.
 */
@Component({
  selector: 'pr-legal-page',
  imports: [SiteHeader, SiteFooter],
  template: `
    <pr-site-header />
    <main class="pr-shell legal">
      <ng-content />
    </main>
    <pr-site-footer />
  `,
  styles: `
    .legal {
      padding-block: 72px 96px;
      max-width: 68ch;
      font-size: 17px;
      line-height: 1.65;
      color: var(--pr-ink-soft);
    }

    /* :host ::ng-deep est ici le bon outil et non un contournement : le contenu arrive par
       ng-content, il porte donc l'identite du composant parent et les styles de celui-ci ne
       l'atteignent pas. La portee reste bornee a .legal. */
    :host ::ng-deep .legal {
      h1 {
        margin: 0 0 8px;
        font-size: clamp(32px, 4vw, 46px);
        font-weight: 600;
        letter-spacing: -0.03em;
        line-height: 1.08;
        color: var(--pr-ink);
      }

      h2 {
        margin: 44px 0 12px;
        font-size: 21px;
        font-weight: 600;
        letter-spacing: -0.01em;
        color: var(--pr-ink);
      }

      p { margin: 0 0 16px; }

      ul {
        margin: 0 0 16px;
        padding-left: 22px;
      }

      li { margin-bottom: 8px; }

      code {
        font-family: var(--pr-font-mono);
        font-size: 15px;
        color: var(--pr-ochre-text);
      }

      .legal__updated {
        font-family: var(--pr-font-mono);
        font-size: 13px;
        color: var(--pr-muted);
        margin-bottom: 40px;
      }
    }
  `,
})
export class LegalPage {}
