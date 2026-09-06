// Verifie que le HTML **servi** porte le texte de la page (issue #70).
//
// C'est le critere de validation de l'issue, pris au mot : « verifier que l'index.html produit
// contient reellement le texte, ne pas le supposer ». Les tests unitaires ne repondent pas a cette
// question. Ils montent les composants dans un navigateur simule et lisent le DOM apres coup :
// tout ce qui n'existerait qu'apres execution de JavaScript y passerait pour present.
//
// Le controle tourne apres chaque build (`postbuild`), donc aussi en CI.
//
// **Ce qu'il attrape en pratique**, et la raison d'etre du fichier : le passage de
// `@defer (hydrate on viewport)` a `@defer (on viewport)`. Un caractere de difference a la
// relecture, et le serveur rend le substitut au lieu du contenu : dix sections sur onze
// disparaissent du HTML servi, sans que rien ne change a l'ecran une fois JavaScript charge.
// C'est exactement le defaut de la fiche RapidAPI que cette issue existe pour ne pas reproduire.
//
// **Le texte est compare apres retrait des balises.** Chercher la chaine brute dans le HTML ne
// marche pas : la coloration syntaxique decoupe `curl -X POST` en plusieurs `<span>`, et un
// `grep` naif conclut a tort que l'exemple manque. La verification a d'ailleurs commence par ce
// faux positif.
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const DIST = resolve(HERE, '..', 'dist', 'parserag-frontend', 'browser');

/** Une phrase par section, prise dans le contenu et non dans le decor. */
const EXPECTED = {
  'index.html': [
    'Turn PDFs into RAG-ready data',
    'Parse a PDF in one request',
    'curl -X POST',
    'import requests',
    'MultipartBody.Builder',
    'PDF extraction is quietly wrong',
    'Bad extraction becomes bad retrieval',
    'From PDF to RAG-ready chunks',
    'Every chunk tells you how much to trust it',
    'Built for the PDFs that break parsers',
    'Metered by requests, bounded by pages',
    'Image-only pages skip native extraction',
    'Give your RAG pipeline cleaner input',
  ],
  'terms/index.html': ['ParseRAG: Terms of Use'],
  'privacy/index.html': ['Privacy and data protection'],
};

/** Le texte que verrait un lecteur sans JavaScript : balises retirees, entites resolues. */
function visibleText(html) {
  const withoutCode = html.replace(/<script[\s\S]*?<\/script>|<style[\s\S]*?<\/style>/g, ' ');
  const withoutTags = withoutCode.replace(/<[^>]+>/g, '');
  return withoutTags
    .replace(/&(#\d+|#x[0-9a-f]+|[a-z]+);/gi, (whole, code) => {
      if (code.startsWith('#x')) {
        return String.fromCodePoint(parseInt(code.slice(2), 16));
      }
      if (code.startsWith('#')) {
        return String.fromCodePoint(Number(code.slice(1)));
      }
      return { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' }[code] ?? whole;
    })
    .replace(/\s+/g, ' ');
}

let failures = 0;

for (const [file, phrases] of Object.entries(EXPECTED)) {
  const path = join(DIST, file);
  let text;
  try {
    text = visibleText(readFileSync(path, 'utf8'));
  } catch {
    console.error(`ABSENT  ${file} — le build ne l'a pas produit`);
    failures += phrases.length;
    continue;
  }
  const missing = phrases.filter((p) => !text.includes(p));
  if (missing.length === 0) {
    console.log(`OK      ${file.padEnd(20)} ${phrases.length} phrases, ${text.length} caracteres`);
    continue;
  }
  failures += missing.length;
  console.error(`ECHEC   ${file}`);
  for (const p of missing) {
    console.error(`          absent du HTML servi : ${p}`);
  }
}

if (failures > 0) {
  console.error(
    `\n${failures} phrase(s) absente(s) du HTML prerendu. Une page qui ne dit rien a un curl ne` +
      ' dit rien a un moteur ni a un agent (issue #70).',
  );
  process.exit(1);
}
