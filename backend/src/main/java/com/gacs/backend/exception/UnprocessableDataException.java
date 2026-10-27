package com.gacs.backend.exception;

/**
 * The request is valid, but data the server needs is missing or unusable (HTTP 422). For the optimiser:
 * a job's baseline hours are not covered by the price forecast, so the baseline cannot be priced.
 * The message names the missing data.
 */
public class UnprocessableDataException extends RuntimeException {

    public UnprocessableDataException(String message) {
        super(message);
    }
}
