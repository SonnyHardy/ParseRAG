import { Injectable, PLATFORM_ID, inject } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import type { gsap as GsapType } from 'gsap';

export interface MotionApi {
  readonly gsap: typeof GsapType;
  readonly ScrollTrigger: typeof import('gsap/ScrollTrigger').ScrollTrigger;
}

/**
 * Chargement et pilotage des animations du design.
 *
 * Trois decisions structurent ce fichier.
 *
 * **GSAP est empaquete, pas charge d'un CDN.** Le design le tirait de jsdelivr : une dependance
 * tierce sur le chemin critique, invisible d'un npm ci, et un point de defaillance hors de notre
 * controle. Ici il est en dependance npm, et importe **dynamiquement** : il sort donc du bundle
 * initial et devient un morceau charge apres le premier rendu, ce qui laisse le budget d'entree
 * a la page elle-meme.
 *
 * **Aucun etat initial n'est pose en CSS.** C'est l'inversion la plus importante par rapport au
 * design, qui masquait le contenu par html.pr-motion [data-fade] { opacity: 0 } jusqu'a ce que
 * GSAP confirme pouvoir animer, avec chien de garde et procedure de secours pour le cas ou il ne
 * repondrait pas. Ici les etats de depart sont poses **par GSAP lui-meme**, donc seulement quand
 * il est charge : sans JavaScript, sans reseau, ou si l'import echoue, la page reste dans son etat
 * final, complete et lisible. Le cas degrade n'a pas besoin d'etre rattrape, il est le defaut.
 *
 * **prefers-reduced-motion coupe tout en amont**, avant meme le chargement du paquet : une
 * personne qui a demande moins d'animation ne telecharge pas la bibliotheque qui les produit.
 *
 * **Et l'on verifie que le navigateur anime vraiment.** Une page peut se declarer visible tout en
 * ne recevant presque aucune image : onglet d'arriere-plan, fenetre masquee, economie d'energie,
 * navigateur pilote. Mesure faite ici, un tel contexte delivrait **2 images par seconde**, ou une
 * animation de trois secondes prend une minute et demie. Dans ce cas les etats de depart ne sont
 * jamais poses : mieux vaut une page immobile et complete qu'une page dont le contenu se devoile
 * a la minute. C'est la meme protection que le chien de garde du design, prise a l'envers : on
 * n'entre pas dans l'etat anime plutot que d'essayer d'en sortir.
 */
@Injectable({ providedIn: 'root' })
export class Motion {
  private readonly platformId = inject(PLATFORM_ID);
  private loading?: Promise<MotionApi | null>;
  private refreshScheduled = false;

  /** Vrai si l'on est dans un navigateur et que l'utilisateur n'a pas demande moins d'animation. */
  get enabled(): boolean {
    if (!isPlatformBrowser(this.platformId)) {
      return false;
    }
    return !window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
  }

  /** Charge GSAP une seule fois. Renvoie null si l'animation est desactivee ou l'import echoue. */
  load(): Promise<MotionApi | null> {
    if (!this.enabled) {
      return Promise.resolve(null);
    }
    // La mesure de cadence tourne **en parallele** de l'import : elle ne coute donc rien, le
    // telechargement et l'analyse du paquet prenant de toute facon plus longtemps.
    this.loading ??= Promise.all([
      import('gsap'),
      import('gsap/ScrollTrigger'),
      this.framesFlow(),
    ])
      .then(([core, trigger, flowing]) => {
        if (!flowing) {
          return null;
        }
        core.gsap.registerPlugin(trigger.ScrollTrigger);
        return { gsap: core.gsap, ScrollTrigger: trigger.ScrollTrigger };
      })
      .catch(() => null);
    return this.loading;
  }

  /**
   * Compte les images rendues pendant une demi-seconde.
   *
   * Le seuil est volontairement bas : on ne cherche pas a mesurer la fluidite, seulement a
   * distinguer un navigateur qui anime d'un navigateur qui ne le fait pas. Six images en 500 ms,
   * soit 12 par seconde, laissent passer une machine lente et arretent un contexte etrangle.
   */
  private framesFlow(): Promise<boolean> {
    return new Promise((resolve) => {
      let frames = 0;
      const start = performance.now();
      const tick = () => {
        frames += 1;
        if (performance.now() - start < 500) {
          requestAnimationFrame(tick);
        } else {
          resolve(frames >= 6);
        }
      };
      requestAnimationFrame(tick);
    });
  }

  /**
   * Joue run quand el entre dans la fenetre.
   *
   * start: 'top 72%' est la valeur du design : l'animation part quand le haut de la section
   * atteint 72 % de la hauteur de la fenetre, donc un peu avant d'etre au centre.
   *
   * Le rappel est protege : une animation qui leve ne doit pas laisser la page figee dans un etat
   * intermediaire, donc l'exception est capturee et l'element remis dans son etat final.
   */
  onEnter(
    api: MotionApi,
    el: Element | null | undefined,
    run: () => void,
    options: { once?: boolean; onFail?: () => void } = {},
  ): void {
    if (!el) {
      return;
    }
    const safely = () => {
      try {
        run();
      } catch {
        options.onFail?.();
      }
    };
    const trigger = api.ScrollTrigger.create({
      trigger: el,
      start: 'top 72%',
      once: options.once ?? false,
      onEnter: safely,
      onEnterBack: options.once ? undefined : safely,
    });
    // Section deja depassee au moment ou l'observateur est cree, par exemple sur un rechargement
    // qui restaure la position : on joue maintenant plutot que jamais.
    if (trigger.progress > 0) {
      safely();
    }
    this.scheduleRefresh(api);
  }

  /**
   * Recalcule les positions de tous les declencheurs, une seule fois par salve.
   *
   * GSAP rafraichit de lui-meme au load et au redimensionnement. Nos declencheurs, eux, sont
   * crees **apres** le load, puisque la bibliotheque est importee dynamiquement : ils calculent
   * donc leur position sur une mise en page qui n'a pas fini de se poser, les polices et les
   * images n'etant pas encore arrivees. Sans ce rafraichissement, un declencheur peut se croire
   * plus bas qu'il n'est et ne jamais s'activer, ce qui laisse sa section dans son etat de depart.
   *
   * Deux requestAnimationFrame imbriques : le premier laisse Angular finir son rendu, le second
   * laisse le navigateur recalculer la mise en page avant qu'on ne la mesure.
   */
  private scheduleRefresh(api: MotionApi): void {
    if (this.refreshScheduled) {
      return;
    }
    this.refreshScheduled = true;
    requestAnimationFrame(() =>
      requestAnimationFrame(() => {
        this.refreshScheduled = false;
        api.ScrollTrigger.refresh();
      }),
    );
  }
}
