# Publier ParseRAG sur RapidAPI : quoi mettre à chaque étape

**Runbook rédigé le 23/08/2026**, après le déploiement Railway et avant la mise en ligne du listing
(issue #54). Les valeurs à saisir sont **en anglais** : elles sont lues par les clients. Le
raisonnement autour reste en français.

Un seul champ n'est pas fourni ici, parce qu'on est seul à le connaître : l'URL publique Railway.
Elle est notée `https://<app>.up.railway.app` partout, à remplacer par la vraie avant de commencer.

> **Ordre imposé.** L'étape 4 (secret de proxy) doit être terminée **avant** l'étape 8 (publication).
> Entre les deux, l'origine Railway répond à quiconque connaît son URL, marketplace contournée.

> **Le dépôt GitHub est privé.** Aucun champ du listing ne doit pointer vers
> `github.com/SonnyHardy/ParseRAG` : un client qui clique tombe sur un 404 et en conclut que le
> produit est abandonné. Toute la documentation destinée aux clients vit donc **dans le listing
> lui-même** (Long Description, onglet Docs, onglet Endpoints). Voir l'étape 2, piège n°3.

---

## Étape 0 : à avoir sous la main

| Élément | Où le trouver |
|---|---|
| URL publique Railway | dashboard Railway → service → Settings → Domains |
| `docs/openapi.json` | dans le dépôt (snapshot généré, **ne pas l'éditer** pour l'import) |
| Accès aux variables d'env Railway | pour y coller le secret de proxy à l'étape 4 |
| Un PDF de test ~2 pages et un PDF scanné | pour l'étape 7 |

---

## Étape 1 : créer le projet API

`rapidapi.com/studio` → **Personal** → **Add API Project**.

| Champ | Valeur à saisir |
|---|---|
| **Name** | `ParseRAG` |
| **Description** | `Turn any PDF into embedding-ready JSON chunks for your RAG pipeline.` |
| **Category** | `Text Analysis` |
| **Team** | `Personal` |
| **Import data** | `OpenAPI` → téléverser `docs/openapi.json` |

Sur la catégorie : une seule est permise et elle est fixée par les admins Rapid. `Text Analysis`
décrit ce qu'on fait ; `Artificial Intelligence/Machine Learning` capte plus de trafic mais nous met
en face des APIs de LLM, où on n'a rien à défendre. `Text Analysis` d'abord, c'est modifiable après.

L'import fait gagner du temps mais laisse **trois pièges**, traités à l'étape suivante.

---

## Étape 2 : corriger ce que l'import a mal repris

### Piège n°1 : le serveur `localhost`

Le snapshot committé déclare un serveur *templaté* :

```json
"servers": [{ "url": "{baseUrl}", "variables": { "baseUrl": { "default": "http://localhost:8080" } } }]
```

RapidAPI en tire une Base URL `http://localhost:8080`. **Ne pas corriger le fichier du dépôt** : il
est régénéré par springdoc et le défaut local y est voulu. On corrige côté RapidAPI, à l'étape 3.

### Piège n°2 : le schéma de sécurité `X-API-Key`

La spec déclare `ApiKeyAuth` sur le header `X-API-Key`. C'est le chemin **auto-hébergé**. Sur le
marketplace, le consommateur envoie `X-RapidAPI-Key` et c'est le proxy qui authentifie.

→ **Hub Listing → Definitions → Security** : supprimer le schéma `ApiKeyAuth` importé, ne garder que
**RapidAPI Auth** (`X-RapidAPI-Host` + `X-RapidAPI-Key`, actif par défaut).

Laissé en place, le client se voit réclamer un `X-API-Key` qu'il n'a pas, et chaque appel de test
part sans le header : l'API est réputée cassée dès la première minute sur la page publique.

### Piège n°3 : le lien vers le dépôt privé

La spec porte un bloc de contact généré par springdoc :

```json
"contact": { "name": "ParseRAG", "url": "https://github.com/SonnyHardy/ParseRAG" }
```

Cette URL est **importée dans le listing** et affichée aux consommateurs. Le dépôt étant privé, elle
renvoie un 404 pour tout le monde sauf nous.

→ Après l'import, **vider ce champ de contact** côté RapidAPI (ou le remplacer par l'URL du listing
lui-même, une fois connue). Là encore, ne pas éditer `docs/openapi.json`, qui est régénéré : si on
veut corriger la source, cela se fait dans `OpenApiConfig`, pas dans le snapshot.

