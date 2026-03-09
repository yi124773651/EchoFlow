package com.echoflow.domain;

/**
 * Base class for all domain exceptions.
 *
 * <p>Domain exceptions must not carry HTTP status codes or any
 * framework-specific detail.  The web layer is responsible for
 * mapping them to appropriate HTTP responses.</p>
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
