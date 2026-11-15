package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Which user gets the new movie entity; null = the calling admin. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddTrackedReleaseToPlatformRequest {
    private Long ownerId;
}
