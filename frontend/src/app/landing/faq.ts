import { Component, ViewEncapsulation } from '@angular/core';
import { AccordionModule } from 'primeng/accordion';

interface Question {
  readonly value: string;
  readonly question: string;
  /** Premiere phrase, autoportante. Voir la note sur l'extraction ci-dessous. */
  readonly lead: string;
  readonly rest?: string;
}

/**
 * La FAQ.
 *
 * Elle etait au brief de design (docs/landing-page-design-brief.md, section 6.8) mais absente du
 * canevas rendu. Elle est reintroduite ici parce que deux issues en dependent : #71 pour le
 * balisage FAQPage, et #72 ou elle est la surface prevue pour les agents.
 *
 * **Les questions sont formulees telles qu'un developpeur les taperait**, et non en intitules de
 * rubrique. « How do I extract tables from a PDF as JSON? » plutot que « Tables ». C'est ce qui
 * permet a un assistant de reconnaitre la question qu'on lui pose dans celle que la page a deja
 * posee.
 *
 * **La premiere phrase de chaque reponse se suffit a elle-meme.** Une reponse qui commence par
 * « Oui, mais seulement si... » n'est citable que collee a sa question ; separee, elle ne veut plus
 * rien dire. Le modele de donnees force la contrainte en separant lead de rest, ce qui la rend
 * visible a la relecture au lieu de dependre de la vigilance de qui ecrit.
 *
 * Le contenu vient de docs/rapidapi-readme.md, section FAQ : la page et le listing repondent la
 * meme chose.
 */
