import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SiteHeader } from '../landing/site-header';
import { SiteFooter } from '../landing/site-footer';
import { SNIPPETS } from '../landing/code-snippets';
import { API_ERRORS } from '../landing/errors-data';
import { PLANS } from '../landing/plans-data';
import { RAPIDAPI_URL } from '../landing/rapidapi';
import { Seo } from '../seo/seo';
import { pageFor } from '../seo/site';

/**
 * La documentation de l'endpoint (issue #72).
 *
 * **Pourquoi une page et pas une section de la vitrine.** Une premiere version posait ce contenu
 * sur la page d'accueil, ce qui etait une erreur de destination : dix codes d'erreur HTTP ne
 * servent pas un visiteur qui decide, ils servent quelqu'un qui integre. La page d'accueil n'a
 * qu'une fonction, convertir une visite en abonnement, et l'allonger de contenu de reference
 * revenait a payer 6,7 Ko et 99 elements de DOM sur le chemin de conversion, alors que le blocage
 * residuel de cette page est justement le temps de blocage (issue #70).
 *
 * Cette page est donc **hors du chemin de conversion** — aucun lien depuis la barre de navigation,
 * un seul depuis le pied de page — mais bien dans le sitemap et dans `llms.txt`. C'est exactement
 * ce que cherche l'issue #72 : les faits restent accessibles a un agent et a un developpeur qui
 * les cherche, sans peser sur la page qui doit convaincre.
 *
 * **Le contenu vient des memes modules que le reste.** Les exemples de `code-snippets.ts`, les
 * plans de `plans-data.ts`, les erreurs de `errors-data.ts` : ceux que la page d'accueil affiche
 * et que `llms-full.txt` publie. Une documentation qui divergerait de la page serait pire
 * qu'absente.
 */
