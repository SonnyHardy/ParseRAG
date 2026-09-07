// Verifie ce qu'un agent trouve sur le site (issue #72).
//
// C'est le critere de validation de l'issue, automatise sur la sortie du build : « `/llms.txt` et
// l'OpenAPI publie repondent 200 et sont a jour du code ».
//
// **Le controle qui compte est celui de la fraicheur, pas celui de la presence.** Un `llms.txt`
// existe toujours ; ce qui se degrade en silence, c'est son contenu, le jour ou l'on ajoute une
// question a la FAQ ou un code d'erreur sans que le fichier suive. Le script relit donc les memes
// modules TypeScript que la page et verifie que chaque fait s'y retrouve, un par un.
//
// **L'OpenAPI publie est verifie sur ce qui le rendrait dangereux**, pas sur sa validite formelle :
// une base URL `localhost` ou un schema `X-API-Key` feraient generer a un agent un appel qui echoue
// a coup sur, et l'API passerait pour cassee avant d'avoir ete essayee.
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const FRONTEND = resolve(HERE, '..');
const DIST = join(FRONTEND, 'dist', 'parserag-frontend', 'browser');

const load = (relative) => import(pathToFileURL(join(FRONTEND, relative)).href);
const { PLANS } = await load('src/app/landing/plans-data.ts');
const { QUESTIONS, answerOf } = await load('src/app/landing/faq-data.ts');
const { API_ERRORS } = await load('src/app/landing/errors-data.ts');
const { SNIPPETS } = await load('src/app/landing/code-snippets.ts');

/** Les robots d'IA que l'issue demande d'autoriser nommement. */
const AI_AGENTS = [
  'GPTBot',
  'OAI-SearchBot',
  'ChatGPT-User',
  'ClaudeBot',
  'Claude-User',
  'anthropic-ai',
  'PerplexityBot',
  'Google-Extended',
  'CCBot',
  'Applebot-Extended',
  'meta-externalagent',
];

let failures = 0;

function report(file, problems) {
  if (problems.length === 0) {
    return true;
  }
  failures += problems.length;
  console.error(`ECHEC   ${file}`);
  for (const problem of problems) {
    console.error(`          ${problem}`);
  }
  return false;
}

function read(name) {
  const path = join(DIST, name);
  return existsSync(path) ? readFileSync(path, 'utf8') : null;
}

// --- robots.txt ------------------------------------------------------------------------------

const robots = read('robots.txt');
if (robots === null) {
  report('robots.txt', ['absent de la sortie du build']);
} else {
  const missing = AI_AGENTS.filter(
    (agent) => !new RegExp(`^User-agent: ${agent}$`, 'm').test(robots),
  );
  if (report('robots.txt', missing.map((a) => `agent ${a} non autorise nommement`))) {
    console.log(`OK      robots.txt           ${AI_AGENTS.length} agents IA nommes`);
  }
}

// --- llms.txt --------------------------------------------------------------------------------

const llms = read('llms.txt');
if (llms === null) {
  report('llms.txt', ['absent de la sortie du build']);
} else {
  const problems = [];
  if (!llms.startsWith('# ParseRAG')) {
    problems.push('ne commence pas par un titre de niveau 1');
  }
  // La convention llms.txt veut un resume en citation juste apres le titre : c'est ce qu'un agent
  // lit en premier, et souvent la seule chose qu'il lit.
  if (!/\n> \S/.test(llms)) {
    problems.push('pas de resume en citation sous le titre');
  }
  for (const link of ['/documentation', 'openapi.json', 'llms-full.txt', 'rapidapi.com']) {
    if (!llms.includes(link)) {
      problems.push(`ne renvoie pas vers ${link}`);
    }
  }
  if (report('llms.txt', problems)) {
    console.log(`OK      llms.txt             titre, resume, liens profonds`);
  }
}

// --- llms-full.txt ---------------------------------------------------------------------------

const full = read('llms-full.txt');
if (full === null) {
  report('llms-full.txt', ['absent de la sortie du build']);
} else {
  const problems = [];
  for (const plan of PLANS) {
    if (!full.includes(plan.name) || !full.includes(plan.pages)) {
      problems.push(`plan ${plan.name} absent ou incomplet`);
    }
  }
  for (const error of API_ERRORS) {
    if (!full.includes(error.code)) {
      problems.push(`code d'erreur ${error.code} absent`);
    }
  }
  for (const question of QUESTIONS) {
    if (!full.includes(question.question)) {
      problems.push(`question absente : ${question.question}`);
    }
    if (!full.includes(answerOf(question))) {
      problems.push(`reponse absente ou divergente pour : ${question.question}`);
    }
  }
  for (const snippet of SNIPPETS) {
    if (!full.includes(snippet.code)) {
      problems.push(`exemple ${snippet.label} absent ou divergent`);
    }
  }
  // Le prix ne doit pas plus apparaitre ici que sur la page : il vit sur le listing, qui le
  // facture. Le balisage JSON-LD est la seule exception, et elle est assumee dans #71.
  if (/[$€£]\s?\d/.test(full)) {
    problems.push('un montant apparait, alors que les prix vivent sur le listing');
  }
  if (report('llms-full.txt', problems)) {
    console.log(
      `OK      llms-full.txt        ${PLANS.length} plans, ${API_ERRORS.length} erreurs, ` +
        `${QUESTIONS.length} questions, ${SNIPPETS.length} exemples`,
    );
  }
}

// --- openapi.json ----------------------------------------------------------------------------

const rawSpec = read('openapi.json');
if (rawSpec === null) {
  report('openapi.json', ['absent de la sortie du build']);
} else {
  const problems = [];
  let spec;
  try {
    spec = JSON.parse(rawSpec);
  } catch (err) {
    problems.push(`illisible : ${err.message}`);
  }
  if (spec) {
    const servers = spec.servers ?? [];
    if (servers.length !== 1 || !/^https:\/\/[^/]+\.rapidapi\.com$/.test(servers[0]?.url ?? '')) {
      problems.push(
        `serveur publie inattendu : ${JSON.stringify(servers)} — le snapshot du depot defaut sur localhost`,
      );
    }
    const schemes = Object.values(spec.components?.securitySchemes ?? {}).map((s) => s.name);
    if (!schemes.includes('X-RapidAPI-Key')) {
      problems.push('aucun schema de securite X-RapidAPI-Key');
    }
    if (schemes.includes('X-API-Key')) {
      problems.push('le schema X-API-Key auto-heberge est encore declare');
    }
    if ((spec.info?.contact?.url ?? '').includes('github.com')) {
      problems.push('le contact pointe encore vers le depot, qui est prive');
    }
    if (!spec.paths?.['/api/v1/parse']) {
      problems.push('le chemin /api/v1/parse a disparu de la spec');
    }
  }
  if (report('openapi.json', problems)) {
    console.log(`OK      openapi.json         ${spec.servers[0].url}, auth RapidAPI`);
  }
}

if (failures > 0) {
  console.error(
    `\n${failures} probleme(s) sur ce qu'un agent lit. Ces fichiers sont la seule documentation` +
      ' publique du produit, le depot etant prive (issue #72).',
  );
  process.exit(1);
}
