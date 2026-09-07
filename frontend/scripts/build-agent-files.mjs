// Ecrit `/llms.txt`, `/llms-full.txt` et `/openapi.json` dans la sortie du build (issue #72).
//
// Une part croissante de la decouverte d'une API ne passe plus par une page de resultats mais par
// une reponse d'assistant. Le lecteur n'est alors pas quelqu'un qui parcourt une mise en page :
// c'est un extracteur qui cherche des faits. Ces trois fichiers sont ecrits pour lui.
//
// **Rien n'est recopie.** Le script importe les memes modules TypeScript que les composants
// affichent — plans, questions, codes d'erreur, mesures, exemples de code. Node sait charger un
// `.ts` directement depuis la version 23.6 ; la CI et Vercel sont sur Node 24, et un retour en
// arriere sur ce point casserait ce script bruyamment, ce qui est le bon comportement. C'est la
// seule facon de garantir que ce qu'un agent lit et ce qu'un humain lit ne peuvent pas diverger :
// il n'y a qu'une source.
//
// **`openapi.json` est une variante publiee, pas une copie.** Le snapshot de `docs/openapi.json`
// est genere par springdoc et decrit le deploiement **auto-heberge** : serveur `localhost`,
// authentification `X-API-Key`, lien vers un depot prive. Publie tel quel, il ferait generer a un
// agent un appel vers localhost avec le mauvais en-tete — une API reputee cassee des la premiere
// tentative. Les trois corrections sont celles du runbook (`docs/rapidapi-listing-setup.md`,
// etape 2, pieges 1 a 3), appliquees a la volee plutot qu'a la main dans un second fichier qui
// divergerait.
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const FRONTEND = resolve(HERE, '..');
const DIST = join(FRONTEND, 'dist', 'parserag-frontend', 'browser');
const OPENAPI_SOURCE = resolve(FRONTEND, '..', 'docs', 'openapi.json');

/** `import()` d'un chemin absolu echoue sous Windows : `E:\...` y est lu comme le protocole
 *  `e:`. Le passage par une URL `file://` est la forme portable. */
const load = (relative) => import(pathToFileURL(join(FRONTEND, relative)).href);

const { SITE_ORIGIN, absolute } = await load('src/app/seo/site.ts');
const { RAPIDAPI_URL } = await load('src/app/landing/rapidapi.ts');
const { PLANS } = await load('src/app/landing/plans-data.ts');
const { QUESTIONS, answerOf } = await load('src/app/landing/faq-data.ts');
const { API_ERRORS } = await load('src/app/landing/errors-data.ts');
const { SNIPPETS } = await load('src/app/landing/code-snippets.ts');

/** L'hote du marketplace, seul par lequel un consommateur passe. */
const RAPIDAPI_HOST = 'parserag.p.rapidapi.com';
const RAPIDAPI_BASE = `https://${RAPIDAPI_HOST}`;

const SUMMARY =
  'ParseRAG is a PDF parsing API for retrieval-augmented generation. One endpoint takes a PDF ' +
  'and returns embedding-ready JSON chunks: text in real reading order across multi-column ' +
  'layouts, repeated headers and footers stripped, tables as structured JSON, scanned pages ' +
  'handled by a vision model, and a confidence score on every chunk.';

// ---------------------------------------------------------------------------------------------
// llms.txt — l'entree courte
// ---------------------------------------------------------------------------------------------

const llms = `# ParseRAG

> ${SUMMARY}

## Quick facts

- Endpoint: \`POST ${RAPIDAPI_BASE}/api/v1/parse\`
- Request: \`multipart/form-data\` with a single \`file\` part
- Authentication: \`X-RapidAPI-Key\` header, obtained from the RapidAPI listing
- File size limit: 50 MB on every plan
- Pages per document: ${PLANS.map((p) => `${p.pages} on ${p.name}`).join(', ')}
- Response: JSON chunks, each with \`type\`, \`page\`, \`text\`, \`confidence\`, \`manual_review_needed\` and \`fallback_used\`; \`TABLE\` chunks also carry \`table_json\`

## Documentation

- [API documentation](${absolute('documentation')}): the request, the response, the limits and every error code
- [OpenAPI specification](${absolute('openapi.json')}): the machine contract, with the marketplace base URL and authentication already applied
- [Everything on one page](${absolute('llms-full.txt')}): plans, error codes, measured results, worked examples
- [Landing page](${SITE_ORIGIN}/): the same content, laid out for a human
- [RapidAPI listing](${RAPIDAPI_URL}): subscribe, get a key, see current prices

## Notes

- Prices are not published on this site. They live on the RapidAPI listing, which is the system that bills them.
- Requests per month and per minute are metered by RapidAPI. ParseRAG enforces one limit of its own, pages per document, because the marketplace counts requests and cannot see how big a job is.
`;

// ---------------------------------------------------------------------------------------------
// llms-full.txt — tout, en un seul fichier
// ---------------------------------------------------------------------------------------------

const table = (headers, rows) =>
  [
    `| ${headers.join(' | ')} |`,
    `|${headers.map(() => '---').join('|')}|`,
    ...rows.map((row) => `| ${row.join(' | ')} |`),
  ].join('\n');

const plansTable = table(
  ['Plan', 'Requests / month', 'Requests / minute', 'Pages per document'],
  PLANS.map((p) => [p.name, p.perMonth, p.perMinute, p.pages]),
);