---

## Étape 3 : Hub Listing → General

| Champ | Valeur à saisir |
|---|---|
| **Logo** | PNG/JPEG, **500×500 px max** (voir « à produire » en fin de doc) |
| **Category** | `Text Analysis` |
| **Short Description** | `Turn any PDF into embedding-ready JSON chunks: column-aware text, headers and footers stripped, tables as structured JSON, OCR fallback for scans, and a confidence score on every chunk.` |
| **Website** | **laisser vide** (voir ci-dessous) |
| **Base URL** | `https://<app>.up.railway.app` |
| **Health Check URL** | `https://<app>.up.railway.app/actuator/health` |
| **Visibility** | `Private` **pour l'instant**, bascule en `Public` à l'étape 8 |

**Website** est optionnel et s'affiche comme « Product Website » sur l'onglet About. Le seul candidat
qu'on ait est le dépôt privé, donc un 404 : mieux vaut un champ vide qu'un lien mort, qui est un
signal de projet abandonné bien plus fort que l'absence de site. À remplir le jour où une page
d'accueil publique existe.

La Base URL s'arrête au domaine : la route `/api/v1/parse` est portée par l'endpoint (étape 5).
Le health check est testé une fois par jour ; `/actuator/health` est justement le seul endpoint non
authentifié, et il évite délibérément la base (issue #58), donc il ne tombera pas en rouge pour une
raison qui ne concerne pas la disponibilité de l'API.

### Long Description (Markdown)

```markdown
ParseRAG turns a PDF into clean, structured JSON that is ready to embed. One endpoint, one upload.

## What you get back

A list of chunks, each one an embedding call away from your vector store.

- **Reading order that survives multi-column layouts.** Text is assembled band by band, following the
  page's actual column structure, then verified: if the output shows the signature of interleaved
  columns, the page is re-assembled with different splits. A naive extractor stitches a two-column
  paper line by line and quietly poisons your index.
- **Repeated headers and footers stripped.** Three stacked detectors (geometric recurrence,
  boilerplate patterns, density clustering) remove the running titles, DOI lines and page numbers
  that would otherwise dilute every embedding in the document.
- **Tables as structured JSON**, not flattened text: `headers`, `rows`, and the caption when one is
  found. A quality gate decides whether a broken grid is worth re-reading with a vision model.
- **Scanned pages handled.** Image-only pages go through a vision model instead of coming back empty.
- **A confidence score on every chunk**, plus a `manual_review_needed` flag. A bad page never enters
  your index unnoticed, which is the one thing a plain PDF-to-text call cannot tell you.

## Response shape

```json
{
  "document_id": "doc_9f3c1a7b",
  "pages": 12,
  "language": "en",
  "processing_ms": 1843,
  "status": "ok",
  "chunks": [
    {
      "id": "chunk_doc_9f3c1a7b_000",
      "text": "Retrieval-augmented generation grounds a language model in an external corpus...",
      "type": "PARAGRAPH",
      "page": 1,
      "confidence": 0.94,
      "fallback_used": false,
      "manual_review_needed": false
    }
  ]
}
```

`type` is one of `PARAGRAPH`, `TABLE`, `FIGURE_CAPTION` or `HEADER_ARTIFACT`. A `TABLE` chunk also
carries a `table_json` object with `headers`, `rows` and an optional `caption`.

## Always returns a result

Vision work runs under a time budget. If the provider slows down, the remaining pages come back
flagged for review instead of the request hanging until a gateway timeout. A partial answer you can
act on beats a 504 you cannot.

## Limits

| Constraint | Value |
|---|---|
| Max file size | 50 MB, on every plan |
| Max pages per document | Plan-dependent, see Pricing |
| Content type | `multipart/form-data`, one part named `file` |

Parsing is synchronous, so budget a generous client timeout: a large scanned document goes through a
vision model page by page.

Errors come back in one shape on every failure, with a stable `error` code you can branch on:

```json
{ "error": "DOCUMENT_TOO_LONG", "message": "...", "status": 422 }
```

Runnable examples in cURL, Python and Java are on the **Docs** tab. The full response reference and
every error code are on the **Endpoints** tab, next to a live test console.
```

> Le bloc ci-dessus contient lui-même un bloc de code JSON. En le collant dans RapidAPI, vérifier que
> les trois backticks internes ne coupent pas le champ : si le rendu casse, remplacer ces clôtures
> par une indentation de quatre espaces.

### README

RapidAPI réclame un README en plus de la Long Description. Ce sont deux champs distincts et il ne
faut pas les confondre : la Long Description est l'accroche affichée sous le titre, quelques
paragraphes qu'un visiteur lit avant de décider s'il s'abonne ; le README est la **documentation
complète**, celle qu'on ouvre une fois abonné, quand on cherche le nom exact d'un champ ou le code
d'une erreur.

Le contenu prêt à coller est dans **[`rapidapi-readme.md`](rapidapi-readme.md)**, à copier
intégralement. Il couvre : le problème résolu, le démarrage rapide en quatre langages, la référence
de l'endpoint, la réponse champ par champ, les types de chunks, `table_json`, les bonnes pratiques
d'indexation, le tableau des erreurs avec une colonne « retry ? », les plans, et une FAQ.

Trois écarts délibérés avec le `README.md` du dépôt, à conserver si on le met à jour :

- **La section « Run it yourself » a disparu.** Elle décrit l'auto-hébergement depuis un dépôt privé,
  donc elle n'a aucun sens ici, et elle expliquerait à un client payant comment ne pas payer.
- **Les erreurs d'authentification ne sont pas documentées.** `MISSING_API_KEY`, `INVALID_API_KEY`,
  `MARKETPLACE_REQUIRED` et `INVALID_PROXY_SECRET` appartiennent au chemin interne : un consommateur
  du marketplace ne peut pas les rencontrer, et les publier reviendrait à décrire notre mécanique
  d'authentification à qui voudrait la contourner.
- **Les noms de plans sont ceux du marketplace** (BASIC/PRO/ULTRA/MEGA), pas nos noms internes
  (FREE/STARTER/PRO/SCALE). Le client ne connaît que les premiers. Attention au faux ami : `PRO` côté
  RapidAPI correspond à `STARTER` chez nous. Si la grille de l'étape 6 change, ce tableau change
  aussi.

### Terms of use (Markdown)

```markdown
## ParseRAG: Terms of Use

**Service.** ParseRAG accepts a PDF document and returns its extracted content as JSON chunks. The
service is provided on an "as is" basis, without warranty of any kind.

**Your content.** You keep all rights to the documents you upload. Documents are processed in memory
for the duration of the request. They are not stored, not logged, and never used for training. Pages
that require optical character recognition are sent to a third-party vision model provider for
transcription during that same request, and not retained beyond it.

**Accuracy.** Extraction quality depends on the source document. Every chunk carries a `confidence`
score and a `manual_review_needed` flag; you are responsible for how you use low-confidence output.
ParseRAG is not liable for decisions made on extracted content.

**Acceptable use.** Do not upload documents you are not entitled to process. Do not attempt to
circumvent plan quotas, rate limits, or the page limit per document.

**Availability.** No uptime guarantee is offered on any plan. Quotas, rate limits and prices may
change, with notice given through the RapidAPI marketplace.

**Contact.** Use the Discussions tab of this API listing.
```

Deux remarques sur ce champ :

- La documentation RapidAPI annonce un support **HTML** pour les Terms of use, pas explicitement
  Markdown. Le contenu ci-dessus est en Markdown comme demandé ; si le rendu de la popup affiche les
  `**` littéralement, la conversion est mécanique (`**x**` → `<strong>x</strong>`, `##` → `<h3>`) et
  le texte n'a pas à changer.
- Le contact passe par l'onglet **Discussions** du listing, seul canal public dont on dispose : le
  dépôt est privé et une adresse e-mail personnelle sur une page publique attire surtout du spam.

> La clause « not stored, not logged » est un engagement, pas une formule : elle impose de désactiver
> le logging des corps de requête à l'étape 4. Les deux vont ensemble.

---

## Étape 4 : Hub Listing → Gateway

C'est l'étape qui a le plus de conséquences techniques. Les défauts de RapidAPI ne conviennent pas.

### Firewall Settings : le secret de proxy

1. Copier la valeur de **`X-RapidAPI-Proxy-Secret`** affichée dans cette section.
2. Railway → service → Variables → `RAPIDAPI_PROXY_SECRET` = cette valeur.
3. Redéployer et attendre que le service soit à nouveau `UP`.

**Ce que cette variable déclenche, au-delà de la vérification du secret** : dès qu'elle est non vide,
`ApiKeyFilter` se referme. Seule une clé `admin` est encore acceptée en direct, tout le reste reçoit
`403 MARKETPLACE_REQUIRED`. C'est voulu (issue #56) : sans ce verrou, n'importe quelle clé interne
servirait l'origine en contournant quota et facturation. Vérifier avant de poser la variable qu'on a
bien une clé admin fonctionnelle (`ADMIN_KEY_HASH`), sinon on perd l'accès à `/api/v1/health`.

Pour une **rotation** ultérieure : poser l'ancien secret dans `RAPIDAPI_PROXY_SECRET_PREVIOUS`,
déployer, puis changer `RAPIDAPI_PROXY_SECRET`. Les deux créneaux sont acceptés en parallèle, ce qui
évite la coupure, proxy et backend ne pouvant pas basculer au même instant.

### Request Configurations : les deux valeurs à ne pas laisser par défaut

| Réglage | Valeur | Pourquoi celle-là |
|---|---|---|
| **Max request size** | `50 MB` (le maximum) | C'est exactement notre plafond de fichier. Plus bas, un PDF de 45 Mo est refusé **par le proxy**, avec un message qui n'est pas le nôtre : le client voit une erreur RapidAPI générique au lieu de `FILE_TOO_LARGE`. |
| **Proxy timeout** | `180 s` (le maximum) | Toute l'arithmétique du budget vision d'`application.yaml` est calée sur ce couperet : 180 s moins 20 s de marge donne les 160 s de plafond ParseRAG. Le réduire invalide le calcul et fait revenir les 504 sans corps que l'issue #57 a supprimés. |

### Threat Protection

- **SQL / JavaScript injection : désactivé.** Le corps de la requête est un PDF binaire. Le scanner
  n'y trouvera rien d'utile, et il lira 50 Mo d'octets arbitraires à chaque appel : faux positifs
  garantis sur des flux compressés, latence ajoutée sur le chemin chaud.
- **Content-Type validation : laisser libre.** Notre endpoint attend `multipart/form-data`, dont le
  header porte un paramètre `boundary` variable. Une validation stricte risque de rejeter des clients
  parfaitement corrects. La validation réelle est déjà faite chez nous : type MIME **et** signature
  `%PDF` des octets, indépendamment du header.

### Request Schema Validation

**`Pass through`.** `Strip and passthrough` et `Block` raisonnent sur des paramètres typés ; sur un
corps multipart binaire, le risque de voir la part `file` altérée ou rejetée est réel, pour un
bénéfice nul : l'endpoint n'a qu'un seul paramètre et il le valide lui-même.

### Logging Configurations

**Désactiver l'enregistrement des corps de requête et de réponse.** Deux raisons qui pointent dans le
même sens : ce sont les PDF des clients (c'est l'engagement pris dans les Terms of Use ci-dessus), et
ce sont 50 Mo par appel. Garder les headers si on veut du diagnostic, mais pas les corps.

### IP Whitelist

À reporter dans Railway **seulement si** on décide de filtrer par IP. Ce n'est pas nécessaire : le
secret de proxy joue déjà ce rôle et ne casse pas quand RapidAPI ajoute une région.

---

## Étape 5 : endpoint et documentation

L'import a créé l'endpoint. Vérifier :

| Champ | Valeur |
|---|---|
| **Name** | `Parse a PDF into chunks` |
| **Method** | `POST` |
| **Route** | `/api/v1/parse` |
| **Group / Tag** | `Parsing` |
| **Body** | `multipart/form-data`, une part `file` de type **binary**, requise |

**Description de l'endpoint :**

```markdown
Upload a PDF and receive its content as embedding-ready chunks.

`multipart/form-data`, one part named `file`. Maximum 50 MB. The page limit per document depends on
your plan; a longer document is rejected with `DOCUMENT_TOO_LONG` (422) rather than truncated.

The response carries `document_id`, `pages`, `language`, `processing_ms` and the `chunks` array. Each
chunk has `text`, `type` (PARAGRAPH, TABLE, FIGURE_CAPTION, HEADER_ARTIFACT), `page`, `confidence`,
`fallback_used` and `manual_review_needed`. TABLE chunks additionally carry `table_json` with
`headers`, `rows` and an optional `caption`.

Filter on `manual_review_needed == false` before indexing if you want only pages the parser is
confident about.
```

Les réponses d'erreur (`400`, `401`, `403`, `413`, `415`, `422`, `429`, `500`, `503`) sont déjà
importées depuis la spec. Vérifier qu'elles sont bien présentes : c'est ce qui remplit l'onglet
*Responses* de la page publique.

### Onglet Docs

Y coller les trois exemples de `docs/examples/` (cURL, Python, Java/OkHttp), en remplaçant l'URL par
`https://parserag.p.rapidapi.com` et en gardant les headers `X-RapidAPI-Key` / `X-RapidAPI-Host`.
Ajouter la note sur le timeout client : `readTimeout` de 5 minutes côté Java, `timeout=300` côté
Python, parce qu'un scan volumineux passe page par page dans un modèle de vision.

Le dépôt étant privé, **cet onglet est la seule documentation que le client verra**. Ce qui y manque
n'existe pas pour lui : y reporter aussi le tableau des codes d'erreur du README.

---

## Étape 6 : Hub Listing → Monetize

### Le défaut à corriger d'abord

Le plan **BASIC** est créé automatiquement avec **1 000 000 requêtes/mois** sur rapidapi.com. C'est
absurde ici : une requête peut être un PDF de 1 000 pages passé au modèle de vision. Ce chiffre est
le premier à changer.

### Grille proposée

| Plan | Price | Quota | Limit type | Overage | Rate limit | Pages/doc (appliqué par ParseRAG) |
|---|---|---|---|---|---|---|
| **BASIC** | Free | `50` requests / month | **Hard** | aucun | `2` / minute | 100 |
| **PRO** | `$9.99` / month | `1,000` requests / month | Soft | `$0.020` / request | `5` / minute | 300 |
| **ULTRA** | `$49.99` / month | `7,500` requests / month | Soft | `$0.012` / request | `10` / minute | 500 |
| **MEGA** | `$199.99` / month | `50,000` requests / month | Soft | `$0.006` / request | `20` / minute | 1 000 |

**Type de plan** : `Monthly Subscription` pour PRO/ULTRA/MEGA. **Objects** : garder le seul objet
`Requests`, nos quotas se comptant en requêtes et non en pages.

### Les pages par document ne sont pas un quota : ce sont des « Features »

C'est le piège du formulaire, et il fait perdre du temps parce que la colonne existe dans notre
grille sans avoir d'équivalent dans l'onglet Monetize. Un **Object** est un compteur qui se décrémente
sur une période (1 000 requêtes par mois, 10 e-mails par jour). Notre limite de pages n'est pas de
cette nature : c'est une contrainte **par appel**, vérifiée par ParseRAG au moment du parse, et rien
ne s'accumule d'un document au suivant. Créer un objet `Pages` exprimerait « 300 pages par mois », ce
qui n'est pas la règle et tromperait le client dès son deuxième document.

Le bon champ est **Features**, prévu pour ce que le plan permet plutôt que pour ce qu'il consomme.
Une Feature porte un nom, une description en info-bulle, un interrupteur par plan, et surtout une
**note propre à chaque plan**. C'est cette note qui porte le chiffre.

**Monetize → Add Feature** :

| Champ | Valeur |
|---|---|
| **Name** | `Pages per document` |
| **Description** | `The maximum number of pages ParseRAG reads in a single PDF. A longer document is rejected with DOCUMENT_TOO_LONG (422) rather than silently truncated. The 50 MB file size limit applies on every plan.` |
| **Associated Endpoints** | laisser vide : la Feature décrit une limite, elle n'ouvre pas un endpoint |

Puis activer l'interrupteur sur les quatre plans, chacun avec sa note :

| Plan | Note |
|---|---|
| BASIC | `Up to 100 pages` |
| PRO | `Up to 300 pages` |
| ULTRA | `Up to 500 pages` |
| MEGA | `Up to 1,000 pages` |

Le consommateur voit alors une ligne « Pages per document » sur l'onglet Pricing, cochée sur les
quatre plans, avec le chiffre qui change de colonne en colonne. C'est exactement la lecture qu'on
cherchait.

Tant qu'on y est, deux Features de plus coûtent une minute et remplissent une grille tarifaire qui,
sinon, ne montre qu'une différence de volume. Elles se cochent sur les quatre plans sans note :

| Name | Description |
|---|---|
| `Scanned page support` | `Image-only pages are transcribed by a vision model instead of coming back empty. Those chunks are marked fallback_used.` |
| `Structured table extraction` | `Tables come back as a table_json object with headers and rows, not as flattened text.` |

### Facturer un gros document plus cher qu'un petit, si on veut

Découvert en creusant ce point, à ne pas faire maintenant mais à ne pas oublier : le header de
réponse **`X-RapidAPI-Billing`** permet au backend de décrémenter le quota de plusieurs unités au
lieu d'une. C'est la réponse au déséquilibre noté depuis l'issue #54, où RapidAPI compte des requêtes
alors que notre coût réel suit les pages et les appels vision : un scan de 200 pages et un mémo d'une
page consomment aujourd'hui la même unité.

On pourrait, par exemple, facturer une unité par tranche de 50 pages. Ce n'est pas un réglage de
tableau de bord, il faut émettre le header depuis `ParseController`, et cela change le sens du quota
annoncé au client, donc la Long Description et le README avec. À traiter comme un chantier à part
entière, une fois qu'on aura des données d'usage réelles pour savoir si le déséquilibre coûte
vraiment de l'argent.

Trois raisons derrière ces chiffres :

1. **Hard limit sur le gratuit, soft ailleurs.** Un plan gratuit en dépassement facturé, c'est un
   litige ; un plan payant bloqué net en milieu de mois, c'est un client perdu.
2. **Les débits du listing restent sous les nôtres.** `RateLimitFilter` plafonne à 10/30/100/300
   requêtes par minute (FREE/STARTER/PRO/SCALE). Les 2/5/10/20 ci-dessus sont largement en dessous :
   c'est le proxy qui refuse en premier, jamais nous. L'inverse serait le pire des cas, celui où on
   refuse à un client un débit qu'il a payé à RapidAPI.
3. **Le prix suit le coût marginal réel**, qui n'est pas la requête mais l'appel vision. Un PDF natif
   de 200 pages ne coûte que du CPU ; un scan de 25 pages, ce sont 25 appels Gemini. La dégressivité
   de l'overage suppose que la proportion de scans ne monte pas avec le volume, hypothèse à revoir
   sur données réelles après un mois.

> **Ces montants sont une proposition, pas une contrainte technique.** Les quotas et débits, eux, le
> sont : voir l'avertissement sur la concurrence en fin de document.

### Si un plan privé est créé plus tard

`X-RapidAPI-Subscription` vaut alors **`CUSTOM`**, valeur absente de `parserag.rapidapi.plan-mapping`,
donc le client retombe sur `FREE` et ses 100 pages par document. C'est l'échec fermé voulu, mais il
surprendra. Ajouter `CUSTOM: <plan>` dans `application.yaml` **avant** de vendre le plan privé.

---

## Étape 7 : tester avant de publier

### 7.1 : l'origine répond bien au proxy (depuis un terminal local)

```bash
export ORIGIN="https://<app>.up.railway.app"
export SECRET="<X-RapidAPI-Proxy-Secret>"

# Doit renvoyer 200 et des chunks
curl -i -X POST "$ORIGIN/api/v1/parse" \
  -H "X-RapidAPI-Proxy-Secret: $SECRET" \
  -H "X-RapidAPI-Subscription: PRO" \
  -H "X-RapidAPI-User: smoke-test" \
  -F "file=@sample.pdf"
```

Trois vérifications qui ne coûtent qu'un appel chacune :

```bash
# Sans secret et sans cle -> 401 MISSING_API_KEY (le chemin interne prend le relais)
curl -s -X POST "$ORIGIN/api/v1/parse" -F "file=@sample.pdf" | head -c 200

# Mauvais secret -> 403 INVALID_PROXY_SECRET (jamais de repli sur la cle interne)
curl -s -X POST "$ORIGIN/api/v1/parse" -H "X-RapidAPI-Proxy-Secret: wrong" \
  -F "file=@sample.pdf" | head -c 200

# Plan inconnu -> doit se comporter comme FREE (100 pages), pas planter
curl -s -X POST "$ORIGIN/api/v1/parse" -H "X-RapidAPI-Proxy-Secret: $SECRET" \
  -H "X-RapidAPI-Subscription: NOPE" -F "file=@sample-150-pages.pdf" | head -c 200
```

Le dernier doit répondre `DOCUMENT_TOO_LONG` (422) : c'est la preuve que le repli sur `FREE` marche.

### 7.2 : depuis la console RapidAPI

Onglet **Endpoints** de son propre listing → sélectionner l'endpoint → téléverser un PDF dans la part
`file` → **Test Endpoint**. Vérifier dans l'ordre : un `200` avec des chunks, puis un PDF scanné pour
confirmer que `fallback_used: true` apparaît (donc que `GOOGLE_API_KEY` est bien posé sur Railway),
puis un fichier non-PDF pour voir remonter `INVALID_FILE_FORMAT` dans notre format d'erreur et non
dans celui du proxy.

---

## Étape 8 : publier

**Hub Listing → General → Visibility** : cocher la case **Terms of Service**, puis basculer le
sélecteur sur **Public**. (Une API détenue par une *team* demande l'approbation d'un admin
d'organisation ; en `Personal`, la bascule est immédiate.)

**Ne pas publier avant** d'avoir coché :

- [ ] `RAPIDAPI_PROXY_SECRET` posé sur Railway et service redéployé
- [ ] appel sans secret vérifié comme refusé (7.1)
- [ ] `ApiKeyAuth` / `X-API-Key` supprimé des schémas de sécurité (étape 2)
- [ ] contact `github.com/SonnyHardy/ParseRAG` vidé du listing, dépôt privé (étape 2)
- [ ] aucun autre champ public ne pointe vers le dépôt (Website, Long Description, Terms of use)
- [ ] Base URL = Railway, pas `localhost` (étape 3)
- [ ] Max request size à 50 MB **et** proxy timeout à 180 s (étape 4)
- [ ] logging des corps de requête désactivé (étape 4)
- [ ] quota BASIC descendu de 1 000 000 à 50 (étape 6)
- [ ] README collé depuis `rapidapi-readme.md`, tableau des plans cohérent avec l'étape 6
- [ ] onglet Docs rempli, seule documentation visible par le client (étape 5)
- [ ] un parse réel réussi depuis la console RapidAPI (7.2)

---

## Le point à trancher avant de vendre du volume

`parserag.parse.max-concurrent` vaut **2**. Ce n'est pas un réglage frileux : un seul parse avec
vision a été mesuré à **646 Mio** sur l'image de production, et le conteneur Railway est à 2 Go. Deux
places, c'est ce que la mémoire permet.

La conséquence est une capacité de service **globale**, tous clients confondus : de l'ordre de 20 à
25 parses/minute sur des PDF natifs rapides, et d'à peine 2 à 4/minute si les documents passent par
la vision. Au-delà, c'est `503 SERVICE_BUSY` après 15 s d'attente.

Les débits du tableau de l'étape 6 (2/5/10/20 par minute) sont calés là-dessus : ils tiennent tant
qu'on n'a pas plusieurs abonnés ULTRA/MEGA actifs en même temps. **Un 503 ne se distingue pas d'une
panne pour le client, et il coûte une note sur le listing**, bien plus cher qu'un 429 qui est un
signal compris et documenté.

Deux leviers, dans cet ordre :

1. Monter la mémoire Railway à 4 Go et `PARSE_MAX_CONCURRENT` à 4. C'est une variable
   d'environnement, pas un redéploiement de code.
2. Ne relever les débits du listing qu'après, et jamais au-dessus des paliers de `RateLimitFilter`.

À surveiller dès la première semaine : le taux de `503 SERVICE_BUSY` dans les métriques
(`parserag.*`), qui est l'indicateur avancé de ce plafond.

---

## Logo et visuels

Les sources vivent dans le dossier **[`brand/ParseRAG_iconography/export`](brand/ParseRAG_iconography/export)**. Chaque visuel s'y exporte en PNG à la largeur voulue.

| Visuel | Où il va | Export |
|---|---|---|
| **Logo** | Hub Listing → General → Logo | PNG **500** de large (le plafond RapidAPI) |
| **Avant / après** | en tête de la Long Description | PNG **1200** de large |
| **Pipeline** | section « How it works » du README | PNG **1200** de large |

Le logo reprend le modèle en bandes qui est le cœur technique du produit : un bandeau pleine largeur
au-dessus de deux colonnes séparées par une gouttière, exactement ce que `PageGeometryAnalyzer`
détecte. La barre ambrée est le chunk qui porte un score de confiance. Il reste lisible à 64 px,
taille à laquelle il s'affiche réellement sur les tuiles du Hub, parce qu'il ne contient que des
aplats sans détail fin.

L'avant / après est le visuel qui compte. Il montre le symptôme d'entrelacement sur un extrait à deux
colonnes, les lignes de la seconde colonne en rouge pour qu'on voie d'un coup d'œil que deux textes
sont mélangés, et il porte le chiffre mesuré sur le corpus : 87 chunks en revue manuelle contre 8.
C'est le seul visuel qui montre le problème au lieu de le décrire.

### Ce qui manque encore

- **Une capture d'écran de réponse réelle**, prise sur la console de test RapidAPI une fois le
  listing monté (étape 7.2). Un JSON authentique avec un vrai `document_id` est plus convaincant
  qu'un exemple rédigé, et il ne peut être produit qu'après la mise en place.
- **Une page d'accueil publique**, le jour où on veut remplir le champ Website. Tant que le dépôt est
  privé, le listing est notre seule vitrine.

---

## Sources

- [Add API: Getting Started](https://docs.rapidapi.com/docs/add-api-getting-started)
- [Hub Listing: General Tab](https://docs.rapidapi.com/docs/hub-listing-general-tab)
- [Hub Listing: Gateway Tab](https://docs.rapidapi.com/docs/hub-listing-gateway-tab)
- [Hub Listing: Monetize Tab](https://docs.rapidapi.com/docs/hub-listing-monetize-tab)
- [Additional Request Headers](https://docs.rapidapi.com/docs/additional-request-headers)
- [Configuring API Security](https://docs.rapidapi.com/docs/configuring-api-security)
