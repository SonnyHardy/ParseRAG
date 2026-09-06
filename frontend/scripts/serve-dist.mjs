// Sert `dist/parserag-frontend/browser` comme Vercel le sert, pour que Lighthouse mesure la page
// et non le serveur (issue #70).
//
// Ce fichier existe parce que la premiere mesure a ete fausse. Servie par `python -m http.server`,
// la page tombait a **61** en performance, dont 743 Kio d'« economies possibles » qui n'etaient
// que l'absence de compression : le score decrivait le serveur de test, pas le site. Un chiffre
// non reproductible ne vaut rien dans un ticket, d'ou ce serveur, versionne avec le reste.
//
// Trois comportements du reseau de bordure de Vercel sont reproduits, et rien d'autre :
//
// - **Brotli**, avec repli gzip, sur les types texte. C'est le poste dominant : le bundle passe de
//   693 Kio a environ 150 Kio.
// - **`Cache-Control`**, aligne sur `vercel.json` : `immutable` pour les fichiers dont le nom
//   porte une empreinte, un an pour les polices et images de marque dont le nom est stable, et
//   revalidation systematique du HTML. Sans cela Lighthouse signale un cache trop court, ce qui
//   est vrai du serveur et faux de la production.
// - **Le routage des routes prerendues** : `/terms` est servi depuis `terms/index.html`.
//
// Ce n'est pas un serveur de production : la sortie du build est statique et n'a besoin d'aucun
// serveur a l'execution (issue #67). C'est un instrument de mesure.
import { createServer } from 'node:http';
import { createReadStream, existsSync, readFileSync, statSync } from 'node:fs';
import { extname, join, normalize, resolve, sep } from 'node:path';
import { brotliCompressSync, gzipSync, constants as zlibConstants } from 'node:zlib';

const ROOT = resolve(process.argv[2] ?? 'dist/parserag-frontend/browser');
const PORT = Number(process.argv[3] ?? 4321);

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.webp': 'image/webp',
  '.avif': 'image/avif',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
  '.xml': 'application/xml; charset=utf-8',
};

/** Types que Vercel compresse. Les images et les woff2 sont deja compresses, les recomprimer les
 *  alourdirait. */
const COMPRESSIBLE = new Set(['.html', '.js', '.css', '.json', '.svg', '.txt', '.xml']);

/** Une empreinte dans le nom vaut promesse d'immuabilite : le contenu change, le nom aussi. */
const HASHED = /-[A-Z0-9]{8}\.(?:js|css)$/i;

/** Polices et images de marque : noms stables, donc pas d'`immutable`, mais un an de cache.
 *  Doit rester aligne sur `vercel.json`, faute de quoi la mesure decrit un cache que la
 *  production n'applique pas. */
const LONG_LIVED = /[\\/](?:fonts|brand)[\\/]/;

const cache = new Map();

function encodedBody(path, ext, accept) {
  const key = `${path}|${accept}`;
  const hit = cache.get(key);
  if (hit) {
    return hit;
  }
  const raw = readFileSync(path);
  let body = raw;
  let encoding = null;
  if (COMPRESSIBLE.has(ext)) {
    if (accept.includes('br')) {
      body = brotliCompressSync(raw, {
        params: { [zlibConstants.BROTLI_PARAM_QUALITY]: 11 },
      });
      encoding = 'br';
    } else if (accept.includes('gzip')) {
      body = gzipSync(raw, { level: 9 });
      encoding = 'gzip';
    }
  }
  const entry = { body, encoding };
  cache.set(key, entry);
  return entry;
}

/** Resout une URL vers un fichier du build, sans jamais sortir de ROOT. */
function resolveFile(urlPath) {
  const clean = decodeURIComponent(urlPath.split('?')[0]);
  const candidate = resolve(join(ROOT, normalize(clean)));
  if (candidate !== ROOT && !candidate.startsWith(ROOT + sep)) {
    return null;
  }
  if (existsSync(candidate) && statSync(candidate).isFile()) {
    return candidate;
  }
  // Route prerendue : /terms -> terms/index.html, / -> index.html
  const asDirectory = resolve(join(candidate, 'index.html'));
  if (existsSync(asDirectory)) {
    return asDirectory;
  }
  return null;
}

const server = createServer((req, res) => {
  const file = resolveFile(req.url ?? '/');
  if (!file) {
    res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
    res.end('404');
    return;
  }

  const ext = extname(file).toLowerCase();
  const type = TYPES[ext] ?? 'application/octet-stream';
  const accept = String(req.headers['accept-encoding'] ?? '');

  const headers = {
    'content-type': type,
    'cache-control': HASHED.test(file)
      ? 'public, max-age=31536000, immutable'
      : LONG_LIVED.test(file)
        ? 'public, max-age=31536000'
        : ext === '.html'
          ? 'public, max-age=0, must-revalidate'
          : 'public, max-age=86400',
    'x-content-type-options': 'nosniff',
  };

  if (!COMPRESSIBLE.has(ext)) {
    headers['content-length'] = statSync(file).size;
    res.writeHead(200, headers);
    createReadStream(file).pipe(res);
    return;
  }

  const { body, encoding } = encodedBody(file, ext, accept);
  if (encoding) {
    headers['content-encoding'] = encoding;
    headers['vary'] = 'Accept-Encoding';
  }
  headers['content-length'] = body.length;
  res.writeHead(200, headers);
  res.end(req.method === 'HEAD' ? undefined : body);
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`dist servi comme en production sur http://127.0.0.1:${PORT} (racine ${ROOT})`);
});
