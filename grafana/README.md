# Dashboards & alertes Grafana (issue #38, §6)

Deux dashboards et trois règles d'alerte, versionnés ici pour passer en revue de code et se
redéployer à l'identique.

| Fichier | Ce qu'il répond |
|---|---|
| `dashboards/parserag-pipeline.json` | Où passe le temps, qu'est-ce qui échoue, quel volume — l'outil de debug |
| `dashboards/parserag-cost-and-limits.json` | Ce que coûte la vision et qui tape contre les limites — la vue produit |
| `alerting/parserag-alerts.yaml` | Taux d'erreur `/parse`, saturation du pool Hikari, base injoignable |

## Import

**Dashboards** — Grafana → Dashboards → New → Import → *Upload JSON file*. À l'import, Grafana
demande quelle source Prometheus utiliser : les panneaux passent par une variable `datasource`, il
n'y a donc aucun UID en dur à corriger.

**Alertes** — remplacer `<DATASOURCE_UID>` par l'UID de la source Prometheus (Connections → Data
sources, l'UID est dans l'URL), puis importer via Alerting → Alert rules → Import.

## Noms de séries : d'où ils viennent

Les requêtes utilisent les noms **après traduction OTLP → Prometheus**, qui n'est pas la simple
copie du nom Micrometer :

| Métrique Micrometer | Série dans Mimir |
|---|---|
| `parserag.parse.total` (compteur) | `parserag_parse_total` |
| `parserag.stage.duration` (timer, secondes) | `parserag_stage_duration_seconds_bucket` / `_sum` / `_count` |
| `parserag.document.pages` (distribution) | `parserag_document_pages_sum` / `_count` |
| `parserag.ratelimit.buckets_active` (gauge) | `parserag_ratelimit_buckets_active` |

Les règles appliquées : les points deviennent des underscores, l'unité est suffixée (`_seconds`),
et les compteurs reçoivent `_total` — sans doublon quand le nom s'y termine déjà, ce qui est le cas
de `parserag.parse.total`.

Ces noms sont **dérivés du payload OTLP réellement émis** (capturé et décodé pendant
l'implémentation), pas devinés — mais la dernière étape de traduction a lieu côté Grafana et n'a pas
pu être vérifiée sur l'instance. Si un panneau affiche « No data », ouvrir Explore et taper
`parserag_` : l'autocomplétion donne le nom exact, et l'écart sera un suffixe.

## Ce qui conditionne les données

- **`OTEL_ENABLED=true`** dans `.env`, sans quoi rien n'est exporté.
- **Les traces sont échantillonnées** (`OTEL_SAMPLING`, défaut `0.1`) : une requête sur dix a une
  trace dans Tempo. Les **métriques**, elles, comptent tout — les deux dashboards ci-dessus ne
  dépendent donc pas de l'échantillonnage.
- **L'export métriques part toutes les 60 s** : la dernière minute avant un arrêt brutal de l'app
  peut ne jamais partir.
- Les compteurs sont **cumulatifs** et repartent de zéro à chaque redémarrage. C'est la raison pour
  laquelle tous les panneaux passent par `rate()` ou `increase()` plutôt que d'afficher la valeur
  brute, qui dessinerait des dents de scie.

## Budget de cardinalité

Les tags sont bornés par construction (voir `ParseRagMetrics`) : `plan` (4), `stage` (5), `outcome`,
`error_code`, `detector` (3), `provider`, `model`, `type`, `reason` (3), `borderless`. Aucune clé
d'API, aucun identifiant, aucun nom de fichier — ni explosion de séries, ni donnée personnelle
envoyée au backend de métriques.
