import { TestBed } from '@angular/core/testing';
import { Seo } from './seo';
import { SITE_ORIGIN, SITE_PAGES, absolute, pageFor } from './site';
import { homeGraph, pageGraph } from './structured-data';
import { QUESTIONS, answerOf } from '../landing/faq-data';
import { PLANS } from '../landing/plans-data';
import { routes } from '../app.routes';

/**
 * Ce que ces tests protegent est **la coherence entre ce que la page montre et ce qu'elle
 * declare**, pas la presence des balises : celle-la est verifiee sur le HTML produit par
 * `scripts/check-seo.mjs`, ce qu'un test monte en memoire ne saurait faire.
 *
 * La difference compte. Un balisage complet mais desynchronise de la page est pire qu'un balisage
 * absent : Google le traite comme une tentative de decrire un contenu qui n'existe pas.
 */
describe('Metadonnees et donnees structurees', () => {
  const home = pageFor('/');

  it('declare une page par route de l application', () => {
    // Une route ajoutee sans metadonnees se deploierait muette, et ne se decouvrirait qu'au moment
    // ou personne ne la trouve. Le rapprochement se fait ici, pas a la relecture.
    const declared = SITE_PAGES.map((p) => p.path).sort();
    const actual = routes.map((r) => `/${r.path ?? ''}`.replace(/\/$/, '') || '/').sort();
    expect(declared).toEqual(actual);
  });

  it('donne a chaque page un titre et une description qui lui sont propres', () => {
    const titles = SITE_PAGES.map((p) => p.title);
    const descriptions = SITE_PAGES.map((p) => p.description);
    expect(new Set(titles).size).toBe(titles.length);
    expect(new Set(descriptions).size).toBe(descriptions.length);
    for (const page of SITE_PAGES) {
      // Trop courte elle n'apprend rien, trop longue elle est tronquee dans les resultats.
      expect(page.description.length).toBeGreaterThanOrEqual(50);
      expect(page.description.length).toBeLessThanOrEqual(200);
    }
  });

  it('compose des URL absolues sans barre finale parasite', () => {
    expect(absolute('/')).toBe(`${SITE_ORIGIN}/`);
    expect(absolute('/terms')).toBe(`${SITE_ORIGIN}/terms`);
    expect(absolute('brand/og-cover.png')).toBe(`${SITE_ORIGIN}/brand/og-cover.png`);
  });

  describe('graphe de la page d accueil', () => {
    const graph = homeGraph(home.description) as {
      '@graph': Record<string, unknown>[];
    };
    const nodeOf = (type: string) => graph['@graph'].find((n) => n['@type'] === type)!;

    it('porte les quatre entites attendues, une seule fois chacune', () => {
      const types = graph['@graph'].map((n) => n['@type']);
      expect(types).toEqual(['Organization', 'WebSite', 'SoftwareApplication', 'FAQPage']);
    });

    // C'est l'invariant central de #71 : le balisage lit les memes modules que les composants
    // affichent. Si quelqu'un ajoute une question a la FAQ sans toucher au JSON-LD, ce test reste
    // vert — et c'est justement le but, il n'y a plus deux endroits a tenir.
    it('publie exactement les six reponses affichees par la FAQ', () => {
      const faq = nodeOf('FAQPage') as { mainEntity: { name: string; acceptedAnswer: { text: string } }[] };
      expect(faq.mainEntity.length).toBe(QUESTIONS.length);
      for (const [i, question] of QUESTIONS.entries()) {
        expect(faq.mainEntity[i].name).toBe(question.question);
        expect(faq.mainEntity[i].acceptedAnswer.text).toBe(answerOf(question));
      }
    });

    it('publie les quatre plans du listing, prix compris', () => {
      interface Offer {
        name: string;
        price: string;
        priceCurrency: string;
        category: string;
        url: string;
      }
      const app = nodeOf('SoftwareApplication') as unknown as { offers: Offer[] };
      expect(app.offers.length).toBe(PLANS.length);
      for (const [i, plan] of PLANS.entries()) {
        expect(app.offers[i].name).toBe(plan.name);
        expect(app.offers[i].price).toBe(plan.monthlyUsd);
        expect(app.offers[i].priceCurrency).toBe('USD');
        // Chaque offre pointe vers l'endroit ou ce prix est publie et facture. C'est ce qui rend
        // acceptable qu'il figure dans le balisage sans figurer sur la page.
        expect(app.offers[i].url).toContain('rapidapi.com');
      }
      expect(app.offers[0].category).toBe('free');
    });

    it('ne declare qu une organisation, referencee par identifiant', () => {
      const org = nodeOf('Organization') as { '@id': string };
      const app = nodeOf('SoftwareApplication') as { provider: { '@id': string } };
      expect(app.provider['@id']).toBe(org['@id']);
    });
  });

  describe('graphe d une page interne', () => {
    const graph = pageGraph(pageFor('/terms')) as { '@graph': Record<string, unknown>[] };

    it('porte un fil d Ariane a deux echelons et aucun balisage de produit', () => {
      const types = graph['@graph'].map((n) => n['@type']);
      expect(types).toEqual(['Organization', 'WebSite', 'WebPage', 'BreadcrumbList']);
      const crumbs = graph['@graph'].find((n) => n['@type'] === 'BreadcrumbList') as {
        itemListElement: { position: number; item: string }[];
      };
      expect(crumbs.itemListElement.map((c) => c.position)).toEqual([1, 2]);
      expect(crumbs.itemListElement[1].item).toBe(`${SITE_ORIGIN}/terms`);
    });
  });

  describe('service Seo', () => {
    let seo: Seo;

    beforeEach(() => {
      TestBed.configureTestingModule({});
      seo = TestBed.inject(Seo);
      document.head.querySelectorAll('link[rel="canonical"], script#pr-jsonld').forEach((el) =>
        el.remove(),
      );
    });

    // Le <head> n'est pas hydrate par Angular : les balises du prerendu y sont deja quand le
    // navigateur reprend la main. Un service qui ajoute au lieu de mettre a jour produirait deux
    // canoniques et deux blocs JSON-LD, ce qu'un moteur lit comme une contradiction. Deux appels
    // successifs reproduisent exactement ce scenario.
    it('met a jour les balises au lieu de les dupliquer', () => {
      seo.apply(home, 'home');
      seo.apply(pageFor('/privacy'));

      expect(document.head.querySelectorAll('link[rel="canonical"]').length).toBe(1);
      expect(document.head.querySelectorAll('script[type="application/ld+json"]').length).toBe(1);
      expect(document.head.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe(
        `${SITE_ORIGIN}/privacy`,
      );
    });

    it('ecrit un JSON-LD analysable', () => {
      seo.apply(home, 'home');
      const raw = document.head.querySelector('script#pr-jsonld')?.textContent ?? '';
      expect(() => JSON.parse(raw)).not.toThrow();
      expect(JSON.parse(raw)['@context']).toBe('https://schema.org');
    });
  });
});
