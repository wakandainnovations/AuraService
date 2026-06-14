package com.aura.service.exception;

/**
 * Thrown when a resource is absent <em>or</em> not owned by the current user. Mapped to
 * {@code 404 Not Found} (see {@link GlobalExceptionHandler}) deliberately: a caller must not be able
 * to distinguish "does not exist" from "exists but belongs to someone else", otherwise the API would
 * leak the existence of other users' entities.
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
