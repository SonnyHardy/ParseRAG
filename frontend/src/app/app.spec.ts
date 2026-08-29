import { TestBed } from '@angular/core/testing';
import { providePrimeNG } from 'primeng/config';
import { App } from './app';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      // providePrimeNG est requis meme ici : le composant importe ButtonModule, qui lit la
      // configuration au premier rendu. Sans lui, le test echoue sur une raison sans rapport avec
      // ce qu'il verifie.
      providers: [providePrimeNG({})],
    }).compileComponents();
  });

  it('monte le composant racine', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
  });

  // Ce que ce test protege n'est pas la formulation du titre mais le fait qu'il y ait un h1 rendu :
  // un seul h1 par page est la structure sur laquelle reposent les issues #71 et #72.
  it('rend un h1 unique', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const titles = (fixture.nativeElement as HTMLElement).querySelectorAll('h1');
    expect(titles.length).toBe(1);
    expect(titles[0].textContent).toContain('ParseRAG');
  });

  // Le composant PrimeNG doit produire un vrai <a href> et non un bouton inerte : c'est ce lien
  // qui porte la seule conversion de la page, et un moteur ou un agent doit pouvoir le suivre.
  it('rend le CTA comme un lien externe suivable', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const cta = (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('a[href]');
    expect(cta?.getAttribute('href')).toBe(
      'https://rapidapi.com/parserag-parserag-default/api/parserag',
    );
    expect(cta?.getAttribute('rel')).toContain('noopener');
  });
});
