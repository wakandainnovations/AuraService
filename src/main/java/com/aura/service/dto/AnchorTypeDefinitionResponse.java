package com.aura.service.dto;

import com.aura.service.enums.AnchorType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Movie-facing view of an {@link com.aura.service.service.AnchorTypeCatalog.AnchorTypeDefinition}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnchorTypeDefinitionResponse {
    private AnchorType type;
    private String name;
    private String function;
    private String barrierAddressed;
    private String example;
}