const errorsTable = table(
  ['Code', 'HTTP', 'When', 'Retry?'],
  API_ERRORS.map((e) => [`\`${e.code}\``, e.status, e.when, e.retry]),
);

const faqSection = QUESTIONS.map((q) => `### ${q.question}\n\n${answerOf(q)}`).join('\n\n');

const examples = SNIPPETS.map(
  (s) => `### ${s.label}\n\n\`\`\`\n${s.code}\n\`\`\``,
).join('\n\n');

const llmsFull = `# ParseRAG

> ${SUMMARY}

Source: ${SITE_ORIGIN}/ — subscribe at ${RAPIDAPI_URL}

## The endpoint

\`\`\`
POST ${RAPIDAPI_BASE}/api/v1/parse
Content-Type: multipart/form-data
X-RapidAPI-Key: <your key>
\`\`\`

One part, named \`file\`, carrying the PDF. The response is JSON: a document summary and a list of
chunks. Every chunk carries \`type\`, \`page\`, \`text\`, \`confidence\`, \`manual_review_needed\`
and \`fallback_used\`. \`TABLE\` chunks additionally carry \`table_json\` with \`headers\`,
\`rows\` and an optional \`caption\`, so extracting a table needs no separate endpoint or
parameter.

The full machine contract is at ${absolute('openapi.json')}, and the same material laid out for
a human is at ${absolute('documentation')}.

## Worked examples

${examples}

## Plans

Requests are metered by RapidAPI. ParseRAG enforces one limit of its own, pages per document,
because the marketplace counts requests and cannot see how big a job is.

${plansTable}

The 50 MB file cap applies on every plan. Over the page cap, the call is rejected with
\`DOCUMENT_TOO_LONG\` rather than truncated: a silently half-parsed document is worse than a clear
refusal.

Prices are not published on this site. They live on the RapidAPI listing, which is the system that
bills them: ${RAPIDAPI_URL}

## Errors

Every error comes back in the same shape:

\`\`\`json
{ "error": "FILE_TOO_LARGE", "message": "File size 63.2 MB exceeds the 50 MB limit.", "status": 413 }
\`\`\`

Branch on \`error\`, which is stable across releases. \`message\` is written for humans and may
change.

${errorsTable}

## Questions

${faqSection}
`;

// ---------------------------------------------------------------------------------------------
// openapi.json — la variante publiee
// ---------------------------------------------------------------------------------------------

let spec;
try {
  spec = JSON.parse(readFileSync(OPENAPI_SOURCE, 'utf8'));
} catch (err) {
  console.error(
    `ECHEC   openapi.json — ${OPENAPI_SOURCE} illisible : ${err.message}\n` +
      "          Ce fichier vit a la racine du depot, hors de frontend/. Sur Vercel, verifier que\n" +
      '          la construction inclut les fichiers hors du Root Directory (issue #73).',
  );
  process.exit(1);
}

// Piege 1 du runbook : le serveur template defaut sur localhost.
spec.servers = [
  { url: RAPIDAPI_BASE, description: 'ParseRAG on the RapidAPI marketplace' },
];

// Piege 2 : le schema X-API-Key est le chemin auto-heberge. Sur le marketplace, le consommateur
// envoie X-RapidAPI-Key et c'est le proxy qui authentifie. Laisse en place, le client se voit
// reclamer un en-tete qu'il n'a pas.
spec.components = spec.components ?? {};
spec.components.securitySchemes = {
  RapidApiKey: {
    type: 'apiKey',
    in: 'header',
    name: 'X-RapidAPI-Key',
    description: 'Your RapidAPI key. Subscribe to the listing to obtain one.',
  },
  RapidApiHost: {
    type: 'apiKey',
    in: 'header',
    name: 'X-RapidAPI-Host',
    description: `Always \`${RAPIDAPI_HOST}\`.`,
  },
};
spec.security = [{ RapidApiKey: [], RapidApiHost: [] }];
for (const operations of Object.values(spec.paths ?? {})) {
  for (const operation of Object.values(operations)) {
    if (operation && typeof operation === 'object' && 'security' in operation) {
      operation.security = spec.security;
    }
  }
}

// Piege 3 : le bloc de contact pointe vers un depot prive, donc vers un lien mort.
spec.info = spec.info ?? {};
spec.info.contact = { name: 'ParseRAG', url: `${SITE_ORIGIN}/` };
spec.externalDocs = { description: 'ParseRAG', url: `${SITE_ORIGIN}/` };

writeFileSync(join(DIST, 'llms.txt'), llms);
writeFileSync(join(DIST, 'llms-full.txt'), llmsFull);
writeFileSync(join(DIST, 'openapi.json'), `${JSON.stringify(spec, null, 2)}\n`);

const kio = (text) => `${(Buffer.byteLength(text) / 1024).toFixed(1)} Kio`;
console.log(`OK      llms.txt             ${kio(llms)}`);
console.log(
  `OK      llms-full.txt        ${kio(llmsFull)}, ${PLANS.length} plans, ` +
    `${API_ERRORS.length} erreurs, ${QUESTIONS.length} questions, ${SNIPPETS.length} exemples`,
);
console.log(`OK      openapi.json         serveur ${RAPIDAPI_BASE}, auth RapidAPI`);
