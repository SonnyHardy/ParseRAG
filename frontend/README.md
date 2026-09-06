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
npm start        # serveur de developpement
npm run build    # build de production, prerendu, puis controles de prerendu et de budget
npm test         # vitest, sans mode veille

npm run serve:dist   # sert dist/ comme Vercel : brotli et en-tetes de cache
npm run lighthouse   # mediane de 5 passages mobile sur le serveur ci-dessus
npm run fonts:fetch  # regenere public/fonts/ et src/fonts.scss (hors build)
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

## Rendu statique et performance (issue #70)

Le contenu doit etre dans le HTML servi, et la page doit etre rapide. Ce sont deux prerequis du
referencement, pas du confort. Cette issue est nee d'une observation sur la fiche RapidAPI, qui
sert une coquille : 297 ko de HTML, pas de meta description, contenu rendu cote client.

### La premiere mesure etait fausse, et c'est le point de depart

Servie par `python -m http.server`, la page notait **61** en performance. Sur ces 61, l'essentiel
n'etait pas la page : 743 Kio d'« economies possibles » etaient l'absence de compression du serveur
de test. Servie compressee, la meme sortie notait **90**.

D'ou `scripts/serve-dist.mjs`, versionne avec le reste : il reproduit de Vercel les trois seuls
comportements qui pesent sur la mesure, brotli, les en-tetes de cache et le routage des routes
prerendues. Un chiffre obtenu autrement ne decrit pas le site.

Second piege, du meme ordre : le meme build mesure quatre fois d'affilee a donne 62, 93, 85 et 93.
Lighthouse simule le reseau mais partage le vrai processeur. `scripts/lighthouse.mjs` prend donc la
**mediane** de N passages et affiche l'ecart ; un ecart large veut dire qu'il faut recommencer, pas
que la page a change.

### Ce qui a change

- **Polices auto-hebergees.** Plus aucune requete vers `fonts.googleapis.com` ni
  `fonts.gstatic.com`. Deux fichiers variables `wght@400..700` au lieu de sept graisses, sous-
  ensembles `latin` et `latin-ext` seulement, `font-display: swap`, et prechargement du seul latin
  de la police de texte. Voir `scripts/fetch-fonts.mjs`, qui explique chacun de ces choix.
- **Images en WebP avec repli PNG**, en `<picture>`. Contre l'attente, **l'AVIF perd** sur ces
  images : ce sont des aplats et du texte, ou son codage n'a rien a factoriser. Sur `wordmark-ink`,
  AVIF q60 fait 4,2 Kio quand le WebP sans perte en fait 2,3, et sur le favicon l'AVIF est plus
  gros que le PNG d'origine. Proposer les deux aurait ete pire qu'inutile, `<picture>` retenant le
  premier format reconnu.
- **Hydratation differee section par section**, en `@defer (hydrate on viewport)`. Le bundle
  d'entree passe de **693 ko a 287 ko bruts** : accordeon, onglets et tag partent en morceaux
  charges au defilement. Le HTML servi, lui, ne perd rien.
- **GSAP est charge a l'inactivite du navigateur**, plus juste apres le premier rendu. Son
  evaluation formait une tache longue de **299 ms** au pire moment, alors que toutes les animations
  de la page sont declenchees au defilement : le report ne change rien a l'ecran.
- **Trois contrastes sous le seuil AA**, corriges. Deux etaient le meme lapsus pris en miroir, un
  jeton de fond sombre pose sur fond clair et l'inverse. Ce n'etait pas theorique : sans
  JavaScript, quatre des cinq blocs de « comment ca marche » restent dans l'etat au repos.

### Deux garde-fous, tous deux verifies par sabotage

`postbuild` enchaine deux controles, donc la CI aussi.

`scripts/check-prerender.mjs` lit le HTML **produit** et y cherche une phrase par section. Il
existe pour un piege precis : entre `@defer (hydrate on viewport)` et `@defer (on viewport)`, il y
a un mot et un monde. Le second rend le substitut cote serveur, et dix sections sur onze quittent
le HTML servi sans que rien ne change a l'ecran. Verifie : ce sabotage fait echouer le build.
Les tests unitaires ne repondent pas a cette question, puisqu'ils lisent le DOM apres execution.

