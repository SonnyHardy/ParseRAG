package com.sonny.parserag.exception;

import lombok.Getter;
import org.springframework.http.HttpStatusCode;

/**
 * Exception métier de base pour toutes les erreurs ParseRAG.
 * Interceptée par GlobalExceptionHandler qui la convertit
 * en réponse JSON structurée.
 * <p>
 * Exemple :
 *   throw new ParseRagException(
 *       HttpStatus.BAD_REQUEST,
 *       "INVALID_FILE_FORMAT",
 *       "Only PDF files are supported."
 *   );
 */
@Getter
public class ParseRagException extends RuntimeException {

    private final HttpStatusCode status;
    private final String errorCode;

    /**
     * Délai suggéré avant de réessayer, rendu en en-tête {@code Retry-After} par
     * {@code GlobalExceptionHandler} ; {@code null} quand réessayer n'a pas de sens (format de
     * fichier invalide, document trop long : les réémettre à l'identique échouera pareillement).
     * <p>
     * C'est une <em>indication</em>, pas une promesse : nul ne sait quand une place se libérera.
     * Mais un {@code 503} sans {@code Retry-After} laisse le client choisir entre marteler le
     * service et abandonner — les deux mauvais.
     */
    private final Integer retryAfterSeconds;

    public ParseRagException(HttpStatusCode status, String errorCode, String message) {
        this(status, errorCode, message, null);
    }

    public ParseRagException(HttpStatusCode status, String errorCode, String message,
                             Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

}