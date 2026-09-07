import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { providePrimeNG } from 'primeng/config';
import { Documentation } from './documentation';
import { API_ERRORS } from '../landing/errors-data';
import { PLANS } from '../landing/plans-data';
import { SNIPPETS } from '../landing/code-snippets';

/**
 * La documentation est hors du chemin de conversion, mais c'est la page que lira quelqu'un qui
 * integre — et celle vers laquelle `llms.txt` envoie un agent (issue #72).
 *
 * Ce que ces tests protegent est donc la **structure extractible** : de vrais tableaux, de vrais
 * blocs de code avec de vraies fins de ligne, et le meme contenu que les modules partages. Ce
 * qu'ils ne prouvent pas, c'est la presence dans le HTML servi : cela se verifie sur la sortie du
 * build, dans scripts/check-prerender.mjs.
 */
describe('Page de documentation', () => {
  let root: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Documentation],
      providers: [providePrimeNG({}), provideRouter([])],
    }).compileComponents();
    const fixture = TestBed.createComponent(Documentation);
    fixture.detectChanges();
    await fixture.whenStable();
    root = fixture.nativeElement as HTMLElement;
  });

  it('porte un titre unique et propre a la page', () => {
    const titles = root.querySelectorAll('h1');
    expect(titles.length).toBe(1);
    expect(titles[0].textContent).toContain('Using the ParseRAG endpoint');
  });

  // Un <table> semantique est la structure la plus fiablement extraite, loin devant une liste
  // stylee qui aurait le meme rendu. Une grille de div passerait un test qui ne cherche que du
  // texte, d'ou la verification des balises.
  it('publie les codes d erreur dans un tableau annonce', () => {
    const table = root.querySelector('table');
    expect(table).not.toBeNull();
    expect(table!.querySelector('caption')?.textContent?.trim()).toBeTruthy();
    expect(table!.querySelectorAll('thead th[scope="col"]').length).toBe(4);
    expect(table!.querySelectorAll('tbody th[scope="row"]').length).toBe(API_ERRORS.length);

    const text = table!.textContent ?? '';
    for (const error of API_ERRORS) {
      expect(text).toContain(error.code);
      expect(text).toContain(String(error.status));
    }
  });

  // Dans un <pre>, ce qui separe deux lignes doit etre un vrai caractere : c'est lui qu'extrait un
  // agent et lui seul que copie le presse-papiers.
  it('rend les trois exemples avec de vraies fins de ligne', () => {
    const blocks = Array.from(root.querySelectorAll('pre'));
    // Trois exemples, plus la requete et la forme d'erreur.
    expect(blocks.length).toBeGreaterThanOrEqual(SNIPPETS.length + 1);
    for (const snippet of SNIPPETS) {
      const block = blocks.find((b) => (b.textContent ?? '') === snippet.code);
      expect([snippet.label, block !== undefined]).toEqual([snippet.label, true]);
      expect(block!.textContent).toContain('\n');
    }
  });

  // Les limites viennent de la grille des plans plutot que d'une phrase recopiee : une page de
  // reference qui contredit le tableau des plans est pire qu'une page absente.
  it('compose les limites de pages depuis la grille des plans', () => {
    const text = root.textContent ?? '';
    for (const plan of PLANS) {
      expect(text).toContain(`${plan.pages} on ${plan.name}`);
    }
  });

  it("n'affiche aucun prix", () => {
    expect(root.textContent ?? '').not.toMatch(/[$€£]\s?\d/);
  });
});
