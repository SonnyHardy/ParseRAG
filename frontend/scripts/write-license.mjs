// Genere src/generated/primeng-license.ts a partir de PRIMENG_LICENSE_KEY (issue #67).
//
// PrimeNG 22 n'est plus MIT : `providePrimeNG` attend une cle, y compris pour la licence
// Community gratuite. Elle ne vit donc pas dans le depot mais dans les variables d'environnement
// de Vercel, et ce script la materialise au moment du build.
//
// A savoir avant de la traiter comme un secret : une cle de licence front finit **dans le bundle
// servi au navigateur**, donc publique par construction. La sortir du depot n'en fait pas un
// secret, cela evite seulement de la graver dans l'historique Git et de la publier avec le code.
// Rien ici ne merite un coffre-fort.
//
// Sans cle, le fichier est genere avec une chaine vide et le build passe : c'est l'etat du poste
// de developpement tant que l'enregistrement chez PrimeTek n'est pas fait. PrimeNG signalera
// lui-meme ce qui lui manque au demarrage.

import { mkdirSync, writeFileSync, readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const outDir = join(root, 'src', 'generated');
const outFile = join(outDir, 'primeng-license.ts');

/**
 * Lit `frontend/.env` s'il existe, puis laisse l'environnement l'emporter.
 *
 * Node ne charge pas les `.env` tout seul. Sans cette lecture, poser la cle en developpement
 * demanderait de l'exporter dans le shell a chaque session, et la premiere reaction serait de la
 * coller dans app.config.ts, c'est-a-dire dans le depot. Sur Vercel il n'y a pas de fichier :
 * la variable vient du tableau de bord, et l'ordre ci-dessous fait qu'elle gagne.
 */
function readEnvFile() {
  const file = join(root, '.env');
  if (!existsSync(file)) {
    return {};
  }
  const values = {};
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const match = /^\s*([A-Z_][A-Z0-9_]*)\s*=\s*(.*)$/i.exec(line);
    if (!match || line.trimStart().startsWith('#')) {
      continue;
    }
    values[match[1]] = match[2].trim().replace(/^["']|["']$/g, '');
  }
  return values;
}

const fromFile = readEnvFile();
const key = (process.env.PRIMENG_LICENSE_KEY ?? fromFile.PRIMENG_LICENSE_KEY ?? '').trim();

const contents = `// Fichier genere par scripts/write-license.mjs. Ne pas editer, ne pas committer.
export const PRIMENG_LICENSE_KEY = ${JSON.stringify(key)};
`;

// Ne reecrire que si le contenu change : une ecriture systematique invalide le cache du
// compilateur et fait recompiler tout le projet a chaque `ng serve`.
if (!existsSync(outFile) || readFileSync(outFile, 'utf8') !== contents) {
  mkdirSync(outDir, { recursive: true });
  writeFileSync(outFile, contents);
}

console.log(
  key
    ? 'PrimeNG : cle de licence injectee depuis PRIMENG_LICENSE_KEY.'
    : 'PrimeNG : PRIMENG_LICENSE_KEY absente, cle vide. Attendu en developpement, a corriger sur Vercel.',
);
