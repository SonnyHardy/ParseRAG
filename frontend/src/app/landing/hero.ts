import { Component, ElementRef, OnDestroy, afterNextRender, inject } from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { RAPIDAPI_URL } from './rapidapi';
import { Motion, type MotionApi } from './motion';

/**
 * Accroche, et le triptyque PDF -> ParseRAG -> JSON.
 *
 * L'animation du design est reproduite : les fragments du PDF sont identifies, s'elevent, volent
 * vers le moteur en devenant des chunks, une barre de balayage traverse le moteur, le JSON
 * s'ecrit ligne a ligne et le score de confiance compte jusqu'a 0.94.
 *
 * Difference de fond avec le design : **aucun etat initial n'est pose en CSS**. Le design gatait
 * le contenu par html.pr-motion [data-fade] { opacity: 0 } et devait ensuite le rattraper par un
 * chien de garde si GSAP ne repondait pas. Ici les opacites de depart sont posees par GSAP, donc
 * seulement s'il est charge ; sans JavaScript la page reste dans son etat final.
 *
 * Les faux documents sont des aplats decoratifs, donc aria-hidden.
 */
@Component({
  selector: 'pr-hero',
  imports: [ButtonModule],
  template: `
    <section class="pr-shell hero">
      <div class="hero__intro">
        <div class="pr-kicker" data-intro>The preprocessing layer between PDFs and RAG</div>
        <h1 class="hero__title" data-intro>Turn PDFs into RAG-ready data.</h1>
        <p class="hero__sub" data-intro>
          PDF in. Structured JSON out. Clean reading order, structured tables, confidence on every
          chunk.
        </p>
        <a
          pButton
          class="pr-cta hero__cta"
          data-intro
          [href]="rapidapi"
          target="_blank"
          rel="noopener"
        >
          Try on RapidAPI <span class="pr-cta__arrow pr-mono" aria-hidden="true">&rarr;</span>
        </a>
        <div class="hero__endpoint pr-mono">POST /api/v1/parse</div>
      </div>

      <div class="hero__flow">
        <div class="hero__fx" aria-hidden="true"></div>

        <div class="panel panel--pdf">
          <div class="panel__head pr-mono">research-paper.pdf</div>
          <div class="panel__body mock" aria-hidden="true">
            <div class="mock__bar mock__bar--strong" data-frag data-label="metadata"></div>
            <div class="mock__cols" data-frag data-label="paragraph">
              <div class="mock__col">
                <span></span><span></span><span></span><span class="w68"></span>
              </div>
              <div class="mock__col">
                <span></span><span></span><span></span><span class="w52"></span>
              </div>
            </div>
            <div class="mock__table" data-frag data-label="table">
              <span class="strong"></span><span class="strong"></span><span class="strong"></span>
              <span class="soft"></span><span class="soft"></span><span class="soft"></span>
              <span class="soft"></span><span class="soft"></span><span class="soft"></span>
            </div>
            <div class="mock__cols" data-frag data-label="paragraph">
              <div class="mock__col"><span></span><span class="w74"></span></div>
              <div class="mock__col"><span></span><span class="w58"></span></div>
            </div>
          </div>
        </div>

        <div class="panel panel--engine">
          <div class="engine__scan" aria-hidden="true"></div>
          <div class="panel__head panel__head--dark">
            <span class="pr-mono arrow-coral" aria-hidden="true">&rarr;</span>
            <span class="pr-mono amber">POST /api/v1/parse</span>
          </div>
          <div class="engine__body">
            <picture>
              <source srcset="brand/wordmark-paper.webp" type="image/webp" />
              <img
                src="brand/wordmark-paper.png"
                alt="ParseRAG"
                width="632"
                height="208"
                class="engine__mark"
              />
            </picture>
            <div class="engine__note">Column-aware extraction, verified against its own output.</div>
          </div>
        </div>

        <div class="panel panel--json">
          <div class="panel__head">
            <span class="pr-mono red" aria-hidden="true">&rarr;</span>
            <span class="pr-mono muted">200 OK &middot; application/json</span>
          </div>
          <div class="json pr-mono">
            <div class="json__punct" data-json-line>&#123;</div>
            <div class="json__row" data-json-line>
              <span class="json__key">"type"</span><span class="json__punct">: </span
              ><span class="json__str">"PARAGRAPH"</span><span class="json__punct">,</span>
            </div>
            <div class="json__row" data-json-line>
              <span class="json__key">"page"</span><span class="json__punct">: </span
              ><span class="json__num">1</span><span class="json__punct">,</span>
            </div>
            <div class="json__row json__row--conf" data-json-line>
              <span class="json__conf-key">"confidence"</span><span class="json__punct">: </span
              ><span class="json__conf-val" data-conf>0.94</span>
            </div>
            <div class="json__punct" data-json-line>&#125;</div>
          </div>
        </div>
      </div>

      <div class="ready">
        <span class="pr-mono red ready__dot" aria-hidden="true">&rarr;</span>
        <span class="ready__tag pr-mono">RAG-ready</span>
        <span class="ready__text">Embed the chunks. Skip the flagged ones.</span>
        @if (canReplay) {
          <button type="button" class="ready__replay pr-mono" (click)="replay()">Replay</button>
        }
      </div>
      <div class="hero__built pr-mono">
        Built for RAG apps, document search, knowledge bases
      </div>
    </section>
  `,
  styles: `
    .hero {
      padding-block: 80px 64px;
    }

    .hero__intro {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 24px;
      text-align: center;
      margin-bottom: 64px;
    }

    .hero__title {
      margin: 0;
      font-size: clamp(40px, 6vw, 74px);
      line-height: 1.02;
      font-weight: 600;
      letter-spacing: -0.035em;
      max-width: 18ch;
      text-wrap: balance;
    }

    .hero__sub {
      margin: 0;
      font-size: clamp(19px, 2.2vw, 24px);
      line-height: 1.45;
      color: var(--pr-ink-soft);
      max-width: 34ch;
    }

    .hero__cta { margin-top: 6px; }

    .hero__endpoint {
      font-size: 14px;
      color: var(--pr-muted);
      letter-spacing: 0.02em;
    }

    /* auto-fit plutot qu'un nombre de colonnes fixe : les trois panneaux s'empilent d'eux-memes
       sous 3 x 272 px, sans point de rupture a maintenir. */
    .hero__flow {
      position: relative;
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(272px, 100%), 1fr));
      align-items: stretch;
      gap: 14px;
    }

    /* Calque des fragments en vol. Vide au repos, rempli par l'animation puis re-vide : rien a
       nettoyer si le script ne tourne pas. */
    .hero__fx {
      position: absolute;
      inset: 0;
      z-index: 5;
      pointer-events: none;
      overflow: visible;
    }

    .panel {
      box-sizing: border-box;
      display: flex;
      flex-direction: column;
      border: 1px solid var(--pr-hairline);
    }

    .panel--pdf { background: var(--pr-surface); }
    .panel--json { background: var(--pr-paper); }

    .panel--engine {
      position: relative;
      overflow: hidden;
      border: 0;
      background: var(--pr-dark);
      gap: 12px;
    }

    .engine__scan {
      position: absolute;
      left: 0;
      right: 0;
      top: 0;
      height: 2px;
      background: var(--pr-amber);
      opacity: 0;
    }

    .panel__head {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 10px 16px;
      border-bottom: 1px solid var(--pr-hairline);
      font-size: 12px;
      color: var(--pr-muted);
    }

    .panel__head--dark { border-bottom-color: var(--pr-dark-border); }

    .panel__body {
      padding: 20px 18px;
      display: flex;
      flex-direction: column;
      gap: 11px;
      flex: 1;
    }

    .arrow-coral { font-size: 15px; color: var(--pr-code-keyword); }
    .amber { font-size: 12px; color: var(--pr-amber); }
    .red { font-size: 15px; color: var(--pr-red); }
    .muted { font-size: 12px; color: var(--pr-muted); }

    /* ---- Faux document ---- */
    .mock__bar--strong { height: 8px; background: var(--pr-mock-strong); }

    .mock__cols { display: flex; gap: 12px; }

    .mock__col {
      flex: 1;
      display: flex;
      flex-direction: column;
      gap: 5px;

      span { height: 5px; background: var(--pr-mock); }
      .w68 { width: 68%; }
      .w74 { width: 74%; }
      .w52 { width: 52%; }
      .w58 { width: 58%; }
    }

    .mock__table {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: 3px;
      border: 1px solid var(--pr-hairline);
      padding: 5px;

      span { height: 5px; }
      .strong { background: var(--pr-mock-strong); }
      .soft { background: var(--pr-mock-soft); }
    }

    /* ---- Moteur ---- */
    .engine__body {
      padding: 6px 24px 26px;
      display: flex;
      flex-direction: column;
      gap: 14px;
      flex: 1;
      justify-content: center;
    }

    .engine__mark {
      display: block;
      height: 26px;
      width: auto;
      aspect-ratio: 632 / 208;
      align-self: flex-start;
      flex: none;
    }

    .engine__note {
      font-size: 14px;
      line-height: 1.5;
      color: var(--pr-on-dark-muted);
    }

    /* ---- JSON ---- */
    .json {
      padding: 20px 18px;
      font-size: 14px;
      line-height: 1.75;
      flex: 1;
    }

    .json__row { padding-left: 14px; }
    .json__punct { color: var(--pr-muted); }
    .json__key { color: var(--pr-ink-soft); }
    .json__str { color: var(--pr-ochre-text); }
    .json__num { color: var(--pr-ink); font-weight: 500; }

    /* La ligne mise en avant : c'est le champ qui differencie le produit, il porte donc le seul
       aplat rouge du panneau. */
    .json__row--conf {
      margin: 2px -18px;
      padding-right: 18px;
      background: rgb(140 47 38 / 10%);
      box-shadow: inset 4px 0 0 var(--pr-red);
    }

    .json__conf-key { padding-left: 18px; color: var(--pr-red); font-weight: 700; }
    .json__conf-val { color: var(--pr-red); font-weight: 700; display: inline-block; }

    /* ---- Bandeau RAG-ready ---- */
    .ready {
      display: flex;
      align-items: center;
      gap: 14px;
      flex-wrap: wrap;
      margin-top: 14px;
      padding: 14px 18px;
      background: var(--pr-surface);
      border: 1px solid var(--pr-hairline);
    }

    .ready__tag {
      font-size: 12px;
      letter-spacing: 0.16em;
      text-transform: uppercase;
      color: var(--pr-red);
    }

    .ready__text { font-size: 16px; color: var(--pr-ink-soft); }

    .ready__replay {
      appearance: none;
      margin-left: auto;
      border: 1px solid var(--pr-hairline);
      background: var(--pr-paper);
      color: var(--pr-muted);
      font-size: 11px;
      letter-spacing: 0.12em;
      text-transform: uppercase;
      padding: 7px 12px;
      cursor: pointer;
      border-radius: 3px;
      transition: border-color 0.2s ease, color 0.2s ease;

      &:hover { border-color: var(--pr-red); color: var(--pr-red); }
    }

    .hero__built {
      margin-top: 10px;
      font-size: 13px;
      color: var(--pr-muted);
    }
  `,
})
export class Hero implements OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly motion = inject(Motion);
  private api: MotionApi | null = null;
  private timelines: gsap.core.Timeline[] = [];

  protected readonly rapidapi = RAPIDAPI_URL;

  /**
   * Le bouton Replay n'existe que si l'animation existe. Le design l'affichait toujours, y compris
   * quand le mouvement etait coupe : il ne rejouait alors rien, ce qui est pire qu'une absence.
   */
  protected canReplay = false;

  constructor() {
    afterNextRender(async () => {
      this.api = await this.motion.load();
      if (!this.api) {
        return;
      }
      this.canReplay = true;
      this.intro();
      this.motion.onEnter(this.api, this.host.nativeElement.querySelector('.hero__flow'), () =>
        this.run(),
      { once: true },
      );
    });
  }

  ngOnDestroy(): void {
    this.timelines.forEach((tl) => tl.kill());
  }

  protected replay(): void {
    this.run();
  }

  /** Arrivee de l'accroche : le seul mouvement qui se joue sans attendre le defilement. */
  private intro(): void {
    const { gsap } = this.api!;
    const items = this.host.nativeElement.querySelectorAll('[data-intro]');
    gsap.fromTo(
      items,
      { opacity: 0, y: 16 },
      { opacity: 1, y: 0, duration: 0.7, ease: 'power3.out', stagger: 0.1 },
    );
  }

  private q<T extends Element>(selector: string): T | null {
    return this.host.nativeElement.querySelector<T>(selector);
  }

  /**
   * Le recit du triptyque. Les positions sont mesurees au moment de jouer, jamais figees : la
   * grille passe de trois colonnes a une seule selon la largeur, et des coordonnees calculees une
   * fois pour toutes enverraient les fragments hors de l'ecran sur mobile.
   */
  private run(): void {
    const api = this.api;
    if (!api) {
      return;
    }
    const { gsap } = api;
    const flow = this.q<HTMLElement>('.hero__flow');
    const fx = this.q<HTMLElement>('.hero__fx');
    const engine = this.q<HTMLElement>('.panel--engine');
    const frags = Array.from(this.host.nativeElement.querySelectorAll<HTMLElement>('[data-frag]'));
    const jsonLines = Array.from(
      this.host.nativeElement.querySelectorAll<HTMLElement>('[data-json-line]'),
    );
    if (!flow || !fx || !engine || !frags.length) {
      return;
    }

    this.timelines.forEach((tl) => tl.kill());
    this.timelines = [];
    fx.innerHTML = '';

    const flowRect = flow.getBoundingClientRect();
    const engineRect = engine.getBoundingClientRect();
    // Grille empilee : le moteur est sous le PDF et non a sa droite. Le vol devient alors vertical
    // seul, sinon les etiquettes partiraient sur le cote, hors du panneau.
    const stacked = engineRect.left < flowRect.left + 8;

    const chips = frags.map((frag) => {
      const rect = frag.getBoundingClientRect();
      const chip = document.createElement('div');
      chip.textContent = frag.dataset['label'] ?? '';
      chip.className = 'hero__chip';
      Object.assign(chip.style, {
        position: 'absolute',
        boxSizing: 'border-box',
        left: `${rect.left - flowRect.left}px`,
        top: `${rect.top - flowRect.top - 3}px`,
        width: `${rect.width}px`,
        height: `${Math.max(rect.height + 6, 16)}px`,
        border: '1px solid var(--pr-red)',
        background: 'rgb(140 47 38 / 7%)',
        color: 'var(--pr-red)',
        font: '700 9px/1 var(--pr-font-mono)',
        letterSpacing: '.14em',
        textTransform: 'uppercase',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'flex-end',
        padding: '0 5px',
        opacity: '0',
        willChange: 'transform, opacity',
      });
      fx.appendChild(chip);
      return { el: chip, rect, label: chip.textContent };
    });

    const liftX = chips.map((c) => (stacked ? 0 : Math.min(46, (engineRect.left - c.rect.left) * 0.22)));
    const dx = chips.map((c) => (stacked ? 0 : engineRect.left + 20 - c.rect.left));
    const dy = chips.map((c, i) => engineRect.top + (stacked ? 24 : 58) + i * 19 - c.rect.top);
    const els = chips.map((c) => c.el);

    const conf = this.q<HTMLElement>('[data-conf]');
    const scan = this.q<HTMLElement>('.engine__scan');
    const pdf = this.q<HTMLElement>('.panel--pdf');
    const ready = this.q<HTMLElement>('.ready');

    if (conf) {
      conf.textContent = '0.00';
    }

    const tl = gsap.timeline();
    this.timelines.push(tl);

    // 1. la page se separe : le contenu s'attenue, les fragments sont detoures
    tl.to(frags, { opacity: 0.26, duration: 0.5, ease: 'power2.inOut', stagger: 0.07 }, 0)
      .to(els, { opacity: 1, duration: 0.3, ease: 'power2.out', stagger: 0.09 }, 0.2)
      // 2. anticipation : un leger decollement avant le long deplacement
      .to(
        els,
        {
          x: (i: number) => liftX[i],
          y: -5,
          scale: 1.03,
          duration: 0.42,
          ease: 'power2.out',
          stagger: 0.07,
          transformOrigin: '0% 50%',
        },
        0.75,
      )
      // 3. le vol. En cours de route l'etiquette passe de la region de mise en page au chunk :
      //    c'est la le propos de toute l'animation.
      .to(
        els,
        {
          x: (i: number) => dx[i],
          y: (i: number) => dy[i],
          scale: 1,
          width: Math.max(engineRect.width - 40, 90),
          height: 14,
          duration: 1.25,
          ease: 'power3.inOut',
          stagger: 0.13,
          onStart: () => {
            chips.forEach((chip, i) => {
              setTimeout(
                () => {
                  chip.el.textContent = `chunk_00${i} · ${chip.label}`;
                  chip.el.style.justifyContent = 'space-between';
                },
                520 + i * 130,
              );
            });
          },
        },
        1.35,
      )
      // camera : la mise au point se resserre sur la couche de parsing
      .to(pdf, { opacity: 0.55, scale: 0.985, duration: 0.8, ease: 'power2.inOut', transformOrigin: '0% 50%' }, 1.5)
      .to(engine, { scale: 1.02, duration: 0.9, ease: 'power2.inOut' }, 2.2)
      .to(engine, { scale: 1, duration: 1, ease: 'power2.inOut' }, 3.4)
      .to(pdf, { opacity: 1, scale: 1, duration: 1, ease: 'power2.out' }, 3.5)
      // 4. le moteur s'active sur les fragments qui arrivent
      .to(scan, { opacity: 1, duration: 0.12 }, 2.35)
      .to(scan, { y: engineRect.height, duration: 0.95, ease: 'power2.inOut' }, 2.45)
      .to(scan, { opacity: 0, duration: 0.22 }, 3.3)
      .to(els, { x: (i: number) => dx[i] + 10, opacity: 0, scale: 0.97, duration: 0.38, ease: 'power2.in', stagger: 0.07 }, 2.95)
      // 5. la sortie structuree s'assemble
      .to(jsonLines, { opacity: 1, y: 0, duration: 0.36, ease: 'power2.out', stagger: 0.11 }, 3.25)
      .to(frags, { opacity: 1, duration: 0.7, ease: 'power2.out', stagger: 0.05 }, 3.5);

    gsap.set(jsonLines, { opacity: 0, y: 6 });
    gsap.set(ready, { opacity: 0, y: 12 });

    if (conf) {
      const counter = { v: 0 };
      tl.to(
        counter,
        {
          v: 0.94,
          duration: 1.05,
          ease: 'power2.out',
          onUpdate: () => {
            conf.textContent = counter.v.toFixed(2);
          },
        },
        3.6,
      ).fromTo(
        conf,
        { scale: 1 },
        { scale: 1.14, duration: 0.16, yoyo: true, repeat: 1, ease: 'power2.out', transformOrigin: '0% 50%' },
        4.7,
      );
    }

    tl.to(ready, { opacity: 1, y: 0, duration: 0.55, ease: 'power3.out' }, 4.9).add(() => {
      fx.innerHTML = '';
      this.ambient();
    }, 5.5);
  }

  /** Apres la resolution : le systeme reste discretement vivant, sans jamais reclamer l'attention. */
  private ambient(): void {
    const api = this.api;
    if (!api) {
      return;
    }
    const { gsap } = api;
    const dot = this.q<HTMLElement>('.ready__dot');
    const conf = this.q<HTMLElement>('[data-conf]');
    const tl = gsap.timeline({ repeat: -1, repeatDelay: 3.4 });
    this.timelines.push(tl);
    if (dot) {
      tl.fromTo(dot, { opacity: 1 }, { opacity: 0.35, duration: 0.7, yoyo: true, repeat: 1, ease: 'sine.inOut' }, 0);
    }
    if (conf) {
      tl.fromTo(conf, { opacity: 1 }, { opacity: 0.5, duration: 0.5, yoyo: true, repeat: 1, ease: 'sine.inOut' }, 1.1);
    }
  }
}
