package com.aura.service.dto;

import com.aura.service.enums.LicenseTier;

/**
 * Generic envelope for a tier-gated feature's response. It replaces the old hard {@code 403}: every
 * gated endpoint now answers {@code 200} with this envelope so the UI can render the feature either
 * live or as a locked, blurred teaser that entices an upgrade.
 *
 * <ul>
 *   <li>{@code entitled} — whether the current user may use the feature (a {@code ROLE_ADMIN}, or an
 *       effective tier at least {@code requiredTier}).</li>
 *   <li>{@code requiredTier} — the minimum tier the feature needs. Always set, so the UI can show
 *       which tier unlocks it.</li>
 *   <li>{@code data} — the real payload, present only when {@code entitled}; otherwise {@code null}.</li>
 *   <li>{@code preview} — a masked teaser of the payload, present only when <em>not</em>
 *       {@code entitled}; otherwise {@code null}. It never carries a real underlying value.</li>
 * </ul>
 *
 * <p>Deliberately price-free: it names the required tier, never its cost.
 */
public class EntitledResponse<T> {

    private final boolean entitled;
    private final LicenseTier requiredTier;
    private final T data;
    private final Object preview;

    public EntitledResponse(boolean entitled, LicenseTier requiredTier, T data, Object preview) {
        this.entitled = entitled;
        this.requiredTier = requiredTier;
        this.data = data;
        this.preview = preview;
    }

    /** Entitled response: the real {@code data}, no preview. */
    public static <T> EntitledResponse<T> entitled(LicenseTier requiredTier, T data) {
        return new EntitledResponse<>(true, requiredTier, data, null);
    }

    /** Locked response: a masked {@code preview} (may be {@code null} for non-previewable actions), no data. */
    public static <T> EntitledResponse<T> locked(LicenseTier requiredTier, Object preview) {
        return new EntitledResponse<>(false, requiredTier, null, preview);
    }

    public boolean isEntitled() {
        return entitled;
    }

    public LicenseTier getRequiredTier() {
        return requiredTier;
    }

    public T getData() {
        return data;
    }

    public Object getPreview() {
        return preview;
    }
}
