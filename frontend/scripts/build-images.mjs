// Derive un WebP de chaque image de marque, dans `public/brand/` (issue #70).
//
// **Ce script ne tourne pas au build.** Comme pour les polices, les fichiers produits sont
// versionnes : un build ne doit dependre ni du reseau ni d'une bibliotheque native pour reussir.
// On le relance a la main le jour ou une image de marque change.
//
// Le PNG source est conserve et reste le `<img src>` : c'est le repli, et il est ce que voit un
// lecteur de flux, un client de messagerie ou un navigateur qui ignore `<picture>`. Les formats
// modernes sont proposes **avant** lui dans le `<picture>`, le navigateur prenant le premier
// `type` qu'il sait decoder.
//
// **WebP sans perte, et pas d'AVIF** : c'est le contraire de ce qu'on attend, et c'est mesure.
// L'AVIF gagne sur les photographies ; ces images sont des aplats et du texte, ou son ondelette
// n'a rien a factoriser. Sur wordmark-ink :
//
//     PNG source        11,6 Kio
//     AVIF sans perte    7,0 Kio
//     AVIF q90           4,8 Kio
//     AVIF q60           4,2 Kio
//     WebP sans perte    2,3 Kio   <- retenu
//
// Proposer les deux aurait ete pire qu'inutile : `<picture>` prend le premier `type` que le
// navigateur sait decoder, donc un navigateur moderne aurait telecharge l'AVIF, deux fois plus
// lourd. Sur le favicon l'AVIF est meme plus gros que le PNG d'origine.
//
// Les dimensions ne changent pas. Un wordmark de 632x208 affiche a 40 px de haut est certes
// surdimensionne, mais le redimensionner n'aurait de sens qu'en connaissant la densite de
// l'ecran, et a 2,3 Kio le gain restant ne paie plus les fichiers et le balisage qu'il couterait.
import { readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';

const HERE = dirname(fileURLToPath(import.meta.url));
const BRAND_DIR = resolve(HERE, '..', 'public', 'brand');

const kio = (bytes) => `${(bytes / 1024).toFixed(1)} Kio`;

const sources = readdirSync(BRAND_DIR).filter((f) => f.endsWith('.png'));
if (sources.length === 0) {
  console.error(`aucun PNG dans ${BRAND_DIR}`);
  process.exit(1);
}

for (const png of sources) {
  const input = join(BRAND_DIR, png);
  const base = png.replace(/\.png$/, '');
  const originalSize = statSync(input).size;
  const line = [`${base.padEnd(16)} PNG ${kio(originalSize).padStart(9)}`];

  // Sans perte : un wordmark est un aplat, ou la compression avec perte produit des franges
  // autour des lettres pour un gain nul. `effort` eleve parce que ces images sont encodees une
  // fois et servies indefiniment.
  const out = join(BRAND_DIR, `${base}.webp`);
  const info = await sharp(input).webp({ lossless: true, effort: 6 }).toFile(out);
  const delta = Math.round((1 - info.size / originalSize) * 100);
  line.push(`WebP ${kio(info.size).padStart(9)} (${delta > 0 ? '-' : '+'}${Math.abs(delta)} %)`);
  console.log(line.join('  |  '));
}
