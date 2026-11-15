package com.aura.service.dto;

/** A user-facing movie entity created for a tracked release, and who owns it. */
public record PlatformEntityRef(Long entityId, Long ownerId, String ownerUsername) {
}
