package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Summary returned after restoring a {@link WorkspaceExportBundle}. Each count reflects the
 * number of items the import created (or, for tracked entities, created-or-updated) for the
 * authenticated user.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkspaceImportResult {
    private int templatesImported;
    private int alertRulesImported;
    private int playbooksImported;
    private int trackedEntitiesImported;
}
