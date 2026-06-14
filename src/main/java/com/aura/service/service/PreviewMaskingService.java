package com.aura.service.service;

/**
 * Produces a masked, "blurred" teaser of a real feature payload for users who are not entitled to it.
 *
 * <p>The masked preview preserves the <em>shape</em> of the payload (object keys, nesting) so the UI
 * can render a believable locked view, but it guarantees that <strong>no real underlying value
 * survives</strong>:
 * <ul>
 *   <li>strings become a fixed starred placeholder;</li>
 *   <li>numbers collapse to a coarse, digit-free magnitude bucket (never the exact value);</li>
 *   <li>lists are truncated to a short teaser length;</li>
 *   <li>booleans are dropped (a boolean is a real value too).</li>
 * </ul>
 */
public interface PreviewMaskingService {

    /** Returns a masked copy of {@code payload} (or {@code null} when {@code payload} is {@code null}). */
    Object mask(Object payload);
}
