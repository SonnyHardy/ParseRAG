// Refuse un accent grave dans un commentaire de bloc situe a l'interieur d'un litteral de gabarit.
//
// Un tel accent **termine** le litteral qui porte le `template` ou les `styles` du composant, et le
// compilateur signale alors une erreur sans aucun rapport avec la cause : il voit du CSS la ou il
// attend les proprietes du decorateur, et se plaint de « overflow n'existe pas sur le type
// Component » ou de « impossible de trouver le nom accordionheader ». On cherche du cote du CSS,
// qui est parfaitement valide. Le piege s'est presente trois fois pendant l'ecriture de la page.
//
// **Pourquoi un script et non un test.** Le defaut empeche la suite de tests de compiler : un test
// ne s'executerait jamais pour le signaler. Verifie a la main, le sabotage produisait bien les
// erreurs du compilateur et aucun test ne tournait. Il faut donc lire le fichier comme du texte,
// avant toute compilation, ce que fait ce script depuis les scripts `pre*` de npm.

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const sourceDir = join(root, 'src');

const BLOCK_COMMENT = /\/\*(?:[^*]|\*(?!\/))*\*\//g;
const NEWLINE = /\r?\n/;

function typescriptFiles(dir) {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      return typescriptFiles(full);
    }
    return full.endsWith('.ts') ? [full] : [];
  });
}

const offenders = [];

for (const file of typescriptFiles(sourceDir)) {
  const source = readFileSync(file, 'utf8');
  const lines = source.split(NEWLINE);

  for (const match of source.matchAll(BLOCK_COMMENT)) {
    if (!match[0].includes('`')) {
      continue;
    }

    // Detection exacte plutot qu'heuristique : on compte les accents graves qui precedent le
    // commentaire. Un nombre pair signifie que tout litteral ouvert avant lui est referme, donc le
    // commentaire est du TypeScript ordinaire et un accent grave y est legitime, comme dans une
    // JSDoc. Un nombre impair signifie qu'un litteral est encore ouvert : le commentaire est dans
    // un template ou dans un bloc styles, et l'accent grave va le terminer.
    const before = source.slice(0, match.index);
    if ((before.match(/`/g) ?? []).length % 2 === 0) {
      continue;
    }

    const line = before.split(NEWLINE).length;
    offenders.push(`  ${relative(root, file)}:${line}  ${lines[line - 1].trim().slice(0, 72)}`);
  }
}

if (offenders.length > 0) {
  console.error(
    [
      '',
      'Accent grave dans un commentaire de bloc, a l interieur d un litteral de gabarit.',
      'Il termine le litteral et fait echouer la compilation sur une erreur trompeuse.',
      'Retirer les accents graves de ces commentaires :',
      '',
      ...offenders,
      '',
    ].join('\n'),
  );
  process.exit(1);
}
