# frontend

Landing page ParseRAG. Angular 22 + PrimeNG 22, prerendue, destinee a Vercel.

Sa seule fonction est de convertir une visite en abonnement sur le [listing
RapidAPI](https://rapidapi.com/parserag-parserag-default/api/parserag) : le depot etant prive et la
fiche RapidAPI hors sitemap et sans meta description, c'est aujourd'hui la seule surface indexable
du produit.

## Commandes

```bash
cd frontend
npm ci
npm start      # serveur de developpement
npm run build  # build de production, avec prerendu
npm test       # vitest, sans mode veille
```

## Le socle, et pourquoi il est ainsi (issue #67)

**PrimeNG 22 n'est plus open source.** Le depot a ete archive le 28/06/2026 et la v22 attend une cle
de licence, palier Community gratuit compris. L'alternative etait d'epingler PrimeNG 21, qui reste
MIT (verifie dans le `LICENSE.md` du paquet), mais son `peerDependency` est `@angular/core ^21.0.0` :
rester en MIT aurait coute deux majeures de retard, PrimeNG **et** Angular. La v22 a ete retenue.

**La cle ne vit pas dans le depot.** `scripts/write-license.mjs` genere `src/generated/`
(non suivi) a partir de `PRIMENG_LICENSE_KEY`, avant chaque `build`, `start` et `test` via les
scripts `pre*` de npm. Sans cle, le build passe et PrimeNG se contente d'un avertissement
`[PrimeUI] PrimeUI license is not configured.` : c'est l'etat attendu en developpement et en CI, la
cle n'etant posee que sur Vercel. A noter, une cle de licence front finit dans le bundle servi au
navigateur : la sortir du depot evite de la graver dans l'historique Git, cela n'en fait pas un
secret.

**Versions figees, sans `^` ni `~`.** Une landing page se reconstruit rarement ; une montee de
version silencieuse six mois plus tard se decouvrirait en production.

**Le rendu serveur est actif des la creation** et `app.routes.server.ts` prerend toutes les routes.
Verifie sur le HTML produit : le texte, le `<h1>` et le composant PrimeNG y sont, sans executer une
ligne de JavaScript. C'est le contraire de la fiche RapidAPI, qui sert une coquille.

**`darkModeSelector` pointe sur `.parserag-dark`** et non sur le `system` par defaut : la page
portera son propre theme sombre (#68), et deux signaux differents finiraient par se contredire a
l'ecran.

## Ce que PrimeNG coute, mesure au socle

| Bundle initial | Brut | Transfere |
|---|---|---|
| Avec PrimeNG (un seul bouton) | 534,2 ko | 121,5 ko |
| Sans PrimeNG | 245,2 ko | 67,1 ko |
| **Ecart** | **+289,0 ko** | **+54,4 ko** |

L'essentiel de cet ecart est le moteur de theme, paye une fois : les composants suivants (accordeon
de la FAQ, carte, tag) couteront bien moins cher a l'unite. Le chiffre est note ici parce qu'il est
le point de depart de l'issue #70, ou le budget se juge en taille **transferee** et non brute.

Les budgets de `angular.json` sont a 600 ko d'avertissement et **700 ko d'erreur** sur le bundle
initial : reglages provisoires, calibres sur cette mesure plus la marge du contenu reel, a resserrer
en #70 une fois la page ecrite.

## A savoir

- **Node.** La CI et Vercel doivent rester sur **Node 24**. L'outillage Angular 22 declare
  `^22.22.2 || ^24.15.0 || >=26.0.0` : les majeures impaires ne passent jamais en LTS, et Node 25
  produit un avertissement a chaque commande.
- **Pas encore d'ESLint.** `frontend.yml` appelle `npm run lint --if-present`, donc l'etape est
  sautee sans echouer. A ajouter quand le code aura une surface qui le justifie.
- **Le contenu est un echafaudage.** `app.html` porte un titre, un paragraphe et le CTA, dans leur
  forme definitive de lien externe. Le contenu reel vient de #68 (canevas et copie) puis #69
  (integration).