Le texte est compare **balises retirees**. Chercher la chaine brute ne marche pas : la coloration
syntaxique decoupe `curl -X POST` en plusieurs `<span>`, et un `grep` naif conclut a tort que
l'exemple manque. La verification a commence par ce faux positif.

`scripts/check-budget.mjs` mesure le bundle d'entree en taille **transferee**, ce que les budgets
d'`angular.json` ne savent pas faire. Etat du jour :

```
index.html          179,2 Kio bruts    23,1 Kio transferes
main-*.js           287,1 Kio bruts    51,3 Kio transferes
styles-*.css         18,8 Kio bruts     3,9 Kio transferes
TOTAL               485,2 Kio bruts    78,2 Kio transferes   (plafond 95 Kio)
```

Le plafond est un cliquet pose au-dessus de la mesure du jour : on l'abaisse quand on gagne.
Les budgets bruts d'`angular.json` restent en second filet, ramenes de 760/850 a **620/700 ko**.

### Les chiffres Lighthouse

Mediane de 9 passages, profil mobile, Lighthouse 12.8.2, sur `serve:dist` :

| Axe | Mediane | Etendue |
|---|---|---|
| Performance | **89** | 85-97 |
| Accessibilite | **100** | 100 |
| Bonnes pratiques | **100** | 100 |
| Referencement | **100** | 100 |

FCP 1,96 s, LCP 2,41 s, CLS 0,007, SI 1,96 s, **TBT 274 ms** (107 a 391).

**La performance n'atteint pas les 95 vises, et le TBT est le seul responsable.** Toutes les
autres metriques sont dans le vert et stables. Le temps de blocage, lui, va de 107 ms machine au
calme, ou la performance monte a 97, a 391 ms machine chargee.

Ce qu'il reste a gagner n'est plus a portee de cette issue, et la mesure le designe : le poste
principal du fil d'execution n'est pas le script mais **« Style & Layout », 676 ms**, contre 637 ms
d'evaluation de script. C'est PrimeNG qui injecte son theme a l'execution, sous forme d'une
trentaine de feuilles. Le bundle d'entree a fondu de moitie sans y toucher, parce que le moteur de
theme est charge par `providePrimeNG` dans `app.config.ts` et ne peut pas etre differe : il doit
avoir agi avant le premier rendu.

Reduire ce poste demande de rouvrir le choix de #67, pas d'ajuster #70. Les quatre composants
utilises sont un bouton, des onglets, un accordeon et un tag ; le bouton, en particulier, ne sert a
rien ici, `.pr-cta` redefinissant deja tout son style a coups de `!important`. A chiffrer dans une
issue dediee.

## Ce que PrimeNG coute, mesure au socle

| Bundle initial | Brut | Transfere |
|---|---|---|
| Avec PrimeNG (un seul bouton) | 534,2 ko | 121,5 ko |
| Sans PrimeNG | 245,2 ko | 67,1 ko |
| **Ecart** | **+289,0 ko** | **+54,4 ko** |

L'essentiel de cet ecart est le moteur de theme, paye une fois : les composants suivants (accordeon
de la FAQ, carte, tag) couteront bien moins cher a l'unite. Le chiffre est note ici parce qu'il est
le point de depart de l'issue #70, ou le budget se juge en taille **transferee** et non brute.

La section des plans, `p-tag` compris, a coute **+10,4 ko brut / +1,7 ko transfere**, ce qui
confirme la lecture ci-dessus : le moteur de theme paye, un composant de plus ne se voit presque
pas. Ce qu'il en reste apres #70 est plus bas.

## A savoir

- **Node.** La CI et Vercel doivent rester sur **Node 24**. L'outillage Angular 22 declare
  `^22.22.2 || ^24.15.0 || >=26.0.0` : les majeures impaires ne passent jamais en LTS, et Node 25
  produit un avertissement a chaque commande.
- **Pas encore d'ESLint.** `frontend.yml` appelle `npm run lint --if-present`, donc l'etape est
  sautee sans echouer. A ajouter quand le code aura une surface qui le justifie.
- **Deux scripts ne tournent pas au build**, et c'est voulu : `fonts:fetch` et
  `scripts/build-images.mjs` produisent des fichiers versionnes. Un build ne doit dependre ni du
  reseau ni d'une bibliotheque native pour reussir, la panne DNS de #69 l'ayant montre a ses
  depens. On les relance a la main quand une police ou une image de marque change.
