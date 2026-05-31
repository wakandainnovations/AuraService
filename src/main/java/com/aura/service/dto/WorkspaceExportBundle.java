package com.aura.service.dto;

import com.aura.service.entity.SentimentAlert;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Single-document, proprietary backup of one user's workspace: their reply templates,
 * alert rules, playbooks, and tracked entities.
 * <p>
 * This is deliberately the <em>only</em> export shape — there is no per-resource CSV or
 * other interoperable format. The same document is consumed verbatim by the import
 * endpoint, making round-trip backup/restore trivial while offering no convenient path
 * for bulk migration of individual resources to another tool.
 * <p>
 * NOTE FOR PRODUCT REVIEW: the JSON-only design is an intentional retention/lock-in
 * choice ("easy backup, hard exit"), not a technical limitation. See {@code WorkspaceController}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkspaceExportBundle {

    /** Proprietary format marker. Import rejects any payload not bearing this exact value. */
    public static final String FORMAT = "aura-workspace-export";

    /** Current schema version. Import only accepts a matching version. */
    public static final int CURRENT_VERSION = 1;

    private String format;
    private int version;
    private Instant exportedAt;

    /** Username of the workspace owner at export time. Informational only; not used on import. */
    private String owner;

    @Builder.Default
    private List<TemplateItem> templates = new ArrayList<>();

    @Builder.Default
    private List<AlertRuleItem> alertRules = new ArrayList<>();

    @Builder.Default
    private List<PlaybookItem> playbooks = new ArrayList<>();

    @Builder.Default
    private List<TrackedEntityItem> trackedEntities = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TemplateItem {
        private String name;
        private String body;
        private String tone;
        private int useCount;
        private Instant createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AlertRuleItem {
        private Long entityId;
        private SentimentAlert.Kind kind;
        private double threshold;
        @Builder.Default
        private List<String> channels = new ArrayList<>();
        private boolean enabled;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PlaybookItem {
        private Long entityId;
        private Long mentionId;
        private String title;
        private String planText;
        @Builder.Default
        private List<String> tags = new ArrayList<>();
        // Without this, Lombok's boolean getter isFavorite() serializes as "favorite",
        // mismatching the "isFavorite" key used elsewhere in the API.
        @JsonProperty("isFavorite")
        private boolean isFavorite;
        private Instant createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TrackedEntityItem {
        private Long entityId;
        private Instant lastSeenAt;
    }
}
