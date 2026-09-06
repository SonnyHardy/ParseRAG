// Telecharge les polices depuis Google Fonts vers `public/fonts/`, et regenere `src/fonts.scss`
// (issue #70).
//
// **Ce script ne tourne pas au build.** Les `.woff2` et le `.scss` produits sont versionnes : un
// build ne doit dependre d'aucun tiers, ni pour reussir ni pour etre reproductible. La mise en
// ligne de #69 l'a montre a ses depens, `npm run build` echouant sur
// `getaddrinfo ENOTFOUND fonts.googleapis.com` pendant une panne DNS. On le relance a la main le
// jour ou une graisse ou un sous-ensemble change.
//
// Trois choix valent d'etre expliques.
//
// **Des polices variables, pas sept fichiers statiques.** La page utilise les graisses 400, 500,
// 600 et 700 en sans et 400, 500, 700 en mono : sept fichiers si on les demande une par une, deux
// si on demande la plage `wght@400..700`. Le fichier variable est un peu plus lourd que la graisse
// unique, et beaucoup plus leger que la somme. Il rend aussi gratuite toute graisse intermediaire
// qu'un futur ecran voudrait.
//
// **Seulement `latin` et `latin-ext`.** Google en sert six par famille, cyrillique, grec et
// vietnamien compris. La page est en anglais ; les charger revenait a payer des alphabets que
// personne ne lira. C'est aussi ce qui faisait 195 Kio de HTML : la feuille inlinee par Angular
// declarait les 12 blocs `@font-face`.
//
// **L'`unicode-range` est conserve tel quel.** Il n'est pas decoratif : c'est lui qui empeche le
// navigateur de telecharger `latin-ext` tant qu'aucun caractere accentue n'apparait. Sans lui, la
// page paierait les deux fichiers au lieu d'un.
//
// Licences : IBM Plex Sans et JetBrains Mono sont sous SIL Open Font License 1.1, qui autorise
// l'auto-hebergement. Voir public/fonts/OFL.txt.
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const FONT_DIR = resolve(HERE, '..', 'public', 'fonts');
const SCSS_OUT = resolve(HERE, '..', 'src', 'fonts.scss');

// Sans quoi Google sert des `.ttf` : la reponse depend de l'agent utilisateur declare.
const CHROME_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36';

const QUERY =
  'family=IBM+Plex+Sans:wght@400..700&family=JetBrains+Mono:wght@400..700&display=swap';

const WANTED_SUBSETS = ['latin', 'latin-ext'];

/** `IBM Plex Sans` -> `ibm-plex-sans` */
const slug = (family) => family.toLowerCase().replace(/\s+/g, '-');

async function main() {
  const css = await fetch(`https://fonts.googleapis.com/css2?${QUERY}`, {
    headers: { 'user-agent': CHROME_UA },
  }).then((r) => {
    if (!r.ok) {
      throw new Error(`Google Fonts a repondu ${r.status}`);
    }
    return r.text();
  });

  // Le commentaire qui precede chaque bloc porte le nom du sous-ensemble ; c'est la seule facon
  // de le connaitre, la reponse ne l'exposant nulle part ailleurs.
  const blocks = [...css.matchAll(/\/\* ([a-z-]+) \*\/\s*@font-face \{([\s\S]*?)\}/g)];
  if (blocks.length === 0) {
    throw new Error('aucun bloc @font-face reconnu : le format de la reponse a change');
  }

  mkdirSync(FONT_DIR, { recursive: true });
  const faces = [];

  for (const [, subset, body] of blocks) {
    if (!WANTED_SUBSETS.includes(subset)) {
      continue;
    }
    const family = /font-family: '([^']+)'/.exec(body)?.[1];
    const url = /url\((https:\/\/[^)]+)\)/.exec(body)?.[1];
    const range = /unicode-range: ([^;]+);/.exec(body)?.[1];
    if (!family || !url || !range) {
      throw new Error(`bloc @font-face incomplet pour le sous-ensemble ${subset}`);
    }

    const file = `${slug(family)}-${subset}.woff2`;
    const bytes = Buffer.from(
      await fetch(url, { headers: { 'user-agent': CHROME_UA } }).then((r) => r.arrayBuffer()),
    );
    writeFileSync(join(FONT_DIR, file), bytes);
    console.log(`${file.padEnd(34)} ${(bytes.length / 1024).toFixed(1)} Kio  <- ${url}`);

    faces.push({ family, file, range, subset });
  }

  const header = `// GENERE PAR scripts/fetch-fonts.mjs — ne pas editer a la main.
//
// Les deux familles du design, servies depuis notre propre origine (issue #70). Avant, elles
// venaient de fonts.googleapis.com et fonts.gstatic.com : deux origines tierces sur le chemin
// critique, deux resolutions DNS et deux poignees de main TLS avant le premier caractere.
//
// \`font-display: swap\` : le texte s'affiche immediatement dans la police de repli et bascule a
// l'arrivee. C'est le meme principe que partout ailleurs sur cette page, le contenu ne depend
// jamais de l'aboutissement d'un chargement.
//
// La graisse est declaree en plage \`400 700\` parce que le fichier est variable : une seule
// requete couvre les quatre graisses de la page. Le format reste \`woff2\` et non
// \`woff2-variations\`, qui est un vestige : un navigateur qui ne le reconnait pas ignore la face
// entiere, et Google Fonts sert lui-meme ses fichiers variables en \`woff2\`.
`;

  const rules = faces
    .map(
      (f) => `
@font-face {
  font-family: '${f.family}';
  font-style: normal;
  font-weight: 400 700;
  font-display: swap;
  src: url('/fonts/${f.file}') format('woff2');
  unicode-range: ${f.range};
}`,
    )
    .join('\n');

  writeFileSync(SCSS_OUT, `${header}${rules}\n`);
  console.log(`\n${faces.length} faces ecrites dans ${SCSS_OUT}`);
}

main().catch((err) => {
  console.error(err.message);
  process.exit(1);
});
