// Verifie les metadonnees de referencement dans le HTML **produit** (issue #71).
//
// C'est le critere de validation de l'issue, automatise : « un curl montre toutes les balises
// listees, sans exception ». Il tourne en `postbuild`, donc en CI.
//
// **Pourquoi un controle et pas une relecture.** Une balise de referencement ne manque jamais
// bruyamment. La page s'affiche exactement pareil sans `canonical`, sans `og:image`, sans JSON-LD ;
// l'absence ne se paie que des mois plus tard, en indexation, et rien dans un diff de composant ne
// la signale. C'est le profil exact d'un defaut qui doit faire echouer un build.
//
// Le compagnon de `check-prerender.mjs`, qui verifie le contenu. Deux fichiers parce que ce sont
// deux questions : l'un demande si la page dit quelque chose, l'autre si elle le declare bien.
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const DIST = resolve(HERE, '..', 'dist', 'parserag-frontend', 'browser');

const PAGES = [
  'index.html',
  'documentation/index.html',
  'terms/index.html',
  'privacy/index.html',
];

/** Balises `<meta name=...>` exigees sur chaque page. */
const NAMED = [
  'description',
  'twitter:card',
  'twitter:title',
  'twitter:description',
  'twitter:image',
];

/** Balises `<meta property=...>` exigees sur chaque page. */
const PROPERTIES = [
  'og:type',
  'og:site_name',
  'og:locale',
  'og:title',
  'og:description',
  'og:url',
  'og:image',
  'og:image:width',
  'og:image:height',
  'og:image:alt',
];

/** L'ordre des attributs dans une balise n'est pas garanti ; les deux sens sont essayes. */
function metaContent(html, attr, value) {
  const escaped = value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return (
    new RegExp(`<meta[^>]+${attr}="${escaped}"[^>]+content="([^"]*)"`, 'i').exec(html)?.[1] ??
    new RegExp(`<meta[^>]+content="([^"]*)"[^>]+${attr}="${escaped}"`, 'i').exec(html)?.[1]
  );
}

function decodeEntities(text) {
  return text
    .replace(/&quot;/g, '"')
    .replace(/&#(\d+);/g, (_, code) => String.fromCodePoint(Number(code)))
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&');
}

let failures = 0;

for (const file of PAGES) {
  const path = join(DIST, file);
  if (!existsSync(path)) {
    console.error(`ABSENT  ${file} — le build ne l'a pas produit`);
    failures += 1;
    continue;
  }

  const html = readFileSync(path, 'utf8');
  const problems = [];

  if (!/<html[^>]+lang="en"/i.test(html)) {
    problems.push('<html lang="en"> absent');
  }

  const title = /<title[^>]*>([^<]*)<\/title>/i.exec(html)?.[1]?.trim();
  if (!title) {
    problems.push('<title> vide ou absent');
  }

  for (const name of NAMED) {
    if (!metaContent(html, 'name', name)) {
      problems.push(`<meta name="${name}"> absent`);
    }
  }
  for (const property of PROPERTIES) {
    if (!metaContent(html, 'property', property)) {
      problems.push(`<meta property="${property}"> absent`);
    }
  }

  if (metaContent(html, 'name', 'twitter:card') !== 'summary_large_image') {
    // `summary` donnerait une vignette carree, et l'image sociale a ete dessinee en 1200x630
    // precisement pour ne pas etre rognee.
    problems.push('twitter:card doit valoir summary_large_image');
  }

  // Trop courte, la description n'apporte rien ; trop longue, elle est tronquee dans les
  // resultats, et la phrase qui devait convaincre se termine par des points de suspension.
  const description = metaContent(html, 'name', 'description') ?? '';
  if (description.length < 50 || description.length > 200) {
    problems.push(`meta description de ${description.length} caracteres, hors de 50-200`);
  }

  // La canonique est la balise dont l'erreur coute le plus cher : elle ne casse rien a l'ecran et
  // dit a Google que la vraie page est ailleurs. On verifie qu'elle est absolue **et** qu'elle
  // designe cette page-ci, la faute classique etant de poser partout celle de l'accueil.
  const canonical = /<link[^>]+rel="canonical"[^>]+href="([^"]+)"/i.exec(html)?.[1];
  const expectedPath = `/${file.replace(/index\.html$/, '')}`.replace(/\/$/, '') || '/';
  if (!canonical) {
    problems.push('<link rel="canonical"> absent');
  } else if (!canonical.startsWith('https://')) {
    problems.push(`canonical non absolue : ${canonical}`);
  } else if (new URL(canonical).pathname !== expectedPath) {
    problems.push(`canonical vers ${new URL(canonical).pathname} au lieu de ${expectedPath}`);
  }

  // Une og:image qui pointe vers un 404 donne un apercu vide, et rien ne le signale avant qu'un
  // lien soit partage — c'est-a-dire au pire moment.
  const image = metaContent(html, 'property', 'og:image');
  if (image) {
    const asset = new URL(image).pathname.replace(/^\//, '');
    if (!existsSync(join(DIST, asset))) {
      problems.push(`og:image introuvable dans le build : ${asset}`);
    }
  }

  // Un seul bloc JSON-LD. Deux seraient le signe que le service Seo ajoute au lieu de mettre a
  // jour, ce qui arrive des qu'on oublie que le <head> n'est pas hydrate par Angular.
  const blocks = [...html.matchAll(/<script[^>]+application\/ld\+json[^>]*>([\s\S]*?)<\/script>/g)];
  if (blocks.length !== 1) {
    problems.push(`${blocks.length} bloc(s) JSON-LD, un seul attendu`);
  } else {
    try {
      const graph = JSON.parse(decodeEntities(blocks[0][1]));
      if (graph['@context'] !== 'https://schema.org') {
        problems.push('JSON-LD sans @context schema.org');
      }
      const types = (graph['@graph'] ?? []).map((node) => node['@type']);
      const expected =
        file === 'index.html'
          ? ['Organization', 'WebSite', 'SoftwareApplication', 'FAQPage']
          : ['Organization', 'WebSite', 'WebPage', 'BreadcrumbList'];
      for (const type of expected) {
        if (!types.includes(type)) {
          problems.push(`JSON-LD sans noeud ${type}`);
        }
      }
    } catch (err) {
      problems.push(`JSON-LD illisible : ${err.message}`);
    }
  }

  if (problems.length === 0) {
    console.log(`OK      ${file.padEnd(20)} titre, description, canonical, OG, Twitter, JSON-LD`);
  } else {
    failures += problems.length;
    console.error(`ECHEC   ${file}`);
    for (const problem of problems) {
      console.error(`          ${problem}`);
    }
  }
}

if (failures > 0) {
  console.error(`\n${failures} probleme(s) de metadonnees dans le HTML servi (issue #71).`);
  process.exit(1);
}
