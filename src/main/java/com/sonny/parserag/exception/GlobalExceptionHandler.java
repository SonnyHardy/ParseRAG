package com.sonny.parserag.exception;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Intercepte toutes les exceptions et retourne un JSON d'erreur uniforme.
 * <p>
 * Format :
 * {
 *   "error": "INVALID_FILE_FORMAT",
 *   "message": "Only PDF files are supported.",
 *   "status": 400
 * }
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── Erreurs métier ParseRAG ──────────────────────────────────────────────

    @ExceptionHandler(ParseRagException.class)
    public ResponseEntity<ErrorResponse> handleParseRag(ParseRagException ex) {
        log.warn("Business error [{}]: {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity
                .status(ex.getStatus())
                .body(new ErrorResponse(
                        ex.getErrorCode(),
                        ex.getMessage(),
                        ex.getStatus().value()
                ));
    }

    // ── Fichier trop lourd (Spring rejette avant d'atteindre le controller) ──

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxSize(MaxUploadSizeExceededException ex) {
        log.warn("Upload size exceeded: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatusCode.valueOf(413))
                .body(new ErrorResponse(
                        "FILE_TOO_LARGE",
                        "File size exceeds the 50 MB limit.",
                        413
                ));
    }

    // ── Catch-all ────────────────────────────────────────────────────────────

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType() {
        return ResponseEntity
                .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ErrorResponse(
                        "UNSUPPORTED_MEDIA_TYPE",
                        "Only PDF files are supported.",
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(
                        "INTERNAL_ERROR",
                        "An unexpected error occurred. Please try again later.",
                        500
                ));
    }

    // ── Format d'erreur standard ─────────────────────────────────────────────

    @Schema(name = "ErrorResponse", description = "Uniform error body returned by every endpoint.")
    public record ErrorResponse(

            @Schema(description = "Stable machine-readable code. Branch on this, not on the message.",
                    example = "FILE_TOO_LARGE")
            String error,

            @Schema(description = "Human-readable explanation. May change between releases.",
                    example = "File size 63.2 MB exceeds the 50 MB limit.")
            String message,

            @Schema(description = "HTTP status, repeated in the body for clients that only read it.",
                    example = "413")
            int status
    ) {}
}