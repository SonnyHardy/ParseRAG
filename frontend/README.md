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
npm run build    # build de production, prerendu, puis generation et controle des artefacts
npm test         # vitest, sans mode veille

npm run serve:dist    # sert dist/ comme Vercel : brotli et en-tetes de cache
npm run lighthouse    # mediane de 5 passages mobile sur le serveur ci-dessus
npm run fonts:fetch   # regenere public/fonts/ et src/fonts.scss (hors build)
npm run images:build  # regenere les WebP et l'image sociale (hors build)
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

## Referencement : metadonnees et donnees structurees (issue #71)

Aucune page indexable au monde ne parle de ParseRAG : le depot est prive, et la fiche RapidAPI n'est
dans aucun des huit sitemaps de rapidapi.com. Cette page est donc la seule surface de referencement
du produit, et ce qu'elle declare compte autant que ce qu'elle dit.

### Tout est genere depuis les donnees de la page

C'est l'invariant qui structure `src/app/seo/`. Le JSON-LD ne recopie rien : les six questions de la
FAQ viennent de `landing/faq-data.ts`, les quatre plans de `landing/plans-data.ts`, **les memes
modules que les composants affichent**. Google demande que le balisage decrive ce que le visiteur
voit ; ici, publier une reponse que la page n'affiche plus demanderait de la supprimer des deux
endroits a la fois, ce qui n'arrive pas par distraction.

Les titres suivent la meme regle : `app.routes.ts` lit ceux de `seo/site.ts`, la ou vivent aussi la
description, la canonique et le sitemap.

Un seul `<script type="application/ld+json">` par page, un graphe dont les entites se referencent
par `@id`. L'accueil porte `Organization`, `WebSite`, `SoftwareApplication` et `FAQPage` ; les pages
legales portent `Organization`, `WebSite`, `WebPage` et `BreadcrumbList`. Pas de fil d'Ariane sur
l'accueil : a un seul echelon il n'apprend rien et Google le signale.

### Le prix est dans le balisage et nulle part sur la page

C'est une asymetrie voulue des deux cotes, et elle merite d'etre comprise avant d'etre modifiee.
Le brief de design interdit tout montant a l'ecran, parce qu'un tarif recopie se perime sans que
rien ne le signale ; l'issue #71 demande en revanche un bloc `offers` refletant les quatre plans, et
un `Offer` sans prix n'a guere de sens. Chaque offre porte donc en `url` l'adresse ou ce prix est
publie et facture, le listing RapidAPI. Un test verifie qu'aucun montant n'apparait dans le texte de
la page ; un autre, que les quatre offres correspondent aux quatre plans du tableau.

### Le sitemap ne peut pas mentir

`scripts/build-seo-files.mjs` ecrit `robots.txt` et `sitemap.xml` dans la sortie du build, jamais a
la main : un fichier maintenu a la main survit a la suppression de la page qu'il annonce.

Deux details font le travail :

- **La liste des pages vient du disque**, pas d'un tableau. Le script parcourt `dist/` et retient
  chaque `index.html` prerendu ; il enumere donc exactement les pages qui existent.
- **L'origine vient de la balise `canonical` de l'accueil**, pas d'une seconde declaration. Le
  sitemap et les canoniques ne peuvent donc pas se contredire. Si la canonique manque, le script
  echoue : c'est le signe que le prerendu n'a pas pose les metadonnees.

Ni `lastmod`, ni `priority`, ni `changefreq`. Google ignore les deux derniers depuis des annees et
attend du premier qu'il reflete une vraie modification de contenu ; rempli avec la date du build, il
annoncerait que les trois pages changent a chaque deploiement.

### L'image sociale

`public/brand/og-cover.png`, en 1200x630, dessinee par `scripts/build-og-image.mjs`. Pas le logo
carre : l'apercu d'un lien partage est presque toujours rogne au format paysage, et un logo carre y
perd la moitie du nom.

