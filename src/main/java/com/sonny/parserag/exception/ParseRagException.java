package com.sonny.parserag.exception;

public class ParseRagException extends RuntimeException {

    public ParseRagException(String message) {
        super(message);
    }

    public ParseRagException(String message, Throwable cause) {
        super(message, cause);
    }
}
