/**
 * L'identite du site, en un seul endroit (issue #71).
 *
 * Tout ce qui suit finit dans une URL absolue : `canonical`, `og:url`, `og:image`, le `sitemap.xml`
 * et chaque `@id` du JSON-LD. Ces valeurs se recopient donc dans une dizaine d'endroits, et une
 * canonique fausse est le genre d'erreur qui s'indexe en silence : elle ne casse rien a l'ecran,
 * elle dit seulement a Google que la vraie page est ailleurs.
 */

/**
 * L'origine du site, sans barre oblique finale.
 *
 * **Le domaine n'est pas encore acquis** : c'est l'issue #73 qui l'achete, entre `parserag.dev` et
 * `parserag.com`. Cette constante porte le premier candidat, et elle est le seul endroit a changer
 * si l'arbitrage bascule. Le script `scripts/build-seo-files.mjs` la lit pour le sitemap, de sorte
 * que le fichier servi et les balises de la page ne peuvent pas se contredire.
 */
export const SITE_ORIGIN = 'https://parserag.dev';

/** Le nom, tel qu'il apparait dans `og:site_name` et dans l'`Organization` du JSON-LD. */
export const SITE_NAME = 'ParseRAG';

/**
 * L'image sociale, en 1200x630 (issue #71).
 *
 * Ce format n'est pas decoratif : le logo carre est **rogne** par la plupart des apercus, et un
 * apercu rogne coupe le nom du produit en deux. Elle est generee par `scripts/build-og-image.mjs`
 * et versionnee, comme les polices et les WebP.
 */
export const SOCIAL_IMAGE = {
  path: 'brand/og-cover.png',
  width: 1200,
  height: 630,
  alt: 'ParseRAG: turn PDFs into RAG-ready JSON chunks',
} as const;

/** Compose une URL absolue a partir d'un chemin de route ou de fichier. */
export function absolute(path: string): string {
  const clean = path.startsWith('/') ? path : `/${path}`;
  return `${SITE_ORIGIN}${clean === '/' ? '/' : clean.replace(/\/$/, '')}`;
}

/**
 * Les pages du site, dans l'ordre ou elles comptent.
 *
 * Cette liste est la source du `sitemap.xml` **et** du fil d'Ariane. Elle double `app.routes.ts`,
 * ce qui est assume : les routes portent le chargement, celle-ci porte la publication. Une route
 * technique qu'on ne veut pas voir indexee existerait dans la premiere et pas ici. Un test
 * verifie qu'aucune route de l'application n'a ete oubliee de ce tableau.
 */
export interface SitePage {
  readonly path: string;
  readonly title: string;
  readonly description: string;
  /** Priorite relative du sitemap. La page d'accueil est la seule qui vise le referencement. */
  readonly priority: string;
}

export const SITE_PAGES: readonly SitePage[] = [
  {
    path: '/',
    title: 'ParseRAG: PDF Parsing API for RAG',
    description:
      'Turn PDFs into clean, structured JSON and RAG-ready chunks with real reading order, table extraction, confidence scoring, and vision fallback.',
    priority: '1.0',
  },
  {
    path: '/documentation',
    title: 'API documentation | ParseRAG',
    description:
      'How to call the ParseRAG PDF parsing API: the multipart request, the JSON response, the size and page limits, and every error code the endpoint can return.',
    priority: '0.6',
  },
  {
    path: '/terms',
    title: 'Terms of Use | ParseRAG',
    description:
      'Terms of use for the ParseRAG PDF parsing API: what the service does, what happens to the documents you upload, and the limits of liability.',
    priority: '0.3',
  },
  {
    path: '/privacy',
    title: 'Privacy and data protection | ParseRAG',
    description:
      'How ParseRAG handles your documents: processed in memory for the duration of the request, never stored, never logged, never used for training.',
    priority: '0.3',
  },
];

/**
 * La page decrivant une route, ou une erreur explicite.
 *
 * Elle leve plutot que de renvoyer un defaut : une page sans metadonnees se deploierait sans que
 * rien ne proteste, et ne se decouvrirait qu'au moment ou personne ne la trouve. Le prerendu
 * echoue donc au build, ce qui est le bon moment.
 */
export function pageFor(path: string): SitePage {
  const page = SITE_PAGES.find((p) => p.path === path);
  if (!page) {
    throw new Error(`Aucune metadonnee declaree pour la route ${path} (voir src/app/seo/site.ts)`);
  }
  return page;
}
