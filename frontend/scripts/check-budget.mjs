// Budget du bundle initial, mesure en taille **transferee** (issue #70).
//
// Les budgets de `angular.json` comparent des tailles brutes ; ils restent utiles, mais ce n'est
// pas ce que paie le visiteur. Entre les deux mesures, le rapport est d'environ 4,2 sur ce projet
// (589 Kio bruts pour 139 Kio transferes) : un budget brut laisse donc passer, ou refuse, des
// variations que personne ne ressent. Le README de #67 annoncait deja que le budget se jugerait
// en taille transferee ; ce fichier est cette promesse tenue, Angular ne sachant pas le faire.
//
// La compression est faite au meme reglage que scripts/serve-dist.mjs, lui-meme aligne sur le
// reseau de bordure de Vercel : mesurer avec un autre reglage donnerait un chiffre qui ne
// correspond a aucun octet reellement transmis.
//
// Le plafond est un cliquet, pas une cible. Il est pose juste au-dessus de la mesure du jour pour
// qu'une regression se voie ; on l'abaisse quand on gagne, on ne le releve qu'en connaissance de
// cause.
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { brotliCompressSync, constants as zlibConstants } from 'node:zlib';

const HERE = dirname(fileURLToPath(import.meta.url));
const DIST = resolve(HERE, '..', 'dist', 'parserag-frontend', 'browser');

/** Kio transferes au-dela desquels le build echoue. */
const CEILING_KIB = 95;

/** Ce que le navigateur telecharge avant de pouvoir afficher : le HTML, le bundle d'entree et la
 *  feuille de styles. Les morceaux differes n'en font pas partie, c'est tout leur interet. */
const index = readFileSync(join(DIST, 'index.html'));
const initial = readdirSync(DIST).filter((f) => /^(?:main|styles)-[A-Z0-9]{8}\.(?:js|css)$/i.test(f));

if (initial.length < 2) {
  console.error(`budget : fichiers d'entree introuvables dans ${DIST}`);
  process.exit(1);
}

const brotli = (buf) =>
  brotliCompressSync(buf, { params: { [zlibConstants.BROTLI_PARAM_QUALITY]: 11 } }).length;

const rows = [['index.html', index.length, brotli(index)]];
for (const file of initial.sort()) {
  const raw = readFileSync(join(DIST, file));
  rows.push([file, raw.length, brotli(raw)]);
}

const kib = (n) => (n / 1024).toFixed(1);
const totalRaw = rows.reduce((a, r) => a + r[1], 0);
const totalBr = rows.reduce((a, r) => a + r[2], 0);

for (const [name, raw, br] of rows) {
  console.log(`        ${name.padEnd(24)} ${kib(raw).padStart(8)} Kio bruts  ${kib(br).padStart(7)} Kio transferes`);
}
console.log(`        ${'TOTAL'.padEnd(24)} ${kib(totalRaw).padStart(8)} Kio bruts  ${kib(totalBr).padStart(7)} Kio transferes  (plafond ${CEILING_KIB} Kio)`);

if (totalBr / 1024 > CEILING_KIB) {
  console.error(
    `\nBUDGET DEPASSE : ${kib(totalBr)} Kio transferes pour un plafond de ${CEILING_KIB} Kio.`,
  );
  process.exit(1);
}
