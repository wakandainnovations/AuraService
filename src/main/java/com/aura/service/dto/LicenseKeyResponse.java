package com.aura.service.dto;

/**
 * Returned when a license is issued/assigned — carries just the generated license key.
 */
public record LicenseKeyResponse(String licenseKey) {
}
