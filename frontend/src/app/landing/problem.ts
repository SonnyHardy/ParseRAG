import { Component, ElementRef, OnDestroy, afterNextRender, inject } from '@angular/core';
import { Motion, type MotionApi } from './motion';

/**
 * L'avant / apres sur l'ordre de lecture, coeur argumentaire de la page.
 *
 * Les deux colonnes de texte sont du **vrai texte**, pas une image : c'est ce qui permet a un
 * moteur et a un agent de lire la demonstration, et non seulement de voir qu'il y a une figure.
 *
 * L'animation du design rejoue la demonstration : un curseur de lecture parcourt la sortie
 * entrelacee en sautant d'une colonne a l'autre, le verdict tombe, puis les lignes correctes se
 * reconstruisent physiquement en passant les unes devant les autres. Elle boucle, parce que c'est
 * la section qu'on regarde deux fois.
 */
@Component({
  selector: 'pr-problem',
  template: `
    <section id="problem" class="pr-shell pb">
      <div class="pb__intro">
        <div class="pr-kicker">The problem</div>
        <h2 class="pr-h2">PDF extraction is quietly wrong.</h2>
        <p class="pr-lede">Two columns. One page. Two completely different reading orders.</p>
      </div>

      <div class="pb__legend pr-mono">
        <span class="pb__legend-item" data-legend="1">
          <span class="pb__swatch pb__swatch--c1"></span>column 1
        </span>
        <span class="pb__legend-item" data-legend="2">
          <span class="pb__swatch pb__swatch--c2"></span>column 2
        </span>
      </div>

      <div class="pb__grid">
        <div class="card card--bad">
          <div class="card__head">
            <span class="card__label pr-mono">Plain extraction</span>
            <span class="badge badge--bad pr-mono" data-badge="bad">wrong order</span>
          </div>
          <div class="rows pr-mono">
            @for (row of interleaved; track $index) {
              <div class="row" data-bad-row [attr.data-col]="row.col">
                <span class="row__tick" [class.row__tick--c2]="row.col === 2"></span>
                <span class="row__text" [class.row__text--c2]="row.col === 2">{{ row.text }}</span>
              </div>
            }
          </div>
          <div class="card__foot">Every chunk mixes two unrelated sentences.</div>
        </div>

        <div class="card card--good">
          <div class="card__head card__head--dark">
            <span class="card__label pr-mono">ParseRAG</span>
            <span class="badge badge--good pr-mono" data-badge="good">in order</span>
          </div>
          <div class="rows pr-mono">
            @for (row of ordered; track $index) {
              <div class="row" data-good-row>
                <span class="row__tick" [class.row__tick--c2]="row.col === 2"></span>
                <span class="row__text row__text--plain">{{ row.text }}</span>
              </div>
            }
          </div>
          <div class="card__foot">One column read fully, then the next.</div>
        </div>
      </div>

      <p class="pb__close">Parsing a PDF is not the same as understanding its reading order.</p>
    </section>
  `,
  styles: `
    .pb { padding-block: 96px; }

    .pb__intro {
      display: flex;
      flex-direction: column;
      gap: 14px;
      max-width: 52ch;
      margin-bottom: 28px;
    }

    .pb__legend {
      display: flex;
      align-items: center;
      gap: 22px;
      flex-wrap: wrap;
      margin-bottom: 16px;
      font-size: 13px;
      color: var(--pr-muted);
    }

    .pb__legend-item { display: inline-flex; align-items: center; gap: 9px; }

    .pb__swatch { width: 16px; height: 6px; }
    .pb__swatch--c1 { background: var(--pr-red); }
    .pb__swatch--c2 { background: var(--pr-ochre); }

    .pb__grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(320px, 100%), 1fr));
      gap: 24px;
    }

    .card { box-sizing: border-box; display: flex; flex-direction: column; }

    .card--bad {
      border: 1px solid var(--pr-hairline);
      background: var(--pr-surface);
    }

    .card--good {
      border: 1px solid var(--pr-ink);
      background: var(--pr-paper);
    }

    .card__head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding: 12px 18px;
      border-bottom: 1px solid var(--pr-hairline);
    }

    .card__head--dark { background: var(--pr-ink); border-bottom: 0; }

    .card__label {
      font-size: 13px;
      letter-spacing: 0.1em;
      text-transform: uppercase;
      color: var(--pr-ink-soft);
    }

    .card__head--dark .card__label { color: var(--pr-surface); }

    .badge {
      font-size: 12px;
      letter-spacing: 0.08em;
      text-transform: uppercase;
      font-weight: 700;
      padding: 3px 8px;
    }

    .badge--bad { color: var(--pr-red); border: 1px solid var(--pr-red); }
    .badge--good { color: var(--pr-amber); border: 1px solid var(--pr-ochre); }

    /* overflow hidden : pendant la reconstruction les lignes sortent lateralement de leur
       colonne, et sans cela elles depasseraient la carte. */
    .rows {
      padding: 20px 18px;
      display: flex;
      flex-direction: column;
      gap: 5px;
      font-size: 14px;
      line-height: 1.4;
      overflow: hidden;
    }

    .row {
      display: flex;
      gap: 10px;
      padding: 2px 4px;
      margin: 0 -4px;
    }

    .row__tick { width: 4px; flex: none; background: var(--pr-red); }
    .row__tick--c2 { background: var(--pr-ochre); }

    /* Dans la colonne fautive, le texte lui-meme est colore : c'est ce qui rend l'alternance
       visible d'un coup d'oeil. Dans la colonne correcte il redevient noir, seule la barre de
       gauche gardant la couleur d'origine, ce qui montre que l'ordre a change sans que le texte
       change. */
    .row__text { color: var(--pr-red); }
    .row__text--c2 { color: var(--pr-ochre-text); }
    .row__text--plain { color: var(--pr-ink-soft); }

    .card__foot {
      margin-top: auto;
      padding: 12px 18px;
      border-top: 1px solid var(--pr-hairline);
      font-size: 15px;
      line-height: 1.5;
      color: var(--pr-muted);
    }

    .pb__close {
      margin: 32px 0 0;
      font-size: 19px;
      line-height: 1.55;
      color: var(--pr-ink-soft);
      max-width: 60ch;
    }
  `,
})
export class Problem implements OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly motion = inject(Motion);
  private api: MotionApi | null = null;
  private timeline?: gsap.core.Timeline;

  protected readonly interleaved = [
    { col: 1, text: 'Retrieval-augmented generation grounds a' },
    { col: 2, text: 'evaluated separately from generation. We' },
    { col: 1, text: 'language model in an external corpus so' },
    { col: 2, text: 'report recall at k for three chunking' },
    { col: 1, text: 'that factual claims can be traced to a' },
    { col: 2, text: 'strategies and find that boundary quality' },
  ];

  protected readonly ordered = [
    { col: 1, text: 'Retrieval-augmented generation grounds a' },
    { col: 1, text: 'language model in an external corpus so' },
    { col: 1, text: 'that factual claims can be traced to a' },
    { col: 2, text: 'evaluated separately from generation. We' },
    { col: 2, text: 'report recall at k for three chunking' },
    { col: 2, text: 'strategies and find that boundary quality' },
  ];

  /** Index du DOM vers la place que la ligne occupait dans la sortie entrelacee. */
  private readonly scramble = [0, 3, 1, 4, 2, 5];

  constructor() {
    afterNextRender(async () => {
      this.api = await this.motion.load();
      if (!this.api) {
        return;
      }
      this.motion.onEnter(this.api, this.host.nativeElement.querySelector('.pb__grid'), () =>
        this.run(),
      );
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
    const bad = Array.from(root.querySelectorAll<HTMLElement>('[data-bad-row]'));
    const good = Array.from(root.querySelectorAll<HTMLElement>('[data-good-row]'));
    const badgeBad = root.querySelector('[data-badge="bad"]');
    const badgeGood = root.querySelector('[data-badge="good"]');
    const legend1 = root.querySelector('[data-legend="1"]');
    const legend2 = root.querySelector('[data-legend="2"]');
    if (!bad.length || !good.length) {
      return;
    }

    this.timeline?.kill();
    const rowHeight = good.length > 1 ? good[1].offsetTop - good[0].offsetTop : 24;
    const { scramble } = this;

    gsap.set(bad, { backgroundColor: 'rgba(0,0,0,0)' });
    gsap.set(good, {
      backgroundColor: 'rgba(0,0,0,0)',
      y: (i: number) => (scramble[i] - i) * rowHeight,
      // Le cote correct part aussi decale vers sa colonne d'origine : la reconstruction voyage
      // ainsi horizontalement autant que verticalement, ce qui montre d'ou vient chaque ligne.
      x: (i: number) => (scramble[i] % 2 === 0 ? -16 : 22),
    });
    gsap.set([badgeBad, badgeGood], { opacity: 0, scale: 0.94 });
    gsap.set([legend1, legend2], { opacity: 0.45 });

    const tl = gsap.timeline({ repeat: -1, repeatDelay: 2.6 });
    this.timeline = tl;

    // 1. le curseur de lecture naif parcourt la sortie entrelacee, en sautant de colonne
    bad.forEach((row, i) => {
      const at = 0.2 + i * 0.36;
      tl.to(row, { backgroundColor: 'rgba(140,47,38,.14)', x: 5, duration: 0.18, ease: 'power2.out' }, at)
        .to(row, { backgroundColor: 'rgba(0,0,0,0)', x: 0, duration: 0.34, ease: 'power2.inOut' }, at + 0.32);
      const own = row.dataset['col'] === '1' ? legend1 : legend2;
      const other = row.dataset['col'] === '1' ? legend2 : legend1;
      tl.to(own, { opacity: 1, duration: 0.12 }, at).to(other, { opacity: 0.35, duration: 0.12 }, at);
    });

    // 2. arret sur la transition fautive, puis le verdict
    tl.to([bad[1], bad[2]], { backgroundColor: 'rgba(140,47,38,.2)', duration: 0.22 }, 2.35)
      .to(badgeBad, { opacity: 1, scale: 1, duration: 0.34, ease: 'back.out(2)' }, 2.5)
      .to([bad[1], bad[2]], { backgroundColor: 'rgba(0,0,0,0)', duration: 0.5 }, 3.05);

    // 3. reconstruction physique : les lignes sortent, se croisent, se rangent
    tl.to(good, { x: (i: number) => (scramble[i] % 2 === 0 ? -30 : 40), duration: 0.45, ease: 'power2.out', stagger: 0.05 }, 3.25)
      .to(good, { y: 0, duration: 0.95, ease: 'power3.inOut', stagger: 0.07 }, 3.7)
      .to(good, { x: 0, duration: 0.6, ease: 'power3.out', stagger: 0.06 }, 4.35);

    // 4. un curseur confirme la sequence, une colonne puis l'autre
    good.forEach((row, i) => {
      const at = 5.05 + i * 0.24;
      tl.to(row, { backgroundColor: 'rgba(168,117,44,.16)', duration: 0.14 }, at).to(
        row,
        { backgroundColor: 'rgba(0,0,0,0)', duration: 0.3 },
        at + 0.28,
      );
    });

    tl.to(badgeGood, { opacity: 1, scale: 1, duration: 0.34, ease: 'back.out(2)' }, 6.55)
      .to([legend1, legend2], { opacity: 0.45, duration: 0.3 }, 6.55)
      .to([badgeBad, badgeGood], { opacity: 0, duration: 0.5 }, 8.4);
  }
}
