import { Component, ElementRef, afterNextRender, inject } from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { RAPIDAPI_URL } from './rapidapi';
import { Motion } from './motion';
import { PLANS, type Plan } from './plans-data';

/**
 * Le tableau des plans.
 *
 * Section 6.7 du brief de design (docs/landing-page-design-brief.md), absente du canevas rendu
 * comme la FAQ. Elle est reprise ici parce que c'est la derniere question qu'un lecteur se pose
 * avant de cliquer, et la seule que la page ne repondait pas.
 *
 * **Aucun prix, et ce n'est pas un oubli.** Le brief l'interdit explicitement : les prix vivent
 * sur le listing RapidAPI, qui est le systeme qui les facture. Recopies ici, ils se perimeraient
 * au premier ajustement de grille sans que rien ne le signale, et une page vitrine qui annonce un
 * tarif faux coute plus cher que celle qui n'en annonce aucun. D'ou le CTA en fin de section : le
 * lecteur qui vient de lire les limites veut le prix, et il n'existe qu'a un endroit.
 *
 * **Les noms sont ceux du marketplace** (BASIC/PRO/ULTRA/MEGA), pas les noms internes
 * (FREE/STARTER/PRO/SCALE). Le client ne connait que les premiers, et le faux ami est reel : PRO
 * chez RapidAPI vaut STARTER chez nous. La correspondance vit dans
 * `parserag.rapidapi.plan-mapping` (backend/src/main/resources/application.yaml) et n'a aucune
 * raison d'apparaitre ici.
 *
 * **Un vrai `<table>`**, avec `<caption>`, `<th scope>` et en-tetes de ligne : c'est la seule
 * forme qu'un lecteur d'ecran annonce correctement, et la seule qu'un agent lit sans deviner
 * quelle colonne porte quel chiffre (issue #72). Une grille de `<div>` aurait le meme rendu et
 * perdrait exactement cela.
 *
 * **La colonne « pages par document » est mise en avant** parce que c'est la seule limite qui est
 * la notre. RapidAPI compte des requetes ; il ne voit pas qu'une requete peut etre un PDF de mille
 * pages passe au modele de vision. La barre sous chaque chiffre rend l'echelle visible d'un coup
 * d'oeil, ce que quatre nombres empiles ne font pas.
 */
