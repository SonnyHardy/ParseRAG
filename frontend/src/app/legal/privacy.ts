import { Component } from '@angular/core';
import { LegalPage } from './legal-page';
import { RAPIDAPI_URL } from '../landing/rapidapi';

/**
 * Protection des donnees.
 *
 * Le texte decoule de l'engagement deja pris dans les conditions du listing : « not stored, not
 * logged, never used for training ». Ce n'est pas une formule, c'est une contrainte technique
 * verifiable : l'enregistrement des corps de requete est desactive sur la passerelle RapidAPI
 * (runbook, etape 4), et le pipeline traite le PDF en memoire.
 *
 * **A completer a l'issue #74** : la section « Analytics » decrit aujourd'hui l'absence d'outil de
 * mesure. Le jour ou l'analytique sans cookie est posee, c'est ici qu'elle se declare, et cette
 * page doit etre relue avant la mise en ligne de la mesure, pas apres.
 */
@Component({
  selector: 'pr-privacy',
  imports: [LegalPage],
  template: `
    <pr-legal-page>
      <h1>Privacy and data protection</h1>
      <p class="legal__updated">Last updated 2 September 2026</p>

      <h2>Documents you send to the API</h2>
      <p>
        A PDF you upload is processed in memory for the duration of the request and discarded when
        the response is returned. It is <strong>not stored</strong>, its content is
        <strong>not logged</strong>, and it is <strong>never used for training</strong>. Request and
        response body logging is disabled on the marketplace gateway that fronts the API.
      </p>
      <p>
        Pages with no text layer, such as scans, are sent to a third-party vision model provider for
        transcription during that same request, and are not retained beyond it. Chunks produced this
        way are marked <code>fallback_used</code> in the response, so you always know which pages
        left the pipeline.
      </p>
      <p>
        What is kept is operational and does not contain your documents: request counts, durations,
        error codes, and the identifier of the calling subscription.
      </p>

      <h2>Your subscription</h2>
      <p>
        Subscriptions, API keys, quotas and billing are handled by the
        <a [href]="rapidapi" target="_blank" rel="noopener">RapidAPI marketplace</a>, not by
        ParseRAG. We never see your payment details. Refer to RapidAPI's privacy policy for how they
        process your account data.
      </p>

      <h2>This website</h2>
      <p>
        This site is a set of static pages. It sets no cookies, runs no advertising or tracking
        script, and asks for no personal information: there is no form, no account and no newsletter.
        Nothing you do here is associated with an identity.
      </p>
      <p>
        Web fonts are currently served by Google Fonts, which means your browser makes a request to
        <code>fonts.googleapis.com</code> and <code>fonts.gstatic.com</code> when the page loads.
        Self-hosting them, which would remove that request entirely, is planned.
      </p>

      <h2>Analytics</h2>
      <p>
        No analytics is in place at the time of writing. If measurement is added, it will be
        cookie-free and aggregate only, and this page will say so before it goes live rather than
        after.
      </p>

      <h2>Contact</h2>
      <p>
        Questions about data handling go to the Discussions tab of the
        <a [href]="rapidapi" target="_blank" rel="noopener">API listing</a>.
      </p>
    </pr-legal-page>
  `,
})
export class Privacy {
  protected readonly rapidapi = RAPIDAPI_URL;
}