@Component({
  selector: 'pr-faq',
  imports: [AccordionModule],
  encapsulation: ViewEncapsulation.None,
  template: `
    <section id="faq" class="faq">
      <div class="pr-shell faq__inner">
        <div class="faq__intro">
          <div class="pr-kicker">Questions</div>
          <h2 class="pr-h2">Before you subscribe.</h2>
          <p class="pr-lede">The six things developers ask first.</p>
        </div>

        <p-accordion value="q1">
          @for (item of questions; track item.value) {
            <p-accordion-panel [value]="item.value">
              <p-accordion-header>{{ item.question }}</p-accordion-header>
              <p-accordion-content>
                <p class="faq__lead">{{ item.lead }}</p>
                @if (item.rest) {
                  <p class="faq__rest">{{ item.rest }}</p>
                }
              </p-accordion-content>
            </p-accordion-panel>
          }
        </p-accordion>
      </div>
    </section>
  `,
  styles: `
    .faq {
      background: var(--pr-surface);
      border-top: 1px solid var(--pr-hairline);
      border-bottom: 1px solid var(--pr-hairline);
    }

    .faq .faq__inner { padding-block: 96px; }

    .faq .faq__intro {
      display: flex;
      flex-direction: column;
      gap: 14px;
      max-width: 52ch;
      margin-bottom: 40px;
    }

    /* ---- Pont entre nos tokens et ceux de PrimeNG ----
       On ne surcharge pas les regles de PrimeNG, on renseigne ses variables. C'est le point
       d'extension prevu par la bibliotheque, et c'est ce qui evite la guerre de specificite :
       une premiere version redefinissait .p-accordionheader { color: ... } et perdait sur la
       couleur du titre actif, la feuille de theme etant injectee apres les styles du composant.
       Les variables, elles, sont lues par les regles de PrimeNG elles-memes.

       Elles sont posees sur .faq .p-accordion et non sur .faq : PrimeNG declare ses tokens sur
       l element du composant lui-meme, et une declaration portee par un ancetre ne l emporte pas
       sur celle de l element. Mesure faite, --p-accordion-header-active-color resolvait encore
       vers le gris ardoise d Aura tant que la regle visait la section. */
    .faq .p-accordion {
      --p-accordion-panel-border-color: var(--pr-hairline);
      --p-accordion-panel-border-width: 0 0 1px 0;

      --p-accordion-header-background: transparent;
      --p-accordion-header-active-background: transparent;
      --p-accordion-header-hover-background: var(--pr-surface);
      --p-accordion-header-active-hover-background: var(--pr-surface);
      --p-accordion-header-color: var(--pr-ink);
      --p-accordion-header-hover-color: var(--pr-red);
      --p-accordion-header-active-color: var(--pr-red);
      --p-accordion-header-active-hover-color: var(--pr-red);
      --p-accordion-header-padding: 22px 24px;
      --p-accordion-header-font-size: 18px;
      --p-accordion-header-font-weight: 600;
      --p-accordion-header-border-width: 0;
      --p-accordion-header-first-border-width: 0;
      --p-accordion-header-focus-ring-color: var(--pr-red);

      /* Angles vifs : le systeme de design de la page n'a aucun rayon, Aura en met partout. */
      --p-accordion-header-border-radius: 0;
      --p-accordion-header-first-top-border-radius: 0;
      --p-accordion-header-last-bottom-border-radius: 0;
      --p-accordion-header-last-active-bottom-border-radius: 0;

      --p-accordion-header-toggle-icon-color: var(--pr-muted);
      --p-accordion-header-toggle-icon-hover-color: var(--pr-red);
      --p-accordion-header-toggle-icon-active-color: var(--pr-red);
      --p-accordion-header-toggle-icon-active-hover-color: var(--pr-red);

      --p-accordion-content-background: transparent;
      --p-accordion-content-color: var(--pr-ink-soft);
      --p-accordion-content-border-width: 0;
      --p-accordion-content-padding: 0 24px 24px;
    }

    /* Ce qui n'a pas de token : le cadre exterieur et le marqueur rouge du panneau ouvert. */
    .faq .p-accordion {
      display: flex;
      flex-direction: column;
      border: 1px solid var(--pr-hairline);
      background: var(--pr-paper);
    }

    .faq .p-accordionpanel:last-child { border-bottom: 0; }

    /* Etat ouvert : couleur et angles vifs.

       La regle de PrimeNG est
       .p-accordionpanel:not(.p-disabled).p-accordionpanel-active > .p-accordionheader,
       soit quatre classes. Une regle a quatre classes egalement, comme
       .faq .p-accordionpanel[data-p-active] .p-accordionheader, perd l egalite : la feuille de
       theme est injectee apres les styles du composant. On reprend donc sa forme exacte en
       ajoutant .faq devant, ce qui fait cinq et l emporte proprement.

       C est aussi pourquoi on vise sa classe d etat .p-accordionpanel-active et non l attribut
       data-p-active : les deux marquent le meme etat, mais seule la classe entre dans le calcul
       auquel PrimeNG participe. */
    .faq .p-accordionpanel:not(.p-disabled).p-accordionpanel-active > .p-accordionheader {
      color: var(--pr-red);
      border-radius: 0;
      /* Le seul rouge de la section. Ailleurs il marquerait six choses a la fois et ne
         marquerait plus rien. */
      box-shadow: inset 3px 0 0 var(--pr-red);
    }

    /* Le panneau ouvert doit s afficher, meme si l animation de PrimeNG n aboutit pas.

       PrimeNG replie son conteneur .p-motion avec des styles EN LIGNE, visibility: hidden et
       max-height: 0, et compte sur la fin de son animation pour les retirer. Mesure faite sur le
       build de production : le panneau ouvert au chargement les porte deja apres hydratation du
       HTML prerendu, et un clic met bien a jour --height mais ne les retire jamais. Le contenu
       reste donc invisible, hauteur 0, alors que data-p-active vaut true.

       Une declaration en ligne ne se surcharge qu avec !important : c est le seul endroit de la
       page ou il est indispensable plutot que commode. Le principe est celui applique partout
       ailleurs ici : la lisibilite d un contenu ne depend jamais de l aboutissement d une
       animation. Les panneaux fermes gardent le max-height: 0 en ligne de PrimeNG et restent
       fermes. */
    .faq p-accordion-content[data-p-active='true'] .p-motion {
      visibility: visible !important;
      max-height: none !important;
    }

    /* Meme raison pour les angles du premier et du dernier panneau, qu Aura arrondit. */
    .faq .p-accordionpanel:first-child > .p-accordionheader,
    .faq .p-accordionpanel:last-child > .p-accordionheader {
      border-radius: 0;
    }

    .faq .faq__lead {
      margin: 0;
      font-size: 17px;
      line-height: 1.6;
      max-width: 62ch;
    }

    .faq .faq__rest {
      margin: 12px 0 0;
      font-size: 17px;
      line-height: 1.6;
      color: var(--pr-muted);
      max-width: 62ch;
    }
  `,
})
export class Faq {
  protected readonly questions: readonly Question[] = [
    {
      value: 'q1',
      question: 'Does it work on scanned documents?',
      lead: 'Yes. Image-only pages skip native extraction and go to a vision model, and those chunks come back with fallback_used set to true.',
      rest: 'Quality depends on the scan, so check the confidence score before indexing.',
    },
    {
      value: 'q2',
      question: 'What languages are supported?',
      lead: 'Text extraction is language-agnostic and works on any language the PDF contains.',
      rest: 'The reported language field detects fr, en, de and es, and returns unknown otherwise. An unknown language does not affect extraction.',
    },
    {
      value: 'q3',
      question: 'How do I extract tables from a PDF as JSON?',
      lead: 'TABLE chunks carry a table_json object with headers, rows and an optional caption, so no separate endpoint or parameter is needed.',
      rest: 'A quality gate decides when a grid Tabula returned badly is worth re-reading with a vision model, which is why a borderless table still comes back structured.',
    },
    {
      value: 'q4',
      question: 'Why is a chunk flagged when the text looks fine?',
      lead: 'The flag is deliberately cautious: it is raised on the signature of a layout problem, not on proof of one.',
      rest: 'A false positive costs you a review; a false negative costs you a poisoned index. The asymmetry is the point.',
    },
    {
      value: 'q5',
      question: 'How long does a parse take?',
      lead: 'A native-text document of 200 pages parses in a few seconds; a scanned document is far slower, because each page goes through a vision model.',
      rest: 'Keep your client timeout high. Vision work runs under a time budget, so a slow provider returns pages flagged for review rather than hanging the request.',
    },
    {
      value: 'q6',
      question: 'Is there a batch endpoint?',
      lead: 'Not today. Send documents one at a time and stay inside your plan’s requests-per-minute limit.',
    },
  ];
}
