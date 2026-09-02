import {
  Component,
  ElementRef,
  OnDestroy,
  afterNextRender,
  inject,
  PLATFORM_ID,
} from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { RAPIDAPI_URL } from './rapidapi';
import { Motion } from './motion';

/**
 * Barre de navigation collante, avec la jauge de progression du design.
 *
 * La jauge est le seul mouvement de la page qui ne passe pas par GSAP : c'est une largeur en
 * pourcentage recalculee au defilement, et l'importer pour cela serait disproportionne. L'ecouteur
 * est passif et etrangle par requestAnimationFrame, sinon il s'execute a chaque cran de molette
 * sur le chemin le plus chaud de la page.
 *
 * Elle demarre a 0 %, ce qui est exact au chargement, donc rien a corriger si le script ne tourne
 * pas : la jauge reste simplement vide.
 */
@Component({
  selector: 'pr-site-header',
  imports: [ButtonModule, RouterLink],
  template: `
    <header class="hd">
      <nav class="hd__nav pr-shell" aria-label="Main">
        <a routerLink="/" aria-label="ParseRAG home" class="hd__home">
          <img
            src="brand/wordmark-ink.png"
            alt="ParseRAG"
            width="632"
            height="208"
            class="hd__mark"
          />
        </a>
        <div class="hd__links">
          <a routerLink="/" fragment="how" class="hd__link">How it works</a>
          <a routerLink="/" fragment="output" class="hd__link">Output</a>
          <a routerLink="/" fragment="start" class="hd__link">Quick start</a>
          <a
            pButton
            class="pr-cta pr-cta--sm"
            [href]="rapidapi"
            target="_blank"
            rel="noopener"
          >
            Try on RapidAPI <span class="pr-cta__arrow pr-mono" aria-hidden="true">&rarr;</span>
          </a>
        </div>
      </nav>
      <div class="hd__gauge" aria-hidden="true"><div class="hd__gauge-fill"></div></div>
    </header>
  `,
  styles: `
    .hd {
      position: sticky;
      top: 0;
      z-index: 50;
      background: var(--pr-surface);
      border-bottom: 1px solid var(--pr-hairline);
    }

    .hd__nav {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 8px 24px;
      padding-block: 14px;
    }

    .hd__home {
      display: flex;
      align-items: center;
      flex: none;
    }

    /* aspect-ratio fige la place de l'image avant son chargement : sans lui la barre saute
       au moment ou le wordmark arrive, et le decalage compte dans les Core Web Vitals. */
    .hd__mark {
      display: block;
      height: 40px;
      width: auto;
      aspect-ratio: 632 / 208;
      flex: none;
    }

    .hd__links {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: 8px 20px;
      margin-left: auto;
    }

    .hd__link {
      font-size: 15px;
      font-weight: 500;
      color: var(--pr-ink-soft);
      white-space: nowrap;

      &:hover {
        color: var(--pr-red);
        text-decoration: none;
      }
    }

    .hd__gauge { height: 2px; background: transparent; }

    .hd__gauge-fill {
      height: 2px;
      width: 0%;
      background: var(--pr-red);
    }
  `,
})
export class SiteHeader implements OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly motion = inject(Motion);
  private frame = 0;
  private onScroll?: () => void;

  protected readonly rapidapi = RAPIDAPI_URL;

  constructor() {
    afterNextRender(() => {
      if (!isPlatformBrowser(this.platformId) || !this.motion.enabled) {
        return;
      }
      const fill = this.host.nativeElement.querySelector<HTMLElement>('.hd__gauge-fill');
      if (!fill) {
        return;
      }
      this.onScroll = () => {
        if (this.frame) {
          return;
        }
        this.frame = requestAnimationFrame(() => {
          this.frame = 0;
          const scrollable = document.documentElement.scrollHeight - window.innerHeight;
          const ratio = scrollable > 0 ? (window.scrollY / scrollable) * 100 : 0;
          fill.style.width = `${Math.min(100, Math.max(0, ratio))}%`;
        });
      };
      window.addEventListener('scroll', this.onScroll, { passive: true });
      this.onScroll();
    });
  }

  ngOnDestroy(): void {
    if (this.onScroll) {
      window.removeEventListener('scroll', this.onScroll);
    }
    if (this.frame) {
      cancelAnimationFrame(this.frame);
    }
  }
}
