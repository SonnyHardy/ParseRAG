package com.sonny.parserag.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
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

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ErrorResponse> handleQuotaExceeded(QuotaExceededException ex) {
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ErrorResponse(
                        "QUOTA_EXCEEDED",
                        ex.getMessage(),
                        HttpStatus.TOO_MANY_REQUESTS.value()
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

    public record ErrorResponse(
            String error,
            String message,
            int status
    ) {}
}