package com.aura.service.dto;

/** An entity's poster image file, read from disk — see {@code EntityService#getEntityImage}. */
public record EntityImage(byte[] content, String contentType) {
}
