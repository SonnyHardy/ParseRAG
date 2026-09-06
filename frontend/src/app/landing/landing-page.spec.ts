import { DeferBlockBehavior, DeferBlockState, TestBed } from '@angular/core/testing';
import { providePrimeNG } from 'primeng/config';
import { LandingPage } from './landing-page';
import { RAPIDAPI_URL } from './rapidapi';
import { provideRouter } from '@angular/router';

/**
 * Ce que ces tests protegent n'est pas l'apparence, qui se juge a l'oeil et changera, mais les
 * proprietes dont dependent les issues suivantes : une structure de titres exploitable (#71), un
 * contenu present sans JavaScript (#70, #72) et des liens sortants suivables (#74).
 *
 * **Sur « present sans JavaScript », ces tests ne prouvent rien**, et il faut le savoir en les
 * lisant : ils montent les composants dans un navigateur simule, donc ils mesurent le DOM apres
 * execution. La vraie garantie est ailleurs, dans scripts/check-prerender.mjs, qui lit le HTML
 * produit par le build. Ce que les tests ci-dessous protegent, c'est la structure du contenu.
 */
describe('Landing page', () => {
  let root: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LandingPage],
      // Le routeur est requis depuis que le pied de page pointe vers /terms et /privacy par
      // `routerLink` : sans lui la directive n'a pas de contexte et le montage echoue.
      providers: [providePrimeNG({}), provideRouter([])],
      // Les sections sont dans des blocs `@defer (hydrate on viewport)` depuis l'issue #70. Le
      // comportement par defaut de TestBed est `Manual` : les blocs ne rendent rien tant qu'on ne
      // les declenche pas un par un, et dix sections sur onze disparaissaient de ces tests.
      // `Playthrough` reproduit le comportement du navigateur.
      deferBlockBehavior: DeferBlockBehavior.Playthrough,
    }).compileComponents();

    const fixture = TestBed.createComponent(LandingPage);
    fixture.detectChanges();
    await fixture.whenStable();

    // Les blocs `@defer (hydrate on viewport)` de l'issue #70 ne se declenchent pas d'eux-memes
    // ici : leurs declencheurs `hydrate` n'existent que pendant l'hydratation d'un rendu serveur,
    // et TestBed fait un rendu client. On les amene donc explicitement a leur etat complet, qui
    // est celui d'un visiteur ayant fait defiler la page.
    for (const block of await fixture.getDeferBlocks()) {
      await block.render(DeferBlockState.Complete);
    }
    await fixture.whenStable();

    root = fixture.nativeElement as HTMLElement;
  });

  it('rend un h1 unique', () => {
    const titles = root.querySelectorAll('h1');
    expect(titles.length).toBe(1);
    expect(titles[0].textContent).toContain('Turn PDFs into RAG-ready data');
  });

  it('rend les dix sections de la page', () => {
    const headings = Array.from(root.querySelectorAll('h1, h2')).map((h) => h.textContent?.trim());
    for (const expected of [
      'Turn PDFs into RAG-ready data.',
      'Parse a PDF in one request.',
      'PDF extraction is quietly wrong.',
      'Bad extraction becomes bad retrieval.',
      'From PDF to RAG-ready chunks.',
      'Every chunk tells you how much to trust it.',
      'Built for the PDFs that break parsers.',
      'Metered by requests, bounded by pages.',
      'Before you subscribe.',
      'Give your RAG pipeline cleaner input.',
    ]) {
      expect(headings).toContain(expected);
    }
  });

  // La page n'a qu'une conversion. Un CTA qui pointerait ailleurs, ou qui perdrait `noopener`,
  // ne se verrait pas a la relecture d'un diff de gabarit.
  it('envoie tous ses CTA vers le listing RapidAPI', () => {
    const ctas = Array.from(root.querySelectorAll<HTMLAnchorElement>('a[target="_blank"]'));
    expect(ctas.length).toBe(6);
    for (const cta of ctas) {
      expect(cta.getAttribute('href')).toBe(RAPIDAPI_URL);
      expect(cta.getAttribute('rel')).toContain('noopener');
    }
  });

  // PrimeNG rend les onglets inactifs plutot que de les omettre : les trois exemples doivent
  // etre lisibles sans clic, c'est ce dont depend l'issue #72.
  it('rend les trois exemples de code sans interaction', () => {
    const text = root.textContent ?? '';
    expect(text).toContain('curl -X POST');
    expect(text).toContain('import requests');
    expect(text).toContain('MultipartBody.Builder');
  });

  // Les cinq etapes sont dans le DOM des le premier rendu ; le defilement ne fait qu'en mettre
  // une en avant. Si l'une d'elles devenait conditionnelle, la page perdrait un cinquieme de son
  // contenu indexable sans qu'aucun rendu ne change a l'ecran.
  // Le panneau ferme d'un accordeon PrimeNG reste dans le DOM. C'est ce qui permet a un moteur et
  // a un agent de lire les six reponses sans cliquer (issue #72), et c'est exactement ce qu'une
  // implementation maison a base de @if detruirait sans que rien ne change a l'ecran.
  it('rend les six reponses de la FAQ sans ouvrir les panneaux', () => {
    const text = root.textContent ?? '';
    for (const answer of [
      'Image-only pages skip native extraction',
      'Text extraction is language-agnostic',
      'TABLE chunks carry a table_json',
      'The flag is deliberately cautious',
      'A native-text document of 200 pages',
      'Send documents one at a time',
    ]) {
      expect(text).toContain(answer);
    }
  });

  // Le tableau des plans est la derniere chose qu'un lecteur consulte avant de s'abonner, et
  // l'une des premieres qu'un agent cite. Les quatre lignes doivent donc etre dans le HTML
  // prerendu, et chaque chiffre rattache a sa colonne par un vrai en-tete : c'est ce qui
  // distingue un tableau lisible d'une grille de div qui a le meme rendu (issues #71, #72).
  it('rend la grille des plans dans un tableau annonce', () => {
    const table = root.querySelector('#plans table');
    expect(table).not.toBeNull();
    expect(table?.querySelector('caption')?.textContent).toContain('Prices are on the RapidAPI');
    expect(table!.querySelectorAll('thead th[scope="col"]').length).toBe(4);

    const rows = Array.from(table!.querySelectorAll('tbody tr'));
    expect(rows.length).toBe(4);
    const squash = (el: Element | null | undefined) =>
      (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
    expect(rows.map((row) => squash(row.querySelector('th[scope="row"]')))).toEqual([
      'BASICFree',
      'PRO',
      'ULTRA',
      'MEGA',
    ]);
    expect(rows.map((row) => squash(row.querySelectorAll('td')[2]))).toEqual([
      '100',
      '300',
      '500',
      '1,000',
    ]);
  });

  // Les prix vivent sur le listing, qui les facture. Recopies ici ils se perimeraient en
  // silence, et le brief de design les interdit explicitement sur cette page.
  it("n'affiche aucun prix", () => {
    expect(root.textContent ?? '').not.toMatch(/[$€£]\s?\d/);
  });

  it('rend les cinq etapes du pipeline', () => {
    const text = root.textContent ?? '';
    for (const stage of ['VALIDATE', 'EXTRACT', 'CLEAN', 'CHUNK', 'JSON']) {
      expect(text).toContain(stage);
    }
    expect(text).toContain('Rejected before it costs you anything.');
    expect(text).toContain('One structured response.');
  });
});
