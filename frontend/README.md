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
scripts `pre*` de npm. Le script lit `frontend/.env` s'il existe, puis laisse l'environnement
l'emporter : en local on renseigne `.env` (voir `.env.example`), sur Vercel la variable vient du
tableau de bord. **Elle ne va jamais dans `app.config.ts`**, qui est suivi par Git.

Sans cle, le build passe, mais **la page rendue affiche un badge rouge « Invalid PrimeUI License »
en bas a droite** : ce n'est pas seulement un avertissement en console, et cela se voit du visiteur.
La cle est donc un prealable a la mise en ligne, pas une formalite de deploiement.

A noter tout de meme : une cle de licence front finit dans le bundle servi au navigateur, donc
publique par construction. La sortir du depot evite de la graver dans l'historique Git, cela n'en
fait pas un secret et rien ici ne merite un coffre-fort.

**Versions figees, sans `^` ni `~`.** Une landing page se reconstruit rarement ; une montee de
version silencieuse six mois plus tard se decouvrirait en production.

**Le rendu serveur est actif des la creation** et `app.routes.server.ts` prerend toutes les routes.
Verifie sur le HTML produit : le texte, le `<h1>` et le composant PrimeNG y sont, sans executer une
ligne de JavaScript. C'est le contraire de la fiche RapidAPI, qui sert une coquille.

**Sortie statique, aucun serveur a l'execution.** `ng new --ssr` genere par defaut
`outputMode: "server"` et un serveur Express dans `src/server.ts`, pour du rendu a la demande. Une
landing page n'a aucune donnee dynamique : tout est calculable au build, et un serveur Node ne
ferait que recalculer a chaque visite une page qui ne change pas, en ajoutant un demarrage a froid
et une surface a surveiller. `outputMode` est donc passe a `static`, `ssr.entry` retire et
`src/server.ts` supprime, avec `express` et `@types/express` de ses dependances. La sortie est un
seul dossier `browser/`, servi depuis le reseau de bordure de Vercel.

Le serveur de developpement (`npm start`) continue de construire un bundle serveur : c'est son
fonctionnement normal pour rendre le SSR pendant l'edition, cela ne concerne pas la production.

