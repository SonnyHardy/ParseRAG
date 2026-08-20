package com.sonny.parserag.filter;

/**
 * Attributs de requête posés par la chaîne de filtres et lus par les controllers.
 * <p>
 * Regroupés ici parce qu'ils sont un <em>contrat</em> entre deux couches qui ne se voient pas :
 * un filtre écrit, un controller lit, et une faute de frappe d'un côté ne se manifeste que par un
 * {@code null} silencieux de l'autre.
 */
public final class RequestAttributes {

    private RequestAttributes() {
    }

    /**
     * Clé API interne résolue en base ({@code ApiKey}). Présente uniquement sur le chemin
     * d'authentification interne — jamais sur une requête venue de RapidAPI, où aucune clé de
     * notre côté n'intervient.
     */
    public static final String API_KEY = "apiKey";

    /**
     * Plan applicable à la requête ({@code Plan}). Posé par <em>les deux</em> chemins
     * d'authentification, c'est lui que lit le pipeline — il n'a pas à savoir d'où vient l'appel.
     */
    public static final String PLAN = "plan";

    /**
     * Identité du consommateur telle qu'annoncée par le proxy ({@code X-RapidAPI-User}).
     * Journalisation et diagnostic uniquement : jamais en tag de métrique, la cardinalité serait
     * non bornée et la donnée personnelle partirait vers le backend d'observabilité.
     */
    public static final String RAPIDAPI_USER = "rapidApiUser";
}
