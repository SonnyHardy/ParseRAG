import { Component, ElementRef, OnDestroy, afterNextRender, inject } from '@angular/core';
import { Motion, type MotionApi } from './motion';

/**
 * La boucle de verification : la geometrie propose, l'ordre de lecture dispose.
 *
 * L'animation joue le cycle complet : assemblage, relecture ligne a ligne, detection du signal
 * d'entrelacement, **arret delibere** avant d'agir, rejet, reassemblage. Les six pastilles
 * s'allument au fur et a mesure, en ocre pour les etapes normales, en rouge pour le conflit, en
 * encre pour la validation finale.
 */
@Component({
  selector: 'pr-second-pass',
  template: `
    <section class="pr-shell sp">
      <div class="sp__box">
        <div class="sp__head pr-kicker">The second pass</div>
        <div class="sp__grid">
          <div class="sp__left">
            <p class="sp__text">
              After assembling a page, the parser reads its own output back. If it finds the
              statistical signature of interleaved columns, the result is thrown away and the page
              is reassembled with different column splits.
            </p>
            <ol class="sp__steps pr-mono">
              @for (step of steps; track step.text) {
                <li class="sp__step" [class]="'sp__step--' + step.tone">
                  <span class="sp__dot" aria-hidden="true"></span>{{ step.text }}
                </li>
              }
            </ol>
          </div>

          <div class="sp__right">
            <div class="sp__stack" aria-hidden="true">
              @for (frag of fragments; track $index) {
                <div class="frag">
                  <span class="frag__tick" [class.frag__tick--c2]="frag.col === 2"></span>
                  <span class="frag__bar" [style.width.%]="frag.width"></span>
                </div>
              }
            </div>
            <p class="sp__quote">Geometry proposes. Reading order disposes.</p>
          </div>
        </div>
      </div>
    </section>
  `,
  styles: `
    .sp { padding-block: 56px 96px; }

    .sp__box {
      border: 1px solid var(--pr-hairline);
      background: var(--pr-surface);
    }

    .sp__head {
      padding: 14px 22px;
      border-bottom: 1px solid var(--pr-hairline);
      letter-spacing: 0.16em;
    }

    .sp__grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(330px, 100%), 1fr));
    }

    .sp__left {
      box-sizing: border-box;
      padding: 32px 26px;
      display: flex;
      flex-direction: column;
      gap: 22px;
    }

    .sp__text {
      margin: 0;
      font-size: 18px;
      line-height: 1.55;
      color: var(--pr-ink-soft);
      max-width: 42ch;
    }

    .sp__steps {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 9px;
      font-size: 13px;
    }

    .sp__step {
      display: flex;
      align-items: center;
      gap: 11px;
      color: var(--pr-ink-soft);
    }

    .sp__step--alert { color: var(--pr-red); }
    .sp__step--done { color: var(--pr-ink); font-weight: 700; }

    .sp__dot {
      flex: none;
      width: 7px;
      height: 7px;
      background: var(--pr-mock-strong);
    }

    .sp__right {
      box-sizing: border-box;
      border-left: 1px solid var(--pr-hairline);
      background: var(--pr-paper);
      padding: 32px 26px;
      display: flex;
      flex-direction: column;
      gap: 24px;
      justify-content: center;
    }

    /* Sous 330 px de colonne la grille s'empile : le filet de gauche deviendrait un trait
       flottant au milieu du bloc, il passe donc en haut. */
    @media (max-width: 719px) {
      .sp__right {
        border-left: 0;
        border-top: 1px solid var(--pr-hairline);
      }
    }

    /* overflow hidden : pendant le rejet les fragments se decalent lateralement, et sans cela
       ils depasseraient du panneau. */
    .sp__stack {
      display: flex;
      flex-direction: column;
      gap: 7px;
      overflow: hidden;
    }

    .frag {
      display: flex;
      gap: 9px;
      align-items: center;
    }

    .frag__tick {
      width: 4px;
      height: 14px;
      flex: none;
      background: var(--pr-red);
    }

    .frag__tick--c2 { background: var(--pr-ochre); }

    .frag__bar {
      flex: 1;
      height: 7px;
      background: var(--pr-mock);
    }

    .sp__quote {
      margin: 0;
      font-size: clamp(21px, 2.4vw, 28px);
      font-weight: 500;
      letter-spacing: -0.018em;
      line-height: 1.22;
      max-width: 20ch;
    }
  `,
})
export class SecondPass implements OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly motion = inject(Motion);
  private api: MotionApi | null = null;
  private timeline?: gsap.core.Timeline;

  constructor() {
    afterNextRender(async () => {
      this.api = await this.motion.load();
      if (this.api) {
        this.motion.onEnter(this.api, this.host.nativeElement.querySelector('.sp__box'), () =>
          this.run(),
        );
      }
    });
  }

  ngOnDestroy(): void {
    this.timeline?.kill();
  }

  private run(): void {
    const api = this.api;
    if (!api) {
      return;
    }
    const { gsap } = api;
    const root = this.host.nativeElement;
    const steps = Array.from(root.querySelectorAll<HTMLElement>('.sp__step'));
    const dots = Array.from(root.querySelectorAll<HTMLElement>('.sp__dot'));
    const frags = Array.from(root.querySelectorAll<HTMLElement>('.frag'));
    const quote = root.querySelector('.sp__quote');
    if (!steps.length || !frags.length) {
      return;
    }

    this.timeline?.kill();
    const rowHeight = frags.length > 1 ? frags[1].offsetTop - frags[0].offsetTop : 20;
    const interleaved = [0, 3, 1, 4, 2, 5];

    gsap.set(steps, { opacity: 0.25 });
    gsap.set(dots, { backgroundColor: '#c4baa6', scale: 1 });
    gsap.set(frags, { y: 0, x: 0, opacity: 1 });

    const tl = gsap.timeline({ repeat: -1, repeatDelay: 2.8 });
    this.timeline = tl;

    const light = (i: number, color: string, at: number) => {
      tl.to(steps[i], { opacity: 1, duration: 0.25 }, at)
        .to(dots[i], { backgroundColor: color, scale: 1.3, duration: 0.22, ease: 'power2.out' }, at)
        .to(dots[i], { scale: 1, duration: 0.22 }, at + 0.22);
    };

    tl.fromTo(frags, { opacity: 0, y: 12 }, { opacity: 1, y: 0, duration: 0.42, stagger: 0.08, ease: 'power2.out' }, 0);
    light(0, '#a8752c', 0.55);

    // relecture : le parseur repasse sur sa propre sortie, ligne a ligne
    light(1, '#a8752c', 1.15);
    frags.forEach((f, i) => {
      tl.to(f, { x: 6, duration: 0.1, ease: 'power1.out' }, 1.3 + i * 0.09).to(
        f,
        { x: 0, duration: 0.16, ease: 'power1.inOut' },
        1.4 + i * 0.09,
      );
    });

    // conflit : ce qu'il a produit etait entrelace
    tl.to(frags, { y: (i: number) => (interleaved[i] - i) * rowHeight, duration: 0.55, ease: 'power2.inOut', stagger: 0.04 }, 2.0);
    light(2, '#8c2f26', 2.2);

    // arret delibere : le systeme a trouve un probleme et marque un temps avant d'agir. C'est le
    // seul moment immobile de la page, et c'est ce qui rend le rejet lisible.
    tl.to(frags, { opacity: 0.85, duration: 0.3 }, 2.75).to({}, { duration: 0.45 }, 2.85);

    // rejet : l'alignement se defait visiblement
    tl.to(frags, { x: (i: number) => -10 - i * 3, opacity: 0.35, duration: 0.3, ease: 'power3.in', stagger: 0.035 }, 3.35);
    light(3, '#8c2f26', 3.45);

    // reassemblage correct
    tl.to(frags, { y: 0, duration: 0.7, ease: 'power3.inOut', stagger: 0.06 }, 3.85).to(
      frags,
      { x: 0, opacity: 1, duration: 0.6, ease: 'power3.out', stagger: 0.06 },
      4.05,
    );
    light(4, '#a8752c', 4.2);
    light(5, '#1e1b16', 5.0);

    if (quote) {
      tl.fromTo(quote, { opacity: 0.35, y: 10 }, { opacity: 1, y: 0, duration: 0.7, ease: 'power3.out' }, 5.05);
    }
  }

  protected readonly steps = [
    { text: 'page assembled', tone: 'normal' },
    { text: 'quality check', tone: 'normal' },
    { text: 'order signal detected', tone: 'alert' },
    { text: 'result rejected', tone: 'alert' },
    { text: 'page reassembled', tone: 'normal' },
    { text: 'validated', tone: 'done' },
  ];

  protected readonly fragments = [
    { col: 1, width: 100 },
    { col: 1, width: 100 },
    { col: 1, width: 72 },
    { col: 2, width: 100 },
    { col: 2, width: 100 },
    { col: 2, width: 58 },
  ];
}
