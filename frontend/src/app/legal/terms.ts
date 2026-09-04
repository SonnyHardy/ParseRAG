import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LegalPage } from './legal-page';
import { RAPIDAPI_URL } from '../landing/rapidapi';

/**
 * Conditions d'utilisation.
 *
 * **Le texte est celui publie sur le listing RapidAPI, mot pour mot.** Les cinq sections Service,
 * Your content, Accuracy, Acceptable use, Availability et Contact n'ont pas ete reecrites : un
 * client qui lit des conditions differentes ici et sur la fiche a raison de s'inquieter, et c'est
 * un ecart qui ne se voit jamais depuis le depot.
 *
 * Le seul ajout est le renvoi vers la page de confidentialite, qui detaille ce que « not stored,
 * not logged » veut dire techniquement. Il est place en dehors des sections reprises pour que la
 * frontiere reste nette.
 *
 * Toute modification ici se reporte sur le listing, et reciproquement. La source de reference est
 * docs/rapidapi-listing-setup.md, etape 3.
 */
@Component({
  selector: 'pr-terms',
  imports: [LegalPage, RouterLink],
  template: `
    <pr-legal-page>
      <h1>ParseRAG: Terms of Use</h1>
      <p class="legal__updated">
        These are the terms published on the RapidAPI listing. Last updated 2 September 2026.
      </p>

      <h2>Service</h2>
      <p>
        ParseRAG accepts a PDF document and returns its extracted content as JSON chunks. The
        service is provided on an "as is" basis, without warranty of any kind.
      </p>

      <h2>Your content</h2>
      <p>
        You keep all rights to the documents you upload. Documents are processed in memory for the
        duration of the request. They are not stored, not logged, and never used for training. Pages
        that require optical character recognition are sent to a third-party vision model provider
        for transcription during that same request, and not retained beyond it.
      </p>

      <h2>Accuracy</h2>
      <p>
        Extraction quality depends on the source document. Every chunk carries a
        <code>confidence</code> score and a <code>manual_review_needed</code> flag; you are
        responsible for how you use low-confidence output. ParseRAG is not liable for decisions made
        on extracted content.
      </p>

      <h2>Acceptable use</h2>
      <p>
        Do not upload documents you are not entitled to process. Do not attempt to circumvent plan
        quotas, rate limits, or the page limit per document.
      </p>

      <h2>Availability</h2>
      <p>
        No uptime guarantee is offered on any plan. Quotas, rate limits and prices may change, with
        notice given through the RapidAPI marketplace.
      </p>

      <h2>Contact</h2>
      <p>
        Use the Discussions tab of the
        <a [href]="rapidapi" target="_blank" rel="noopener">API listing</a>.
      </p>

      <h2>See also</h2>
      <p>
        The <a routerLink="/privacy">privacy page</a> describes in practical terms what "not stored,
        not logged" means, and what this website itself collects.
      </p>
    </pr-legal-page>
  `,
})
export class Terms {
  protected readonly rapidapi = RAPIDAPI_URL;
}