Le nom du produit y est **l'image du wordmark, pas du texte**, ce qui rend le lettrage de marque
independant des polices installees sur la machine qui execute le script. Seule l'accroche est
composee en texte et depend donc du systeme ; c'est acceptable parce que le PNG produit est
versionne, mais il faut le savoir avant de relancer le script ailleurs.

### Ce qui est verifie, et par quoi

`scripts/check-seo.mjs` tourne en `postbuild`, donc en CI. Il automatise le critere de validation de
l'issue — « un curl montre toutes les balises listees, sans exception » — sur les trois pages :
`lang`, titre, description (longueur comprise), canonique **absolue et propre a la page**, les dix
balises Open Graph, les cinq Twitter, l'existence reelle du fichier vise par `og:image`, et un
unique bloc JSON-LD analysable portant les noeuds attendus.

Une balise de referencement ne manque jamais bruyamment : la page s'affiche exactement pareil sans
elle, et l'absence se paie des mois plus tard en indexation. C'est le profil exact d'un defaut qui
doit faire echouer un build. Verifie par sabotage : poser la canonique de l'accueil sur `/terms`
fait echouer la CI avec `canonical vers / au lieu de /terms`.

Les tests unitaires, eux, ne verifient pas la presence des balises mais **la coherence** : que la
FAQ balisee soit mot pour mot la FAQ affichee, que les offres soient les plans du tableau, que
chaque route de l'application ait ses metadonnees, et que deux appels successifs au service `Seo`
mettent a jour au lieu de dupliquer — le `<head>` n'etant pas hydrate par Angular, un service qui
ajoute produirait deux canoniques.

### Ce qui reste ouvert, et pourquoi

**Le domaine n'est pas acquis.** `SITE_ORIGIN` porte `https://parserag.dev`, premier candidat de
l'issue #73, et c'est le seul endroit a changer si l'arbitrage bascule.

Trois cases de l'issue attendent la mise en ligne et ne peuvent pas etre cochees ici : la validation
par l'outil de test des resultats enrichis, qui exige une URL publique ; la propriete Search Console
et le sitemap soumis ; le compte Bing Webmaster Tools. Elles relevent du jour du deploiement, avec
#73.

## Referencement pour les agents (issue #72)

Une part croissante de la decouverte d'une API ne passe plus par une page de resultats mais par une
reponse d'assistant. Le lecteur n'est alors pas quelqu'un qui parcourt une mise en page : c'est un
extracteur qui cherche des faits. Deux consequences vont a rebours du reflexe du moment.

**On veut etre lu par ces robots.** `robots.txt` autorise onze agents **nommement** — GPTBot,
OAI-SearchBot, ChatGPT-User, ClaudeBot, Claude-User, anthropic-ai, PerplexityBot, Google-Extended,
CCBot, Applebot-Extended, meta-externalagent. Un `Allow` nomme plutot que le seul `User-agent: *`,
parce que plusieurs de ces robots appliquent par defaut une politique restrictive quand aucune regle
ne les vise. Le silence n'y est pas lu comme une autorisation.

**Le cas `Google-Extended` est tranche dans le script, pour ne pas rouvrir le debat** : il gouverne
l'usage des pages dans les reponses generatives de Google et n'a aucun effet sur le classement dans
la recherche classique. L'autoriser sert donc exactement ce que cette issue cherche, sans rien
couter au referencement.

### Il n'y a qu'une source, et les scripts la lisent

C'est l'invariant du chantier. `build-agent-files.mjs` **importe les memes modules TypeScript** que
les composants affichent — plans, questions, codes d'erreur, mesures, exemples de code. Node charge
un `.ts` directement depuis sa version 23.6 ; la CI et Vercel sont sur Node 24.

Cela vaut mieux qu'une discipline : ce qu'un agent lit et ce qu'un humain lit ne **peuvent pas**
diverger, puisqu'il n'existe pas deux endroits ou les ecrire. `check-agent-files.mjs` verrouille la
propriete en verifiant que chaque plan, chaque code d'erreur, chaque question et chaque exemple se
retrouve dans `llms-full.txt`.