@Component({
  selector: 'pr-documentation',
  imports: [SiteHeader, SiteFooter, RouterLink],
  template: `
    <pr-site-header />
    <main class="pr-shell doc">
      <header class="doc__head">
        <div class="pr-kicker">Documentation</div>
        <h1 class="doc__title">Using the ParseRAG endpoint</h1>
        <p class="doc__lede">
          One endpoint, one upload. This page is the reference: the request, the response, the
          limits and every error the API can return.
        </p>
      </header>

      <section class="doc__section">
        <h2>The request</h2>
        <p>
          Send the PDF as a single <code class="pr-mono">file</code> part of a
          <code class="pr-mono">multipart/form-data</code> request. Your RapidAPI key goes in the
          <code class="pr-mono">X-RapidAPI-Key</code> header; the marketplace proxy authenticates
          the call and applies your plan.
        </p>
        <div class="pr-scroll-x doc__panel">
          <pre class="doc__pre pr-mono"><code>POST https://parserag.p.rapidapi.com/api/v1/parse
Content-Type: multipart/form-data
X-RapidAPI-Key: YOUR_KEY</code></pre>
        </div>
      </section>

      <section class="doc__section">
        <h2>Worked examples</h2>
        @for (snippet of snippets; track snippet.id) {
          <h3 class="doc__example">{{ snippet.label }}</h3>
          <div class="pr-scroll-x doc__panel">
            <pre class="doc__pre pr-mono"><code>{{ snippet.code }}</code></pre>
          </div>
        }
      </section>

      <section class="doc__section">
        <h2>The response</h2>
        <p>
          A document summary and a list of chunks. Every chunk carries
          <code class="pr-mono">type</code>, <code class="pr-mono">page</code>,
          <code class="pr-mono">text</code>, <code class="pr-mono">confidence</code>,
          <code class="pr-mono">manual_review_needed</code> and
          <code class="pr-mono">fallback_used</code>. Chunks of type
          <code class="pr-mono">TABLE</code> additionally carry
          <code class="pr-mono">table_json</code> with <code class="pr-mono">headers</code>,
          <code class="pr-mono">rows</code> and an optional <code class="pr-mono">caption</code>, so
          extracting a table needs no separate endpoint or parameter.
        </p>
        <p>
          The full machine contract is published as
          <a href="openapi.json">openapi.json</a>, with the marketplace base URL and authentication
          already applied.
        </p>
      </section>

      <section class="doc__section">
        <h2>Limits</h2>
        <p>
          Requests per month and per minute are metered by RapidAPI. ParseRAG enforces one limit of
          its own, pages per document, because the marketplace counts requests and cannot see how
          big a job is.
        </p>
        <ul class="doc__list">
          <li><strong>50 MB per file</strong>, on every plan.</li>
          <li>
            <strong>Pages per document</strong>:
            {{ pageLimits }}. Over the cap, the call is rejected with
            <code class="pr-mono">DOCUMENT_TOO_LONG</code> rather than truncated: a silently
            half-parsed document is worse than a clear refusal.
          </li>
        </ul>
        <p>
          The full grid is on the <a routerLink="/" fragment="plans">plans section</a>, and prices
          are on the <a [href]="rapidapi" target="_blank" rel="noopener">RapidAPI listing</a>.
        </p>
      </section>

      <section class="doc__section">
        <h2 id="errors">Errors</h2>
        <p>
          Every error comes back in the same shape. Branch on
          <code class="pr-mono">error</code>, which is stable across releases;
          <code class="pr-mono">message</code> is written for humans and may change.
        </p>
        <div class="pr-scroll-x doc__panel">
          <pre class="doc__pre pr-mono"><code>&#123; "error": "FILE_TOO_LARGE", "message": "File size 63.2 MB exceeds the 50 MB limit.", "status": 413 &#125;</code></pre>
        </div>
        <div class="pr-scroll-x doc__table-wrap">
          <table class="doc__table">
            <caption class="doc__caption pr-mono">
              Error codes returned by POST /api/v1/parse
            </caption>
            <thead>
              <tr>
                <th scope="col">Code</th>
                <th scope="col">HTTP</th>
                <th scope="col">When</th>
                <th scope="col">Retry?</th>
              </tr>
            </thead>
            <tbody>
              @for (error of errors; track $index) {
                <tr>
                  <th scope="row" class="pr-mono doc__code">{{ error.code }}</th>
                  <td class="pr-mono">{{ error.status }}</td>
                  <td>{{ error.when }}</td>
                  <td class="doc__retry">{{ error.retry }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>
    </main>
    <pr-site-footer />
  `,
  styles: `
    .doc {
      padding-block: 64px 96px;
      max-width: 82ch;
    }

    .doc__head {
      display: flex;
      flex-direction: column;
      gap: 14px;
      margin-bottom: 48px;
    }

    .doc__title {
      margin: 0;
      font-size: clamp(32px, 4vw, 46px);
      font-weight: 600;
      letter-spacing: -0.03em;
      line-height: 1.08;
    }

    .doc__lede {
      margin: 0;
      font-size: 19px;
      line-height: 1.55;
      color: var(--pr-ink-soft);
      max-width: 60ch;
    }

    .doc__section { margin-bottom: 56px; }

    .doc__section h2 {
      margin: 0 0 16px;
      font-size: 26px;
      font-weight: 600;
      letter-spacing: -0.02em;
    }

    .doc__example {
      margin: 28px 0 10px;
      font-size: 15px;
      font-weight: 600;
      letter-spacing: 0.06em;
      text-transform: uppercase;
      color: var(--pr-muted);
    }

    .doc__section p {
      margin: 0 0 14px;
      font-size: 17px;
      line-height: 1.65;
      color: var(--pr-ink-soft);
      max-width: 68ch;
    }

    .doc__list {
      margin: 0 0 14px;
      padding-left: 20px;
      font-size: 17px;
      line-height: 1.65;
      color: var(--pr-ink-soft);
      max-width: 68ch;
    }

    .doc__list li { margin-bottom: 8px; }

    .doc code { font-size: 0.92em; color: var(--pr-red); }

    .doc__panel {
      background: var(--pr-dark);
      border: 1px solid var(--pr-dark-border);
      margin-bottom: 14px;
    }

    /* Un vrai pre, avec de vrais retours a la ligne : c'est ce qu'un agent extrait et ce que
       copie un lecteur. Ici le texte vient d'une seule interpolation, donc les fins de ligne
       sont celles de la donnee elle-meme. */
    .doc__pre {
      margin: 0;
      padding: 22px 24px;
      font-size: 14px;
      line-height: 1.75;
      color: var(--pr-on-dark-dim);
      white-space: pre;
    }

    .doc__pre code {
      font-family: inherit;
      font-size: inherit;
      color: inherit;
    }

    .doc__table-wrap {
      border: 1px solid var(--pr-hairline);
      background: var(--pr-paper);
      margin-top: 20px;
    }

    .doc__table {
      width: 100%;
      border-collapse: collapse;
      text-align: left;
    }

    .doc__caption {
      caption-side: top;
      padding: 14px 18px;
      border-bottom: 1px solid var(--pr-hairline);
      background: var(--pr-surface);
      font-size: 12px;
      letter-spacing: 0.06em;
      color: var(--pr-muted);
      text-align: left;
    }

    .doc__table thead th {
      padding: 14px 18px;
      border-bottom: 1px solid var(--pr-hairline);
      font-family: var(--pr-font-mono);
      font-size: 12px;
      font-weight: 500;
      letter-spacing: 0.14em;
      text-transform: uppercase;
      color: var(--pr-muted);
      white-space: nowrap;
    }

    .doc__table tbody tr + tr th,
    .doc__table tbody tr + tr td {
      border-top: 1px solid var(--pr-hairline-soft);
    }

    .doc__table tbody th,
    .doc__table tbody td {
      padding: 14px 18px;
      font-size: 15px;
      line-height: 1.5;
      font-weight: 400;
      color: var(--pr-ink-soft);
      vertical-align: top;
    }

    .doc__code {
      color: var(--pr-red) !important;
      white-space: nowrap;
    }

    .doc__retry { white-space: nowrap; color: var(--pr-muted) !important; }

    @media (max-width: 640px) {
      .doc { padding-block: 48px 72px; }
      .doc__section { margin-bottom: 44px; }
      .doc__pre { padding: 18px; font-size: 13px; }
      .doc__table thead th { padding: 12px; font-size: 11px; letter-spacing: 0.08em; }
      .doc__table tbody th,
      .doc__table tbody td { padding: 12px; font-size: 14px; }
      .doc__caption { padding: 12px; }
    }
  `,
})
export class Documentation {
  protected readonly snippets = SNIPPETS;
  protected readonly errors = API_ERRORS;
  protected readonly rapidapi = RAPIDAPI_URL;

  /** « 100 on BASIC, 300 on PRO, … », compose depuis la grille plutot que recopie. */
  protected readonly pageLimits = PLANS.map((plan) => `${plan.pages} on ${plan.name}`).join(', ');

  constructor() {
    inject(Seo).apply(pageFor('/documentation'));
  }
}
