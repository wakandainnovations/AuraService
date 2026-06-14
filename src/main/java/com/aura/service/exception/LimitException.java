package com.aura.service.exception;

/**
 * Thrown when an operation would breach one of the per-tier limits defined by
 * {@link com.aura.service.enums.LicenseTier} (the single source of truth for limits). Mapped to
 * {@code 409 Conflict} (see {@link GlobalExceptionHandler}) with the structured body
 * {@code { limitType, limit, current }}.
 *
 * <p>This carries <strong>no price/cost information</strong>: limits are user-facing, but pricing is
 * admin-only and must never travel with a limit rejection. Keep any message free of cost details.
 */
public class LimitException extends RuntimeException {

    /** Which per-tier cap was hit. The name is surfaced verbatim in the 409 body's {@code limitType}. */
    public enum LimitType {
        ENTITIES,
        KEYWORDS
    }

    private final LimitType limitType;

    /** The tier's cap for this limit (e.g. maxEntities / maxKeywords). */
    private final int limit;

    /**
     * The value that violates the cap: for {@link LimitType#ENTITIES} the user's current owned-entity
     * count (already {@code >= limit}); for {@link LimitType#KEYWORDS} the total the operation would
     * produce across all the user's entities (which {@code > limit}).
     */
    private final int current;

    public LimitException(LimitType limitType, int limit, int current) {
        super(limitType + " limit reached (limit=" + limit + ", current=" + current + ")");
        this.limitType = limitType;
        this.limit = limit;
        this.current = current;
    }

    public LimitType getLimitType() {
        return limitType;
    }

    public int getLimit() {
        return limit;
    }

    public int getCurrent() {
        return current;
    }
}
