// Dessine l'image sociale en 1200x630, dans `public/brand/og-cover.png` (issue #71).
//
// **Ce script ne tourne pas au build**, comme `fetch-fonts.mjs` et `build-images.mjs` : le PNG
// produit est versionne. On le relance a la main quand l'accroche ou la marque changent.
//
// **Pourquoi 1200x630 et pas le logo.** L'apercu d'un lien partage est presque toujours rogne au
// format paysage : un logo carre y perd ses bords, donc la moitie du nom. Le format est celui
// qu'attendent Open Graph et `summary_large_image` de Twitter, et c'est la seule raison d'avoir
// declare cette carte plutot que la vignette carree par defaut.
//
// **Le nom du produit est l'image du wordmark, pas du texte.** C'est ce qui rend le rendu
// reproductible : le lettrage de marque arrive tel quel, sans dependre des polices installees sur
// la machine qui execute ce script. Seule l'accroche est composee en texte, et elle utilise la
// pile de polices du systeme — sur une autre machine elle peut donc tomber sur une autre police.
// C'est acceptable precisement parce que le fichier produit est versionne : ce qu'on voit ici est
// ce qui sera servi.
//
// La composition reprend les jetons de `src/styles.scss` : fond creme, encre, et le rouge de
// marque en une seule touche, la barre verticale. Le rouge est rare par construction dans cette
// identite ; une image sociale qui en abuserait ne ressemblerait plus au site qu'elle annonce.
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';

const HERE = dirname(fileURLToPath(import.meta.url));
const BRAND = resolve(HERE, '..', 'public', 'brand');

const WIDTH = 1200;
const HEIGHT = 630;

const INK = '#1e1b16';
const INK_SOFT = '#3a3630';
const PAPER = '#fbf9f4';
const RED = '#8c2f26';
const MUTED = '#69645d';
const HAIRLINE = '#d2cabb';

const SANS = "'IBM Plex Sans', 'Segoe UI', system-ui, sans-serif";
const MONO = "'JetBrains Mono', 'Cascadia Mono', Consolas, monospace";

const wordmark = readFileSync(join(BRAND, 'wordmark-ink.png'));

// Le wordmark est integre en base64 plutot que reference : librsvg resout les chemins relatifs
// depuis son repertoire de travail, ce qui rend le rendu dependant de l'endroit ou l'on lance le
// script.
const wordmarkHref = `data:image/png;base64,${wordmark.toString('base64')}`;

// 632x208 a l'origine ; la hauteur voulue fixe la largeur, sans deformer.
const markHeight = 64;
const markWidth = Math.round((632 / 208) * markHeight);

const escape = (text) =>
  text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${WIDTH}" height="${HEIGHT}">
  <rect width="${WIDTH}" height="${HEIGHT}" fill="${PAPER}"/>

  <!-- La seule touche de rouge : une barre verticale, echo de la barre du logo. -->
  <rect x="0" y="0" width="14" height="${HEIGHT}" fill="${RED}"/>

  <image href="${wordmarkHref}" x="88" y="86" width="${markWidth}" height="${markHeight}"/>

  <text x="88" y="286" font-family="${SANS}" font-size="70" font-weight="600"
        letter-spacing="-2" fill="${INK}">${escape('Turn PDFs into')}</text>
  <text x="88" y="368" font-family="${SANS}" font-size="70" font-weight="600"
        letter-spacing="-2" fill="${INK}">${escape('RAG-ready data.')}</text>

  <text x="88" y="438" font-family="${SANS}" font-size="30" fill="${INK_SOFT}">${escape(
    'Real reading order. Structured tables. Confidence on every chunk.',
  )}</text>

  <line x1="88" y1="496" x2="${WIDTH - 88}" y2="496" stroke="${HAIRLINE}" stroke-width="1"/>

  <text x="88" y="548" font-family="${MONO}" font-size="27" fill="${RED}">${escape(
    'POST /api/v1/parse',
  )}</text>
  <text x="${WIDTH - 88}" y="548" text-anchor="end" font-family="${MONO}" font-size="24"
        fill="${MUTED}">${escape('50 MB per file  ·  free plan available')}</text>
</svg>`;

const out = join(BRAND, 'og-cover.png');
const info = await sharp(Buffer.from(svg))
  // Un aplat de huit couleurs n'a aucun besoin de 16 millions : la palette divise le poids par
  // quatre sans difference visible, et cette image part dans chaque apercu de lien.
  .png({ compressionLevel: 9, palette: true })
  .toFile(out);

// Rien d'autre n'est ecrit dans public/brand/ : tout ce qui s'y trouve est copie tel quel dans la
// sortie du build et servi publiquement. Un fichier de travail depose la serait deploye.
console.log(`og-cover.png  ${info.width}x${info.height}  ${(info.size / 1024).toFixed(1)} Kio`);
