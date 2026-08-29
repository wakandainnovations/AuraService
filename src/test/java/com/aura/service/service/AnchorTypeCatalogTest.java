package com.aura.service.service;

import com.aura.service.service.AnchorTypeCatalog.AnchorTypeDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnchorTypeCatalogTest {

    @Test
    void hasAllFourAnchorTypes() {
        assertThat(AnchorTypeCatalog.all()).hasSize(4);
    }

    @Test
    void everyEntryHasCompleteMetadata() {
        for (AnchorTypeDefinition def : AnchorTypeCatalog.all().values()) {
            assertThat(def.type()).isNotNull();
            assertThat(def.name()).isNotBlank();
            assertThat(def.function()).isNotBlank();
            assertThat(def.barrierAddressed()).isNotBlank();
            assertThat(def.example()).isNotBlank();
        }
    }
}
