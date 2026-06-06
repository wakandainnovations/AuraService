package com.aura.service.dto;

import com.aura.service.entity.Mention;
import com.aura.service.enums.Platform;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight view of a reported {@link Mention}, attached to abuse-report responses so the
 * "Abuse Reports" audit screen can link straight back to the original post. Only the fields the
 * frontend consumes are exposed, deliberately avoiding serialization of the full JPA entity.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MentionSummaryDto {

    private Long id;
    private String author;
    private String text;
    private Platform platform;
    private String permalink;
    private String sourceUrl;

    /** Projects a mention into the summary the frontend's "View original post" link consumes. */
    public static MentionSummaryDto from(Mention mention) {
        return MentionSummaryDto.builder()
                .id(mention.getId())
                .author(mention.getAuthor())
                .text(mention.getContent())
                .platform(mention.getPlatform())
                .permalink(mention.getPermalink())
                // Mentions carry a single canonical permalink; there is no separate source URL.
                .sourceUrl(null)
                .build();
    }
}
