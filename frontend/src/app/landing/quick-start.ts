import {
  Component,
  ElementRef,
  OnDestroy,
  ViewEncapsulation,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { TabsModule } from 'primeng/tabs';
import { RAPIDAPI_URL } from './rapidapi';
import { RESPONSE_LINES, SNIPPETS, type Snippet } from './code-snippets';
import { Motion, type MotionApi } from './motion';

/**
 * « Parse a PDF in one request » : les trois exemples d'appel et la reponse.
 *
 * Les onglets sont un p-tabs PrimeNG plutot que trois boutons maison. Ce n'est pas pour le
 * style, entierement redefini ci-dessous, mais pour ce qu'on n'aurait pas ecrit autrement : roles
 * tablist / tab / tabpanel, aria-selected, et la navigation aux fleches.
 *
 * Point qui sert le referencement : PrimeNG rend le contenu des onglets inactifs plutot que de
 * l'omettre. Les trois exemples sont donc dans le HTML prerendu, et un agent qui lit la page les
 * trouve tous les trois sans avoir a cliquer (issue #72).
 *
 * encapsulation: None est delibere : redefinir le rendu interne de PrimeNG demande sinon
 * ::ng-deep, deprecie. Tout est porte par la classe racine qs, donc rien ne fuit hors de
 * cette section.
 */
@Component({
  selector: 'pr-quick-start',
  imports: [ButtonModule, TabsModule],
  encapsulation: ViewEncapsulation.None,
  template: `
    <section id="start" class="qs">
      <div class="pr-shell qs__inner">
        <h2 class="qs__title">Parse a PDF in one request.</h2>

        <div class="qs__grid">
          <div class="qs__code">
            <p-tabs [value]="lang()" (valueChange)="onLang($event)">
              <p-tablist>
                @for (snippet of snippets; track snippet.id) {
                  <p-tab [value]="snippet.id">{{ snippet.label }}</p-tab>
                }
              </p-tablist>

              <button
                type="button"
                class="qs__copy pr-mono"
                (click)="copy()"
                [attr.aria-label]="'Copy the ' + lang() + ' example'"
              >
                <span>{{ copied() ? 'Copied' : 'Copy' }}</span>
                @if (copied()) {
                  <span class="qs__check" aria-hidden="true">&#10003;</span>
                }
              </button>

              <p-tabpanels>
                @for (snippet of snippets; track snippet.id) {
                  <p-tabpanel [value]="snippet.id">
                    <div class="pr-scroll-x">
                      <div class="qs__pre pr-mono">
                        @for (line of snippet.lines; track $index; let last = $last) {
                          <div class="qs__line">
                            @for (token of line; track $index) {
                              <span [class]="'tk tk--' + token[0]">{{ token[1] }}</span>
                            }
                            @if (last) {
                              <span class="qs__caret" aria-hidden="true"></span>
                            }
                          </div>
                        }
                      </div>
                    </div>
                  </p-tabpanel>
                }
              </p-tabpanels>
            </p-tabs>
          </div>

          <div class="qs__response">
            <div class="qs__response-head pr-mono">
              <span>Response</span>
              <span class="qs__req" aria-hidden="true">
                <span class="qs__req-dot"></span>request sent
              </span>
              <span class="qs__pill">200 OK <span class="dim">1.84s</span></span>
            </div>
            <div class="pr-scroll-x">
              <div class="qs__pre pr-mono">
                @for (line of responseLines; track $index) {
                  <div class="qs__resp-line">
                    @for (token of line; track $index) {
                      <span [class]="'tk tk--' + token[0]">{{ token[1] }}</span>
                    }
                  </div>
                }
              </div>
            </div>
          </div>
        </div>

        <div class="qs__foot">
          <a pButton class="pr-cta pr-cta--md" [href]="rapidapi" target="_blank" rel="noopener">
            Try it on RapidAPI
            <span class="pr-cta__arrow pr-mono" aria-hidden="true">&rarr;</span>
          </a>
          <span class="qs__note">Your RapidAPI key is the only credential.</span>
        </div>
      </div>
    </section>
  `,
  styles: `
    .qs {
      background: var(--pr-dark);
      color: var(--pr-on-dark);
    }

    .qs .qs__inner { padding-block: 88px; }

    .qs .qs__title {
      margin: 0 0 36px;
      font-size: clamp(28px, 3.4vw, 42px);
      font-weight: 600;
      letter-spacing: -0.028em;
      line-height: 1.08;
      color: var(--pr-on-dark);
    }

    /* flex-start et non stretch : la reponse complete fait 18 lignes contre 4 pour l'exemple
       cURL, et etirer le panneau court a la hauteur du long laissait un grand vide sous le code.
       Chaque panneau prend desormais sa propre hauteur, alignes par le haut. */
    .qs .qs__grid {
      display: flex;
      gap: 24px;
      align-items: flex-start;
      flex-wrap: wrap;
    }

    .qs .qs__code {
      flex: 1 1 460px;
      min-width: 0;
      border: 1px solid var(--pr-dark-border);
      position: relative;
    }

    .qs .qs__response {
      flex: 1 1 340px;
      min-width: 0;
      border: 1px solid var(--pr-dark-border);
      display: flex;
      flex-direction: column;
    }

    .qs .qs__response-head {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 13px 20px;
      background: var(--pr-dark-deep);
      border-bottom: 1px solid var(--pr-dark-border);
      font-size: 14px;
      color: var(--pr-on-dark-muted);
    }

    /* Cache au repos, en CSS. C'est l'exception a la regle « aucun etat initial en CSS », et pour
       la meme raison que les cadres de detection des cas limites : « request sent » est un
       artefact d'animation, pas un contenu. Affiche a cote de « 200 OK 1.84s » sur une page sans
       animation, il raconte deux etats contradictoires du meme appel. */
    .qs .qs__req {
      opacity: 0;
      display: inline-flex;
      align-items: center;
      gap: 7px;
      font-size: 12px;
      color: var(--pr-amber);
    }

    .qs .qs__req-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: var(--pr-amber);
      display: inline-block;
    }

    .qs .qs__pill {
      margin-left: auto;
      display: inline-flex;
      align-items: center;
      gap: 8px;
      padding: 3px 9px;
      border: 1px solid var(--pr-dark-border-soft);
      border-radius: 3px;
      font-size: 12px;
      color: var(--pr-code-string);
    }

    .qs .dim { color: var(--pr-muted); }

    .qs .qs__pre {
      padding: 24px 26px;
      font-size: 15px;
      line-height: 1.85;
      color: var(--pr-code-text);
      white-space: pre;
    }

    /* ---- Coloration syntaxique ---- */
    .qs .tk--k { color: var(--pr-code-keyword); }
    .qs .tk--f { color: var(--pr-code-fn); }
    .qs .tk--s { color: var(--pr-code-string); }
    .qs .tk--p { color: var(--pr-code-punct); }
    .qs .tk--t { color: var(--pr-code-text); }

    .qs .qs__caret {
      display: inline-block;
      width: 8px;
      height: 16px;
      margin-left: 4px;
      vertical-align: -3px;
      background: var(--pr-amber);
      animation: qs-caret 1s step-end infinite;
    }

    @keyframes qs-caret {
      0%, 45% { opacity: 1; }
      55%, 100% { opacity: 0; }
    }

    /* ---- Redefinition du rendu PrimeNG ----
       Le theme Aura est clair et arrondi ; cette section est sombre et a angles vifs. On ne
       surcharge que ce qui se voit : fond, filet, couleur active et barre d'onglet actif. */
    .qs p-tabs,
    .qs .p-tabs { background: transparent; color: inherit; }

    .qs .p-tablist { background: var(--pr-dark-deep); }

    .qs .p-tablist-tab-list {
      background: var(--pr-dark-deep);
      border-color: var(--pr-dark-border);
      border-width: 0 0 1px;
      padding-right: 110px; /* laisse la place au bouton Copy, pose en absolu */
    }

    .qs .p-tab {
      background: transparent;
      border: 0;
      padding: 13px 20px 11px;
      font-family: var(--pr-font-mono);
      font-size: 14px;
      color: var(--pr-on-dark-muted);
      transition: color 0.2s ease;
    }

    .qs .p-tab:hover { color: var(--pr-on-dark-soft); }

    .qs .p-tab[data-p-active='true'] {
      color: var(--pr-on-dark-soft);
      font-weight: 700;
    }

    .qs .p-tablist-active-bar { background: var(--pr-amber); height: 2px; }

    .qs .p-tabpanels {
      background: transparent;
      color: inherit;
      padding: 0;
    }

    .qs .qs__copy {
      position: absolute;
      top: 8px;
      right: 14px;
      z-index: 1;
      appearance: none;
      padding: 7px 13px;
      background: var(--pr-dark-raised);
      border: 1px solid var(--pr-dark-border-soft);
      color: var(--pr-on-dark-soft);
      font-size: 12px;
      letter-spacing: 0.08em;
      text-transform: uppercase;
      cursor: pointer;
      border-radius: 3px;
      display: flex;
      align-items: center;
      gap: 8px;
      transition: background 0.2s ease, border-color 0.2s ease;
    }

    .qs .qs__copy:hover {
      background: var(--pr-dark-border);
      border-color: var(--pr-dark-border-hover);
    }

    .qs .qs__check { color: var(--pr-amber); }

    .qs .qs__foot {
      display: flex;
      align-items: center;
      gap: 20px;
      flex-wrap: wrap;
      margin-top: 32px;
    }

    .qs .qs__note {
      font-size: 16px;
      color: var(--pr-on-dark-muted);
    }
  `,
})
export class QuickStart implements OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly motion = inject(Motion);
  private api: MotionApi | null = null;
  private timeline?: gsap.core.Timeline;
  private responseTimeline?: gsap.core.Timeline;
  private responseShown = false;

  protected readonly rapidapi = RAPIDAPI_URL;
  protected readonly snippets = SNIPPETS;
  protected readonly responseLines = RESPONSE_LINES;
  protected readonly lang = signal<Snippet['id']>('curl');
  protected readonly copied = signal(false);
  protected readonly current = computed(
    () => this.snippets.find((s) => s.id === this.lang()) ?? this.snippets[0],
  );

  constructor() {
    afterNextRender(async () => {
      this.api = await this.motion.load();
      if (!this.api) {
        return;
      }
      this.motion.onEnter(
        this.api,
        this.host.nativeElement.querySelector('.qs__grid'),
        () => {
          this.typeCode();
          this.revealResponse();
        },
        { once: true },
      );
    });

    // Rejoue la frappe au changement d'onglet, comme le design. Seul le code est retape : la
    // reponse ne depend pas du langage choisi, et la remasquer a chaque clic la ferait disparaitre
    // sous les yeux du lecteur.
    effect(() => {
      this.lang();
      if (this.api) {
        queueMicrotask(() => this.typeCode());
      }
    });
  }

  ngOnDestroy(): void {
    this.timeline?.kill();
    this.responseTimeline?.kill();
  }

  protected onLang(value: unknown): void {
    this.lang.set(value as Snippet['id']);
    this.copied.set(false);
  }

  protected async copy(): Promise<void> {
    // `navigator` n'existe pas au prerendu, et l'API Clipboard manque hors contexte securise :
    // les deux gardes evitent une exception sur un bouton purement pratique.
    if (typeof navigator === 'undefined' || !navigator.clipboard) {
      return;
    }
    try {
      await navigator.clipboard.writeText(this.current().code);
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 2000);
    } catch {
      // Copie refusee par le navigateur : le code reste selectionnable a la main.
    }
  }

  /**
   * La frappe du design : chaque ligne se decouvre de gauche a droite par clip-path.
   *
   * clip-path plutot qu'un ajout de caracteres un par un : le texte complet est dans le DOM des le
   * depart, donc selectionnable, copiable et lisible par un extracteur pendant l'animation.
   */
  private typeCode(): void {
    const api = this.api;
    const root = this.host.nativeElement;
    if (!api) {
      return;
    }
    this.timeline?.kill();

    const panel = root.querySelector('.p-tabpanel:not([hidden])') ?? root;
    const lines = Array.from(panel.querySelectorAll('.qs__line'));
    const caret = panel.querySelector('.qs__caret');
    if (!lines.length) {
      return;
    }

    const { gsap } = api;
    const tl = gsap.timeline();
    this.timeline = tl;

    gsap.set(lines, { clipPath: 'inset(0 100% 0 0)' });
    gsap.set(caret, { opacity: 0 });

    let at = 0;
    for (const line of lines) {
      const chars = Math.max((line.textContent ?? '').length, 4);
      const duration = Math.min(0.06 + chars * 0.012, 0.55);
      tl.to(line, { clipPath: 'inset(0 0% 0 0)', duration, ease: 'none' }, at);
      at += duration + 0.04;
    }

    tl.to(caret, { opacity: 1, duration: 0.1 }, at);
  }

  /**
   * La requete part, le statut tombe, la reponse s'ecrit ligne a ligne. **Une seule fois.**
   *
   * Le premier jet rejouait cette sequence a chaque changement d'onglet et a chaque retour dans la
   * section, en remettant les lignes a opacity: 0 au debut. Une interruption laissait donc le
   * panneau vide, ce qui est precisement le defaut reproche au design : un contenu dont l'existence
   * depend du bon deroulement d'une animation. Ici, une fois revelee, la reponse reste.
   */
  private revealResponse(): void {
    const api = this.api;
    const root = this.host.nativeElement;
    if (!api || this.responseShown) {
      return;
    }
    this.responseShown = true;

    const request = root.querySelector('.qs__req');
    const dot = root.querySelector('.qs__req-dot');
    const pill = root.querySelector('.qs__pill');
    const lines = Array.from(root.querySelectorAll('.qs__resp-line'));
    if (!lines.length) {
      return;
    }

    const { gsap } = api;
    const tl = gsap.timeline();
    this.responseTimeline = tl;

    gsap.set([request, pill].filter(Boolean), { opacity: 0 });
    gsap.set(lines, { opacity: 0, x: -4 });

    tl.to(request, { opacity: 1, duration: 0.25 }, 0.3)
      .fromTo(
        dot,
        { opacity: 1 },
        { opacity: 0.2, duration: 0.28, yoyo: true, repeat: 3, ease: 'sine.inOut' },
        0.35,
      )
      .to(request, { opacity: 0, duration: 0.22 }, 1.5)
      .fromTo(pill, { opacity: 0, y: -4 }, { opacity: 1, y: 0, duration: 0.3, ease: 'power2.out' }, 1.6)
      .to(lines, { opacity: 1, x: 0, duration: 0.22, stagger: 0.055, ease: 'power1.out' }, 1.8);
  }
}
