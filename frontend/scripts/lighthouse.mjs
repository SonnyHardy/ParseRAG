// Mesure Lighthouse en profil mobile, plusieurs fois, et rend la **mediane** (issue #70).
//
// Ce fichier existe parce que la premiere serie de mesures etait ininterpretable : le meme build,
// mesure quatre fois de suite, a donne 62, 93, 85 et 93 en performance, le temps de blocage total
// oscillant entre 190 et 1 160 ms. Lighthouse simule le reseau mais partage le vrai processeur ;
// tout ce qui tourne a cote entre dans la mesure. Un seul passage ne mesure donc pas la page, il
// mesure la machine a cet instant.
//
// Deux garde-fous, et ils sont le coeur du fichier :
//
// - **La mediane de N passages**, jamais un passage isole. C'est la pratique de l'outil lui-meme,
//   dont l'interface publique agrege plusieurs releves.
// - **L'ecart est affiche**, minimum et maximum a cote de la mediane. Un ecart large est un
//   avertissement : la machine etait chargee, il faut refermer ce qui tourne et recommencer plutot
//   que de reporter le chiffre.
//
// La version de Lighthouse est **epinglee**. Les seuils de notation changent d'une majeure a
// l'autre, et un chiffre reporte dans un ticket sans sa version ne veut rien dire.
//
// Prerequis : le build sert deja sur le port vise (`npm run build && npm run serve:dist`).
// Il faut un serveur qui compresse — voir scripts/serve-dist.mjs, sans quoi la mesure decrit le
// serveur de test et non le site.
//
//   npm run lighthouse            # 5 passages sur http://127.0.0.1:4321/
//   npm run lighthouse -- 3 /terms
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const LIGHTHOUSE = 'lighthouse@12.8.2';
const RUNS = Number(process.argv[2] ?? 5);
const PATH_UNDER_TEST = process.argv[3] ?? '/';
const ORIGIN = process.env['LH_ORIGIN'] ?? 'http://127.0.0.1:4321';
const URL_UNDER_TEST = `${ORIGIN}${PATH_UNDER_TEST}`;

const CATEGORIES = ['performance', 'accessibility', 'best-practices', 'seo'];
const METRICS = [
  ['first-contentful-paint', 'FCP'],
  ['largest-contentful-paint', 'LCP'],
  ['total-blocking-time', 'TBT'],
  ['cumulative-layout-shift', 'CLS'],
  ['speed-index', 'SI'],
];

const median = (xs) => {
  const s = [...xs].sort((a, b) => a - b);
  const mid = s.length >> 1;
  return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
};

const workDir = mkdtempSync(join(tmpdir(), 'parserag-lh-'));
const reports = [];

try {
  for (let i = 1; i <= RUNS; i++) {
    const out = join(workDir, `run-${i}.json`);
    // `shell: true` : sous Windows, `npx` est un `.cmd` que `spawnSync` ne sait pas lancer
    // directement. Sans lui chaque passage echouait avec un code de sortie nul, c'est-a-dire sans
    // avoir demarre.
    const res = spawnSync(
      [
        'npx --yes',
        LIGHTHOUSE,
        `"${URL_UNDER_TEST}"`,
        `--only-categories=${CATEGORIES.join(',')}`,
        '--form-factor=mobile',
        '--screenEmulation.mobile',
        '--output=json',
        `--output-path="${out}"`,
        '--chrome-flags="--headless=new --no-sandbox"',
        '--quiet',
      ].join(' '),
      { stdio: ['ignore', 'ignore', 'pipe'], shell: true, encoding: 'utf8' },
    );
    let report;
    try {
      report = JSON.parse(readFileSync(out, 'utf8'));
    } catch {
      // Le lanceur de Chrome echoue parfois au **nettoyage**, apres avoir ecrit le rapport ; on ne
      // se fie donc pas au code de sortie, seulement a la presence d'un rapport lisible.
      console.error(`passage ${i} : aucun rapport exploitable (code ${res.status})`);
      const why = String(res.stderr ?? res.error?.message ?? '').trim();
      console.error(why.split(/\r?\n/).slice(-3).join('\n'));
      continue;
    }
    reports.push(report);
    const perf = Math.round((report.categories.performance.score ?? 0) * 100);
    console.log(`passage ${i}/${RUNS} : performance ${perf}`);
  }

  if (reports.length === 0) {
    throw new Error("aucun passage n'a abouti");
  }

  const version = reports[0].lighthouseVersion;
  console.log(`\n${URL_UNDER_TEST} — Lighthouse ${version}, mobile, ${reports.length} passages\n`);
  console.log('  categorie          mediane   (min-max)');
  for (const id of CATEGORIES) {
    const scores = reports.map((r) => Math.round((r.categories[id].score ?? 0) * 100));
    const title = reports[0].categories[id].title;
    const spread = `${Math.min(...scores)}-${Math.max(...scores)}`;
    console.log(`  ${title.padEnd(18)} ${String(median(scores)).padStart(5)}     (${spread})`);
  }
  console.log('\n  metrique           mediane   (min-max)');
  for (const [id, label] of METRICS) {
    const values = reports.map((r) => r.audits[id].numericValue ?? 0);
    const unit = id === 'cumulative-layout-shift' ? '' : ' ms';
    const fmt = (v) => (id === 'cumulative-layout-shift' ? v.toFixed(3) : Math.round(v));
    const spread = `${fmt(Math.min(...values))}-${fmt(Math.max(...values))}`;
    console.log(`  ${label.padEnd(18)} ${String(fmt(median(values))).padStart(5)}${unit}   (${spread})`);
  }

  const perfScores = reports.map((r) => Math.round((r.categories.performance.score ?? 0) * 100));
  const spread = Math.max(...perfScores) - Math.min(...perfScores);
  if (spread > 10) {
    console.log(
      `\n  ATTENTION : ${spread} points d'ecart entre les passages. La machine etait chargee ;` +
        ' refermer ce qui tourne et recommencer plutot que de reporter ce chiffre.',
    );
  }
} finally {
  rmSync(workDir, { recursive: true, force: true });
}
