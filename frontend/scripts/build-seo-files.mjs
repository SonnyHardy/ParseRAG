// Ecrit `robots.txt` et `sitemap.xml` dans la sortie du build (issue #71).
//
// **Genere, jamais maintenu a la main.** L'issue le dit et la raison tient en une phrase : un
// sitemap qui ment est pire que pas de sitemap. Un fichier ecrit a la main survit a la suppression
// de la page qu'il annonce, et Google apprend alors que ce site declare des URL mortes.
//
// **La liste des pages vient de la sortie du build, pas d'un tableau.** Le script parcourt
// `dist/` et retient chaque `index.html` prerendu. C'est ce qui rend le mensonge structurellement
// impossible : le sitemap enumere exactement les pages qui existent, puisqu'il les a trouvees sur
// le disque. Une liste recopiee, meme lue depuis `seo/site.ts`, aurait pu decrire une page que le
// prerendu n'a pas produite.
//
// **L'origine vient de la balise `canonical` de la page d'accueil**, pour la meme raison. Elle est
// posee par `src/app/seo/site.ts` a travers le service `Seo` ; la relire ici plutot que de la
// redeclarer garantit que le sitemap et les canoniques ne peuvent pas se contredire. Si la
// canonique est absente, le script echoue : c'est le signe que le prerendu n'a pas pose les
// metadonnees, et un sitemap serait alors le moindre des problemes.
//
// **Pas de `lastmod`, pas de `priority`, pas de `changefreq`.** Google ignore les deux derniers
// depuis des annees, et attend du premier qu'il reflete une vraie modification de contenu. Rempli
// avec la date du build, il annoncerait que les trois pages changent a chaque deploiement, ce qui
// est faux et fait perdre au signal ce qui lui reste de valeur. Un `<loc>` seul est valide.
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const DIST = resolve(HERE, '..', 'dist', 'parserag-frontend', 'browser');

/** Chaque `index.html` prerendu, en chemin de route. */
function prerenderedRoutes(dir = DIST) {
  const routes = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      routes.push(...prerenderedRoutes(full));
    } else if (entry.name === 'index.html') {
      const relDir = relative(DIST, dir).split(sep).filter(Boolean).join('/');
      routes.push(relDir ? `/${relDir}` : '/');
    }
  }
  return routes;
}

const routes = prerenderedRoutes().sort((a, b) => a.length - b.length || a.localeCompare(b));
if (!routes.includes('/')) {
  console.error(`sitemap : aucune page d'accueil prerendue dans ${DIST}`);
  process.exit(1);
}

const home = readFileSync(join(DIST, 'index.html'), 'utf8');
const canonical = /<link[^>]+rel="canonical"[^>]+href="([^"]+)"/.exec(home)?.[1];
if (!canonical) {
  console.error(
    "sitemap : la page d'accueil prerendue ne porte pas de <link rel=\"canonical\">." +
      ' Le service Seo ne s\'est pas execute pendant le prerendu (voir src/app/seo/seo.ts).',
  );
  process.exit(1);
}

const origin = new URL(canonical).origin;

const escapeXml = (s) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

const urls = routes
  .map((route) => `  <url><loc>${escapeXml(origin + route)}</loc></url>`)
  .join('\n');

const sitemap = `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
${urls}
</urlset>
`;

// `Allow: /` explicite plutot qu'un fichier vide : les deux ont le meme effet, mais celui-ci
// enonce une intention et se relit. La ligne qui compte est `Sitemap`, seul moyen de declarer le
// fichier a un moteur qui n'est pas passe par une console de webmestre.
const robots = `User-agent: *
Allow: /

Sitemap: ${origin}/sitemap.xml
`;

writeFileSync(join(DIST, 'sitemap.xml'), sitemap);
writeFileSync(join(DIST, 'robots.txt'), robots);

console.log(`OK      sitemap.xml          ${routes.length} routes, origine ${origin}`);
for (const route of routes) {
  console.log(`          ${origin}${route}`);
}
console.log('OK      robots.txt           Allow + Sitemap');
