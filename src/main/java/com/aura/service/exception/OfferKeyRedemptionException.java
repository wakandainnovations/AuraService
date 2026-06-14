package com.aura.service.exception;

/**
 * Thrown when an offer-key redemption is refused. Mapped to {@code 400 Bad Request} (see
 * {@link GlobalExceptionHandler}) with the structured body {@code { reason, message }} so the UI can
 * tell the user exactly why the key did not work (unknown, deactivated, expired, or fully redeemed).
 *
 * <p>Like the other licensing exceptions, this carries <strong>no price/cost information</strong>.
 */
public class OfferKeyRedemptionException extends RuntimeException {

    /** Why the redemption was refused; surfaced verbatim in the 400 body's {@code reason}. */
    public enum Reason {
        /** No key exists for the supplied code. */
        INVALID,
        /** The key exists but has been deactivated. */
        INACTIVE,
        /** The key's expiry has passed. */
        EXPIRED,
        /** The key has already been redeemed the maximum number of times. */
        EXHAUSTED
    }

    private final Reason reason;

    public OfferKeyRedemptionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
