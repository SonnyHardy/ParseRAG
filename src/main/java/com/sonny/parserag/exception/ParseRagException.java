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

    public ParseRagException(HttpStatusCode status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

}