**`darkModeSelector` pointe sur `.parserag-dark`** et non sur le `system` par defaut : la page
portera son propre theme sombre (#68), et deux signaux differents finiraient par se contredire a
l'ecran.

## La page (issue #69)

Implementee d'apres `docs/brand/Design/project/ParseRAG Site v6.dc.html`, le bundle de handoff de
Claude Design. Onze sections, un composant standalone par section sous `src/app/landing/`, dans
l'ordre du brief : accroche, quick start, probleme, pourquoi ca compte, comment ca marche, seconde
passe, sortie, cas limites, plans, FAQ, CTA final.

### Les animations

Le mouvement du design est repris : vol des fragments vers le moteur, frappe des exemples de code,
curseur de lecture et reconstruction de l'ordre, cycle de la seconde passe, resolution du score de
confiance par paliers, balayage des cartes de cas limites, jauge de progression.

Trois choix les gouvernent, tous dans `src/app/landing/motion.ts`.

- **GSAP est empaquete, pas charge d'un CDN.** Le design le tirait de jsdelivr. Ici c'est une
  dependance npm, **importee dynamiquement** : elle sort du bundle initial et devient deux morceaux
  differes (70 ko et 44 ko). Licence GSAP : « Standard no charge », voir gsap.com/standard-license.
- **Aucun etat initial n'est pose en CSS.** C'est l'inversion la plus importante. Le design masquait
  le contenu par `html.pr-motion [data-fade] { opacity: 0 }` jusqu'a ce que GSAP confirme pouvoir
  animer, avec chien de garde et procedure de secours s'il ne repondait pas. Ici les etats de depart
  sont poses **par GSAP lui-meme** : sans JavaScript, sans reseau ou si l'import echoue, la page
  reste dans son etat final, complete et lisible. Le cas degrade n'a pas besoin d'etre rattrape, il
  est le defaut. Seuls les artefacts purement decoratifs (cadres de detection, barre de balayage)
  sont caches en CSS, parce que leur etat de repos est bien l'absence.
- **`prefers-reduced-motion` coupe en amont**, avant le chargement du paquet : qui a demande moins
  d'animation ne telecharge pas la bibliotheque qui les produit.

**Un ecart assume avec le design** : le lien « Legal » du pied de page est retire. Il pointait vers
`#start`, faute de page a atteindre. La politique de confidentialite arrive avec l'analytique
qu'elle doit decrire (#74).

**Deux sections etaient au brief sans figurer au design rendu** : le tableau des plans (6.7) et la
FAQ (6.8). Toutes deux sont implementees, faute de quoi la page laissait sans reponse les deux
dernieres questions qu'un lecteur se pose avant de s'abonner. Elles sont aussi ce dont dependent
#71 (balisage `FAQPage`, tableau annonce) et #72, ou elles sont la surface prevue pour les agents.

Le tableau des plans **ne porte aucun prix**, et c'est une consigne du brief plutot qu'un oubli :
les prix vivent sur le listing RapidAPI, qui est le systeme qui les facture. Recopies ici, ils se
perimeraient au premier ajustement de grille sans que rien ne le signale. La section renvoie donc
au listing pour le tarif, et un test verifie qu'aucun montant n'apparait sur la page.

Les noms affiches sont ceux du marketplace (BASIC/PRO/ULTRA/MEGA), pas les noms internes
(FREE/STARTER/PRO/SCALE) : le faux ami est reel, `PRO` cote RapidAPI vaut `STARTER` chez nous. Les
limites de pages, elles, sont celles que le backend applique (`parserag.page-limits`) ; les deux
doivent bouger ensemble, un chiffre affiche plus haut que celui qui est applique se payant en
`DOCUMENT_TOO_LONG` inattendu.

## Ce que PrimeNG coute, mesure au socle

| Bundle initial | Brut | Transfere |
|---|---|---|
| Avec PrimeNG (un seul bouton) | 534,2 ko | 121,5 ko |
| Sans PrimeNG | 245,2 ko | 67,1 ko |
| **Ecart** | **+289,0 ko** | **+54,4 ko** |

L'essentiel de cet ecart est le moteur de theme, paye une fois : les composants suivants (accordeon
de la FAQ, carte, tag) couteront bien moins cher a l'unite. Le chiffre est note ici parce qu'il est
le point de depart de l'issue #70, ou le budget se juge en taille **transferee** et non brute.

Page complete et animations comprises, le bundle initial est a **710,5 ko brut / 153,8 ko
transfere**. GSAP n'y figure pas : il est charge en deux morceaux differes de 72 ko et 44 ko.
La section des plans, `p-tag` compris, a coute **+10,4 ko brut / +1,7 ko transfere**, ce qui
confirme la lecture ci-dessus : le moteur de theme paye, un composant de plus ne se voit presque
pas.

Les budgets de `angular.json` sont a 760 ko d'avertissement et **850 ko d'erreur** sur le bundle
initial : reglages provisoires a resserrer en #70, ou ils se jugeront en taille **transferee** et
apres auto-hebergement des polices.

## A savoir

- **Node.** La CI et Vercel doivent rester sur **Node 24**. L'outillage Angular 22 declare
  `^22.22.2 || ^24.15.0 || >=26.0.0` : les majeures impaires ne passent jamais en LTS, et Node 25
  produit un avertissement a chaque commande.
- **Pas encore d'ESLint.** `frontend.yml` appelle `npm run lint --if-present`, donc l'etape est
  sautee sans echouer. A ajouter quand le code aura une surface qui le justifie.
- **Les polices viennent encore de Google Fonts.** L'auto-hebergement, qui supprimerait deux
  connexions tierces sur le chemin critique, releve de #70 ou il se mesure avec le reste du budget.
