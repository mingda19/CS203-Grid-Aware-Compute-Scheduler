package com.gacs.backend.exception;

/** The request conflicts with the current state of a record (409). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
