# Choix de l'hébergeur : Railway, avec Postgres chez Supabase

**Décision prise le 22/08/2026**, avant le premier déploiement (issue #15). Ce document existe pour
qu'on n'ait pas à refaire le raisonnement dans six mois — et pour qu'on sache à quelles conditions il
faudrait le refaire.

## Ce que ParseRAG demande réellement

Les chiffres qui suivent viennent de mesures sur l'image de production, pas d'estimations :

| Contrainte | Valeur mesurée | Origine |
|---|---|---|
| Mémoire pendant **un seul** parse avec vision | **646 Mio** | conteneur, document de 16 pages |
| Comportement sous 1 Go | un parse de 16 pages passé de ~10 s à **702 s** | issue #57 |
| Démarrage de l'application | **5,2 s** | hors démarrage de la machine hôte |
| Disponibilité | permanente | l'origine est appelée par le proxy RapidAPI, pas par un humain |
| Base de données | deux tables, < 500 Mo | `api_keys`, `usage_records` |

Deux conséquences immédiates : il faut **au moins 2 Go** alloués, et l'application ne peut pas
s'endormir sans qu'on le décide en connaissance de cause (voir plus bas).

## La base est décidée : Supabase, plan gratuit

Ce choix précède la comparaison et la simplifie : **elle ne porte que sur l'hébergement de
l'application.** Les tarifs de base de données des trois fournisseurs ne pèsent donc pas dans la
balance — ce qui, au passage, écarte le seul poste où ils divergeaient vraiment (Fly facture son
Postgres managé $38/mois au premier palier, contre ~$3–5 chez Railway).

Le gratuit Supabase offre 500 Mo, 5 Go d'egress et deux projets actifs. Nos deux tables y tiennent
sans discussion. Les contreparties, elles, ne sont pas négligeables — elles font l'objet d'une
section entière plus bas.

## Aucun plan gratuit ne peut héberger l'application

| | Railway Free | Render Free | Fly.io |
|---|---|---|---|
| Mémoire par service | 0,5 Go | 512 Mo | pas de palier gratuit |
| Disponibilité | permanente | **endormissement après 15 min**, ~1 min de réveil | — |
| Crédits | $1/mois | — | — |

Les deux plafonds gratuits sont sous nos 646 Mio : un unique document ne passe pas. Le crédit de $1
de Railway achète 0,1 Go-mois de mémoire, aux tarifs ci-dessous : il ne finance rien de continu.

## Comparaison, application seule, 2 Go toujours actifs

| | **Railway Hobby** | **Fly.io** | Render Standard |
|---|---|---|---|
| Modèle | abonnement $5 (crédits $5) + **usage réel** | **provisionné**, sans abonnement | palier forfaitaire |
| Mémoire | $10,00/Go/mois *consommé* | ~$5/Go/mois *provisionné* (+ $2,02 de base) | inclus |
| CPU | $20,01/vCPU/mois *consommé* | inclus sur `shared-cpu-1x` | inclus |
| Egress | $0,05/Go | **$0,02/Go** | inclus jusqu'à un quota |
| **Coût mensuel estimé** | **$9–11** | **$10,74** | **$25** |

Détail du calcul Fly : `shared-cpu-1x` à $2,02 (256 Mo inclus) + 1,75 Go supplémentaires à $5 =
$10,77. Détail Railway : ~0,65 Go réellement consommés → ~$6,50, plus un CPU peu sollicité entre les
parses (~0,1–0,2 vCPU en moyenne) → $2–4.

**Railway et Fly sont à un dollar l'un de l'autre.** Ce n'est donc plus une décision de coût.

## Décision : Railway

Deux raisons, aucune n'étant le prix :

**1. La facturation suit la consommation, et notre consommation est en dents de scie.** Mesure à
l'appui : dans un conteneur de 2 Go, l'application occupe **646 Mio**, pas 1,5 Go — la JVM ne gonfle
pas jusqu'à son plafond. Railway facture ces 0,65 Go ; Fly et Render facturent les 2 Go provisionnés.
L'écart vaut environ $7/mois aujourd'hui, et il grandit si l'on doit sur-provisionner par prudence.

**2. Le service reste toujours actif sans qu'on ait à arbitrer.** Fly propose l'arrêt automatique des
machines — un levier réel que Railway n'a pas, et qui ferait tomber la facture à quelques dollars.
Mais notre application démarre en 5,2 s, auxquelles s'ajoute le lancement de la machine : le premier
consommateur après une période creuse paie cette latence, et elle apparaît dans les statistiques du
listing RapidAPI, que les acheteurs regardent avant de souscrire. Au lancement, la régularité vaut
mieux que quelques dollars.

Render est écarté dans tous les scénarios : son premier palier viable coûte $25 pour ce que les deux
autres facturent $10.

## Ce qu'implique une base hors du réseau de l'hébergeur

Ces points sont des tâches de déploiement, pas des remarques :

- **Pause après 7 jours d'inactivité.** Et nous y sommes plus exposés que la moyenne : depuis
  l'issue #54, **le chemin public ne fait aucune lecture en base**. Une semaine de trafic RapidAPI
  normal peut laisser la base intouchée. Suivraient `/api/v1/health` en `db: DOWN`, et surtout, au
  redéploiement suivant, **Flyway incapable de se connecter et l'application qui ne démarre pas**.
  Le `HEALTHCHECK` du conteneur ne protège pas : il interroge `/actuator/health`, qui ne touche pas
  la base. **Traité** : `DatabaseKeepAlive` exécute un `SELECT 1` toutes les 6 h
  (`parserag.database.keep-alive`), à désactiver sur une base auto-hébergée qui ne se met pas en
  pause. Le compteur `parserag.db.keepalive` en est le seul témoin — un ping qui échoue en silence
  ramène exactement la panne qu'il devait éviter.
- **Choisir le bon point de connexion.** La connexion directe (port 5432) est en **IPv6 sur les
  offres gratuites** ; l'IPv4 est une option payante. Si l'hébergeur ne sort pas en IPv6, il faut le
  **session pooler** (port 5432, IPv4 sur tous les plans), recommandé pour un serveur persistant.
  **Éviter le transaction pooler** (port 6543) : il ne supporte pas les *prepared statements*, que
  Hibernate utilise — il faudrait les désactiver côté pilote.
- **SSL.** La connexion traverse l'internet public : `sslmode=require` dans l'URL JDBC, ce que la
  configuration actuelle ne pose pas.
- **Latence.** Sans conséquence ici, et c'est heureux : le chemin public ne lit pas en base. Seuls
  l'administration et les migrations au démarrage la touchent.

## Ce qui ferait revoir cette décision

- **Le trafic devient assez creux pour que l'arrêt automatique soit intéressant** : Fly reprend
  l'avantage, et il est net. Le seuil est le moment où l'on accepte ~10 s sur la première requête
  d'une période creuse.
- **La facture Railway dépasse $20/mois** : son plan Pro ($20, $20 de crédits inclus) devient
  équivalent, puis meilleur.
- **La consommation réelle rejoint le provisionné** (parses plus lourds, concurrence relevée) :
  l'avantage de la facturation à l'usage s'efface et Fly repasse devant sur le prix.
- **Il faut plusieurs réplicas** : Hobby en autorise 6, mais le limiteur de débit et ses buckets sont
  en mémoire (issue #35) — c'est notre code qu'il faudrait changer d'abord, pas l'hébergeur.
- **Un besoin de SLA contractuel** : aucun des paliers retenus n'en offre.

## À faire au déploiement, indépendamment de l'hébergeur

- Allouer **~2 Go** et descendre `parse.max-concurrent` de 4 à **2** : la valeur est marquée
  provisoire depuis l'issue #56, et 646 Mio par parse ne laissent pas la place à quatre simultanés.
- Vérifier que le **ping de maintien en éveil** est actif (`parserag.db.keepalive`, tag
  `outcome=success`) après le premier déploiement.
- Ajouter `sslmode=require` et pointer le **session pooler** Supabase si l'hébergeur n'a pas d'egress
  IPv6.
- Surveiller `parserag.parse.slots_free` et la mémoire réelle avant de remonter l'un ou l'autre.

## Sources

[Railway pricing](https://railway.com/pricing) · [Render pricing](https://render.com/pricing) ·
[Fly.io pricing](https://fly.io/docs/about/pricing/) · [Fly Managed Postgres](https://fly.io/docs/mpg/) ·
[Supabase pricing](https://supabase.com/pricing) ·
[Supabase : options de connexion](https://supabase.com/docs/guides/database/connecting-to-postgres)

Tarifs relevés le 22/08/2026 — à revérifier avant de s'engager, ces grilles bougent souvent.
