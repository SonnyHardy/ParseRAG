# frontend

Emplacement de la landing page Angular. **Le projet n'est pas encore initialise** : c'est l'objet de
l'issue #67, qui doit d'abord trancher la licence PrimeNG (la v22 n'est plus open source et exige une
cle, la v21 reste MIT).

Ce dossier existe des maintenant pour une raison precise : la structure du monorepo, les filtres de
chemins de la CI et les regles `.gitignore` de l'issue #66 se posent et se verifient toutes ensemble.
Les ecrire contre un dossier qui n'existe pas, c'est les ecrire sans savoir si elles fonctionnent.

## Ce qui est deja cable

- `.github/workflows/frontend.yml` se declenche sur `frontend/**`, mais reste **inerte** tant que
  `frontend/package.json` n'existe pas (garde `hashFiles`). Un rouge qui ne veut rien dire est un
  rouge qu'on apprend a ignorer.
- `.gitignore` couvre deja `node_modules/`, `dist/`, `.angular/`, `.vercel/`, `coverage/` et les
  `.env` de ce dossier.
- Le backend ne voit rien de tout cela : son contexte de build Docker est `backend/`, pas la racine.

## Contraintes a respecter en initialisant (issue #67)

- **Rendu serveur active des la creation.** Toute la valeur SEO de la page tient dans le HTML servi
  avant execution du JavaScript ; l'ajouter apres coup coute plus cher.
- Le deploiement Vercel pointera son *Root Directory* sur ce dossier : il doit rester autonome, sans
  jamais remonter au-dessus de `../` autrement que pour lire `docs/openapi.json`.