@Component({
  selector: 'pr-plans',
  imports: [ButtonModule, TagModule],
  template: `
    <section id="plans" class="pl">
      <div class="pr-shell pl__inner">
        <div class="pl__intro">
          <div class="pr-kicker pl__kicker">Plans</div>
          <h2 class="pr-h2 pl__h2">Metered by requests, bounded by pages.</h2>
          <p class="pr-lede pl__lede">
            Requests are metered by RapidAPI. ParseRAG enforces one limit of its own, because the
            marketplace counts requests and cannot see how big a job is: pages per document.
          </p>
        </div>

        <div class="pr-scroll-x pl__scroll">
          <table class="pl__table">
            <caption class="pl__caption pr-mono">
              Limits per plan. Prices are on the RapidAPI listing.
            </caption>
            <thead>
              <tr>
                <th scope="col">Plan</th>
                <th scope="col">Requests / month</th>
                <th scope="col">Requests / minute</th>
                <th scope="col" class="pl__own">
                  Pages per document
                  <span class="pl__own-note pr-mono">enforced by ParseRAG</span>
                </th>
              </tr>
            </thead>
            <tbody>
              @for (plan of plans; track plan.name) {
                <tr>
                  <th scope="row" class="pl__name">
                    <span class="pr-mono">{{ plan.name }}</span>
                    @if (plan.free) {
                      <p-tag value="Free" class="pl__tag" />
                    }
                  </th>
                  <td class="pr-mono">{{ plan.perMonth }}</td>
                  <td class="pr-mono">{{ plan.perMinute }}</td>
                  <td class="pl__own">
                    <span class="pr-mono">{{ plan.pages }}</span>
                    <span class="pl__bar" aria-hidden="true">
                      <span class="pl__bar-fill" [style.width.%]="plan.share"></span>
                    </span>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>

        <p class="pl__note">
          The 50 MB file cap applies on every plan. Over the page cap, the call is rejected with
          <code class="pr-mono">DOCUMENT_TOO_LONG</code> rather than truncated: a silently
          half-parsed document is worse than a clear refusal.
        </p>

        <a pButton class="pr-cta pr-cta--md pl__cta" [href]="rapidapi" target="_blank" rel="noopener">
          See the plans on RapidAPI
          <span class="pr-cta__arrow pr-mono" aria-hidden="true">&rarr;</span>
        </a>
      </div>
    </section>
  `,
  styles: `
    .pl {
      background: var(--pr-dark);
      color: var(--pr-on-dark);
    }

    .pl__inner { padding-block: 96px; }

    .pl__intro {
      display: flex;
      flex-direction: column;
      gap: 14px;
      max-width: 58ch;
      margin-bottom: 40px;
    }

    /* Le rouge de marque n'atteint pas le contraste AA sur le fond sombre. L'ambre est la
       declinaison prevue pour ce fond, deja utilisee par les autres sections sombres. */
    .pl__kicker { color: var(--pr-amber); }
    .pl__h2 { color: var(--pr-on-dark); }
    .pl__lede { color: var(--pr-on-dark-dim); }

    /* Le conteneur de defilement est la reponse a la contrainte du brief : a 360 px le tableau
       doit defiler dans sa propre boite plutot que d'elargir la page. Le tableau tient en
       pratique sur la plupart des telephones grace aux tailles reduites plus bas ; le conteneur
       est ce qui garantit que le cas contraire reste inoffensif. */
    .pl__scroll { border: 1px solid var(--pr-dark-border); }

    .pl__table {
      width: 100%;
      border-collapse: collapse;
      text-align: left;
    }

    .pl__caption {
      caption-side: top;
      padding: 14px 18px;
      border-bottom: 1px solid var(--pr-dark-border);
      background: var(--pr-dark-deep);
      font-size: 12px;
      letter-spacing: 0.06em;
      color: var(--pr-on-dark-muted);
      text-align: left;
    }

    .pl__table thead th {
      padding: 16px 18px;
      border-bottom: 1px solid var(--pr-dark-border-soft);
      font-family: var(--pr-font-mono);
      font-size: 12px;
      font-weight: 500;
      letter-spacing: 0.14em;
      text-transform: uppercase;
      color: var(--pr-on-dark-muted);
      vertical-align: bottom;
      white-space: nowrap;
    }

    .pl__table tbody tr + tr th,
    .pl__table tbody tr + tr td {
      border-top: 1px solid var(--pr-dark-border);
    }

    .pl__table tbody th,
    .pl__table tbody td {
      padding: 18px;
      font-size: 17px;
      color: var(--pr-on-dark-dim);
      font-weight: 400;
      white-space: nowrap;
    }

    .pl__name {
      display: flex;
      align-items: center;
      gap: 10px;
      color: var(--pr-on-dark) !important;
      font-size: 15px !important;
      letter-spacing: 0.1em;
    }

    /* Les variables de PrimeNG, pas ses regles : la classe p-tag est posee sur l'element hote
       lui-meme, donc sur celui que notre feuille atteint. Une declaration ici est bien celle que
       .p-tag resout, sans guerre de specificite ni ::ng-deep. */
    .pl__tag {
      --p-tag-primary-background: transparent;
      --p-tag-primary-color: var(--pr-amber);
      --p-tag-font-size: 11px;
      --p-tag-font-weight: 600;
      --p-tag-padding: 2px 7px;
      --p-tag-border-radius: 0;
      border: 1px solid var(--pr-ochre);
      font-family: var(--pr-font-sans);
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }

    /* La colonne qui nous appartient, distinguee par un aplat plutot que par une couleur : le
       rouge et l'ambre marquent deja le CTA et le plan gratuit, un troisieme accent ne
       marquerait plus rien. */
    .pl__own {
      background: var(--pr-dark-raised);
      border-inline: 1px solid var(--pr-dark-border);
    }

    .pl__table thead th.pl__own { color: var(--pr-amber); }

    .pl__own-note {
      display: block;
      margin-top: 6px;
      font-size: 11px;
      letter-spacing: 0.06em;
      text-transform: none;
      color: var(--pr-on-dark-muted);
    }

    .pl__table tbody td.pl__own { color: var(--pr-on-dark); }

    .pl__bar {
      display: block;
      width: 100%;
      max-width: 150px;
      height: 3px;
      margin-top: 10px;
      background: var(--pr-dark-border-soft);
    }

    /* La largeur est posee en ligne depuis les donnees, pas par une classe : sans JavaScript la
       barre est deja a sa longueur definitive. GSAP ne fait que la faire pousser depuis zero. */
    .pl__bar-fill {
      display: block;
      height: 3px;
      background: var(--pr-amber);
      transform-origin: left center;
    }

    .pl__note {
      margin: 26px 0 0;
      max-width: 68ch;
      font-size: 16px;
      line-height: 1.6;
      color: var(--pr-on-dark-muted);
    }

    .pl__note code {
      font-size: 14px;
      color: var(--pr-amber);
    }

    .pl__cta { margin-top: 30px; }

    @media (max-width: 640px) {
      .pl__inner { padding-block: 72px; }

      /* Les en-tetes cessent d'etre insecables : « Requests / month » passe sur deux lignes et le
         tableau redescend de 598 px de largeur minimale a la largeur d'un telephone. Sans cela il
         defilait certes dans sa boite, mais sur presque deux ecrans. Les valeurs, elles, restent
         insecables : « 50,000 » coupe apres la virgule serait illisible. */
      .pl__table thead th {
        padding: 12px;
        font-size: 11px;
        letter-spacing: 0.08em;
        white-space: normal;
      }

      .pl__table tbody th,
      .pl__table tbody td {
        padding: 14px 12px;
        font-size: 15px;
      }

      .pl__caption { padding: 12px; }

      .pl__bar { max-width: 90px; }
    }
  `,
})
export class Plans {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly motion = inject(Motion);

  protected readonly rapidapi = RAPIDAPI_URL;

  /** La grille vit dans plans-data.ts depuis #71 : le JSON-LD la lit au meme endroit. */
  protected readonly plans: readonly Plan[] = PLANS;

  constructor() {
    afterNextRender(async () => {
      const api = await this.motion.load();
      if (!api) {
        return;
      }
      const bars = Array.from(
        this.host.nativeElement.querySelectorAll<HTMLElement>('.pl__bar-fill'),
      );
      if (!bars.length) {
        return;
      }
      // `once: true` : l'echelle se lit une fois. Une barre qui se recharge a chaque passage
      // devant la section deviendrait un clignotement au lieu d'une demonstration.
      this.motion.onEnter(
        api,
        this.host.nativeElement.querySelector('.pl__table'),
        () => {
          api.gsap.fromTo(
            bars,
            { scaleX: 0 },
            { scaleX: 1, duration: 0.85, ease: 'power3.out', stagger: 0.09 },
          );
        },
        { once: true, onFail: () => api.gsap.set(bars, { scaleX: 1 }) },
      );
    });
  }
}
