package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewAspectBackfillResponse {
    private Long entityId;
    private String entityName;
    // "started" or "already_in_progress" — the actual classification count is only known once the
    // background run finishes; see ReviewAspectBreakdownService#backfillEntityAsync's log lines, or
    // poll GET .../review-aspect-breakdown for the growing totalClassifiedPosts.
    private String status;
}
