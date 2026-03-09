package com.echoflow.domain;

/**
 * Thrown when a requested entity does not exist.
 */
public class EntityNotFoundException extends DomainException {

    public EntityNotFoundException(String entityName, Object id) {
        super(entityName + " not found: " + id);
    }
}
