import { Component, ElementRef, afterNextRender, inject } from '@angular/core';
import { SiteHeader } from './site-header';
import { Hero } from './hero';
import { QuickStart } from './quick-start';
import { Problem } from './problem';
import { WhyItMatters } from './why-it-matters';
import { HowItWorks } from './how-it-works';
import { SecondPass } from './second-pass';
import { Output } from './output';
import { EdgeCases } from './edge-cases';
import { Plans } from './plans';
import { Faq } from './faq';
import { FinalCta } from './final-cta';
import { SiteFooter } from './site-footer';
import { Motion } from './motion';
import { Seo } from '../seo/seo';
import { pageFor } from '../seo/site';

/**
 * La landing page (issue #69), assemblee section par section dans l'ordre du design.
 *
 * Un composant par section plutot qu'un seul gros gabarit : les issues #71 et #72 doivent pouvoir
 * poser des metadonnees et du balisage par section sans relire mille lignes.
 *
 * Les arrivees de titres sont pilotees ici et non dans chaque section : c'est un effet uniforme
 * sur toute la page, le repeter neuf fois en ferait neuf variantes qui divergeraient.
 */
@Component({
  selector: 'pr-landing-page',
  imports: [
    SiteHeader,
    Hero,
    QuickStart,
    Problem,
    WhyItMatters,
    HowItWorks,
    SecondPass,
    Output,
    EdgeCases,
    Plans,
    Faq,
    FinalCta,
    SiteFooter,
  ],
  templateUrl: './landing-page.html',
})
export class LandingPage {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly motion = inject(Motion);
  private readonly seo = inject(Seo);

  constructor() {
    // Pose dans le constructeur, donc pendant le rendu serveur : les balises sont dans le HTML
    // prerendu, sans qu'une ligne de JavaScript s'execute chez le visiteur (issues #70, #71).
    this.seo.apply(pageFor('/'), 'home');

    afterNextRender(async () => {
      const api = await this.motion.load();
      if (!api) {
        return;
      }
      // `once: true` et un declenchement tardif (88 % de la hauteur) : un titre ne doit se reveler
      // qu'une fois, et pas se rejouer a chaque remontee, ce qui rendrait la page instable a la
      // relecture.
      for (const heading of this.host.nativeElement.querySelectorAll('main h2')) {
        api.ScrollTrigger.create({
          trigger: heading,
          start: 'top 88%',
          once: true,
          onEnter: () =>
            api.gsap.fromTo(
              heading,
              { opacity: 0, y: 14 },
              { opacity: 1, y: 0, duration: 0.6, ease: 'power3.out' },
            ),
        });
      }
    });
  }
}
