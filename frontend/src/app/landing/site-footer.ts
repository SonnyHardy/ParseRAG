import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { RAPIDAPI_URL } from './rapidapi';

/**
 * Pied de page.
 *
 * Le design pointait « Legal » vers #start, faute de page a atteindre. Le lien est remplace par
 * deux vraies destinations, /terms et /privacy, plutot que reproduit : un lien qui ne mene pas
 * ou il annonce est pire qu'un lien absent.
 *
 * routerLink et non href sur les liens internes : la navigation reste interne, sans
 * rechargement. Le lien RapidAPI, lui, est bien un href externe avec rel="noopener".
 *
 * **Le lien vers la documentation n'existe qu'ici**, et pas dans la barre de navigation. Cette
 * page est de la reference : elle sert qui integre, pas qui decide, et la barre haute est le
 * chemin de conversion (issue #72). Un lien en pied de page suffit a la rendre trouvable par un
 * lecteur comme par un robot, sans la mettre en travers de l'abonnement.
 */
@Component({
  selector: 'pr-site-footer',
  imports: [RouterLink],
  template: `
    <footer class="ft">
      <div class="pr-shell ft__inner">
        <div class="ft__brand">
          <picture>
            <source srcset="brand/favicon-32.webp" type="image/webp" />
            <img src="brand/favicon-32.png" alt="" width="32" height="32" loading="lazy" class="ft__mark" />
          </picture>
          <span class="ft__copy">&copy; {{ year }} ParseRAG</span>
        </div>
        <nav class="ft__links" aria-label="Footer">
          <a [href]="rapidapi" target="_blank" rel="noopener">RapidAPI</a>
          <a routerLink="/" fragment="start">Quick start</a>
          <a routerLink="/documentation">Documentation</a>
          <a routerLink="/terms">Terms of Use</a>
          <a routerLink="/privacy">Privacy</a>
        </nav>
      </div>
    </footer>
  `,
  styles: `
    .ft {
      background: var(--pr-dark-deep);
      border-top: 1px solid var(--pr-dark-raised);
    }

    .ft__inner {
      padding-block: 32px;
      display: flex;
      align-items: center;
      gap: 28px;
      flex-wrap: wrap;
    }

    .ft__brand {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .ft__mark {
      display: block;
      width: 22px;
      height: 22px;
    }

    .ft__copy {
      font-size: 15px;
      color: var(--pr-on-dark-muted);
    }

    .ft__links {
      display: flex;
      align-items: center;
      gap: 24px;
      flex-wrap: wrap;
      margin-left: auto;
      font-size: 15px;

      a {
        color: var(--pr-on-dark-dim);

        &:hover {
          color: var(--pr-on-dark-soft);
          text-decoration: none;
        }
      }
    }
  `,
})
export class SiteFooter {
  protected readonly rapidapi = RAPIDAPI_URL;
  // Fige au prerendu : la page etant statique, une annee calculee cote client divergerait du HTML
  // servi et declencherait une divergence d'hydratation au passage du 31 decembre.
  protected readonly year = 2026;
}
