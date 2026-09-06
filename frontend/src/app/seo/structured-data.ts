import { RAPIDAPI_URL } from '../landing/rapidapi';
import { QUESTIONS, answerOf } from '../landing/faq-data';
import { PLANS } from '../landing/plans-data';
import { SITE_NAME, SITE_ORIGIN, SOCIAL_IMAGE, absolute } from './site';

/**
 * Le JSON-LD de chaque page (issue #71).
 *
 * **Tout est construit a partir des donnees de la page, jamais recopie.** Les six questions
 * viennent de `faq-data.ts`, les quatre plans de `plans-data.ts`, les memes modules que les
 * composants affichent. C'est la seule facon de tenir la regle de Google — le balisage doit decrire
 * ce que le visiteur voit — autrement que par la vigilance : ici, publier une reponse que la page
 * n'affiche plus demanderait de la supprimer des deux endroits a la fois, ce qui n'arrive pas par
 * distraction.
 *
 * **Un seul graphe par page**, et non cinq blocs `<script>` separes. Les entites se referencent
 * par `@id` : l'`Organization` est declaree une fois et le reste y renvoie, ce qui evite qu'un
 * moteur voie quatre organisations portant le meme nom.
 *
 * Les identifiants sont des URL absolues suffixees d'un fragment (`#organization`, `#api`). C'est
 * la convention attendue : un `@id` doit etre globalement unique, pas seulement unique dans le
 * fichier.
 */

const ORGANIZATION_ID = `${SITE_ORIGIN}/#organization`;
const WEBSITE_ID = `${SITE_ORIGIN}/#website`;
const API_ID = `${SITE_ORIGIN}/#api`;

type Node = Record<string, unknown>;

const organization = (): Node => ({
  '@type': 'Organization',
  '@id': ORGANIZATION_ID,
  name: SITE_NAME,
  url: `${SITE_ORIGIN}/`,
  logo: {
    '@type': 'ImageObject',
    url: absolute('brand/wordmark-ink.png'),
    width: 632,
    height: 208,
  },
  sameAs: [RAPIDAPI_URL],
});

const website = (): Node => ({
  '@type': 'WebSite',
  '@id': WEBSITE_ID,
  name: SITE_NAME,
  url: `${SITE_ORIGIN}/`,
  publisher: { '@id': ORGANIZATION_ID },
  inLanguage: 'en',
});

/**
 * Les quatre plans du listing, en `Offer`.
 *
 * **Le prix est ici et nulle part sur la page**, et cette asymetrie est voulue des deux cotes. Le
 * brief de design interdit tout montant a l'ecran, parce qu'un tarif recopie se perime sans que
 * rien ne le signale ; l'issue #71 demande en revanche un bloc `offers` refletant les quatre
 * plans, un `Offer` sans prix n'ayant guere de sens. Chaque offre porte donc en `url` l'adresse ou
 * ce prix est effectivement publie et facture, c'est-a-dire le listing RapidAPI.
 *
 * A relire si la grille de l'etape 6 du runbook change : c'est le seul endroit du front qui la
 * duplique.
 */
const offers = (): Node[] =>
  PLANS.map((plan) => ({
    '@type': 'Offer',
    name: plan.name,
    price: plan.monthlyUsd,
    priceCurrency: 'USD',
    category: plan.free ? 'free' : 'subscription',
    url: RAPIDAPI_URL,
    description:
      `${plan.perMonth} requests per month, ${plan.perMinute} per minute, ` +
      `up to ${plan.pages} pages per document.`,
  }));

const softwareApplication = (description: string): Node => ({
  '@type': 'SoftwareApplication',
  '@id': API_ID,
  name: SITE_NAME,
  url: `${SITE_ORIGIN}/`,
  description,
  // `DeveloperApplication` est la categorie prevue pour un outil destine aux developpeurs ;
  // `applicationSubCategory` porte la nature reelle du produit, qui n'a pas de valeur dediee.
  applicationCategory: 'DeveloperApplication',
  applicationSubCategory: 'API',
  operatingSystem: 'Any',
  provider: { '@id': ORGANIZATION_ID },
  isPartOf: { '@id': WEBSITE_ID },
  image: absolute(SOCIAL_IMAGE.path),
  offers: offers(),
  featureList: [
    'Reading order preserved across multi-column layouts',
    'Repeated headers and footers stripped',
    'Tables returned as structured JSON',
    'Scanned pages handled by a vision model',
    'Confidence score and review flag on every chunk',
  ],
});

/** Les six questions, telles que la section FAQ les affiche. */
const faqPage = (): Node => ({
  '@type': 'FAQPage',
  '@id': `${SITE_ORIGIN}/#faq`,
  mainEntity: QUESTIONS.map((q) => ({
    '@type': 'Question',
    name: q.question,
    acceptedAnswer: { '@type': 'Answer', text: answerOf(q) },
  })),
});

/**
 * Fil d'Ariane des pages internes.
 *
 * Absent de la page d'accueil : un fil d'Ariane a un seul echelon, qui est la page elle-meme,
 * n'apprend rien et Google le signale.
 */
const breadcrumb = (page: { path: string; title: string }): Node => ({
  '@type': 'BreadcrumbList',
  itemListElement: [
    { '@type': 'ListItem', position: 1, name: SITE_NAME, item: `${SITE_ORIGIN}/` },
    { '@type': 'ListItem', position: 2, name: page.title, item: absolute(page.path) },
  ],
});

const webPage = (page: { path: string; title: string; description: string }): Node => ({
  '@type': 'WebPage',
  '@id': `${absolute(page.path)}#webpage`,
  url: absolute(page.path),
  name: page.title,
  description: page.description,
  isPartOf: { '@id': WEBSITE_ID },
  inLanguage: 'en',
});

/** Le graphe de la page d'accueil : organisation, site, produit, questions. */
export function homeGraph(description: string): Node {
  return {
    '@context': 'https://schema.org',
    '@graph': [organization(), website(), softwareApplication(description), faqPage()],
  };
}

/** Le graphe d'une page interne : organisation, site, la page et son fil d'Ariane. */
export function pageGraph(page: { path: string; title: string; description: string }): Node {
  return {
    '@context': 'https://schema.org',
    '@graph': [organization(), website(), webPage(page), breadcrumb(page)],
  };
}