Verifie par sabotage : ajouter un code d'erreur `PASSWORD_PROTECTED` aux donnees sans rien
regenerer fait echouer le build sur `code d'erreur PASSWORD_PROTECTED absent`.

### Les trois fichiers publies

| URL | Pour qui |
|---|---|
| `/llms.txt` | L'entree courte : ce qu'est ParseRAG, les faits essentiels, les liens profonds. |
| `/llms-full.txt` | Tout en un seul fichier : endpoint, exemples, plans, erreurs, FAQ. |
| `/openapi.json` | Le contrat machine, pour l'agent qui veut generer un appel. |

**`openapi.json` est une variante publiee, pas une copie.** Le snapshot de `docs/openapi.json` est
genere par springdoc et decrit le deploiement **auto-heberge** : serveur `localhost`,
authentification `X-API-Key`, contact pointant vers un depot prive. Publie tel quel, il ferait
generer a un agent un appel vers localhost avec le mauvais en-tete, et l'API passerait pour cassee
avant d'avoir ete essayee. Les trois corrections sont celles du runbook
(`docs/rapidapi-listing-setup.md`, etape 2, pieges 1 a 3), appliquees a la volee.

Une limite assumee : la prose generee par springdoc mentionne encore `X-API-Key` dans la description
generale et dans celle d'une reponse 401. C'est exact — elle decrit les deux chemins de deploiement
— et la corriger demanderait de la chirurgie de chaines sur un artefact genere, ce qui casserait au
premier changement de formulation.

### Une page `/documentation`, hors du chemin de conversion

Les faits de reference — la requete, la reponse, les limites, les dix codes d'erreur — vivent sur
une page a eux (`src/app/documentation/`), pas sur la vitrine.

**Une premiere version les posait sur la page d'accueil, et c'etait une erreur de destination.**
Dix codes HTTP ne servent pas un visiteur qui decide, ils servent quelqu'un qui integre. La page
d'accueil n'a qu'une fonction, convertir une visite en abonnement ; l'allonger de contenu de
reference coutait 6,7 Ko et 99 elements de DOM sur le chemin de conversion, alors que le blocage
residuel de cette page est justement le temps de blocage (#70).

La page est donc **absente de la barre de navigation** et n'a qu'un lien, en pied de page. Elle est
en revanche dans le `sitemap.xml`, dans `llms.txt` et dans `llms-full.txt` : trouvable par qui la
cherche, humain ou robot, sans peser sur la page qui doit convaincre.

Son contenu vient des memes modules que le reste — exemples de `code-snippets.ts`, limites
composees depuis `plans-data.ts`, erreurs de `errors-data.ts`. Une documentation qui divergerait de
la page serait pire qu'absente.

### Les exemples sont dans de vrais `<pre><code>`

Avec de vrais caracteres de fin de ligne, et la nuance n'est pas academique. Une premiere version
separait les lignes par `display: block` : le rendu etait identique a l'oeil et le texte extrait
tenait sur **une seule ligne**. Un exemple de code sur une ligne est inutilisable, pour un agent
comme pour un copier-coller.

Deux pieges rencontres au passage, tous deux silencieux : une interpolation dont le resultat ne
contient que du blanc est supprimee a la compilation, et un bloc de controle dont le corps n'est
qu'un retour a la ligne aussi. Colle a du texte reel, le caractere survit.

### Ce qui reste ouvert

**`docs/openapi.json` vit a la racine du depot, hors de `frontend/`.** Le build le lit et echoue
bruyamment s'il ne le trouve pas. Sur Vercel, dont le *Root Directory* pointe sur `frontend`, cela
suppose que la construction inclue les fichiers exterieurs : c'est une dependance a verifier en #73,
et le message d'erreur du script y renvoie.

Le critere de validation de l'issue — poser a un assistant une question sur l'extraction de tableaux
en fournissant le site comme source — demande un site en ligne. Il releve de #73.

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
