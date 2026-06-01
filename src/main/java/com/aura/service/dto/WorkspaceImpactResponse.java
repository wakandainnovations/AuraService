package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A reflection of what the user has accumulated in their workspace — the "investment made visible".
 * <p>
 * Every field is a count of value the user has already built up (templates, playbooks, posted
 * replies, upheld reports, …). {@link #highlights} turns the non-zero numbers into short,
 * display-ready sentences ("Your playbook library has handled 12 crises.") so a client can surface
 * the compounding without re-deriving copy. Surfaced via {@code GET /api/workspace/impact}; intended
 * for the dashboard header and the morning digest.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkspaceImpactResponse {

    /** Entities the user actively watches (distinct viewed entities). */
    private long entitiesWatched;

    /** Reply templates the user has authored — the reusable library. */
    private long templateCount;

    /** Total times those templates have seeded a reply (sum of per-template use counts). */
    private long draftsSavedByTemplates;

    /** Crisis playbooks the user has built. */
    private long playbookCount;

    /** Of those, how many are starred for quick reuse. */
    private long favoritePlaybookCount;

    /** Replies the user has actually posted to platforms. */
    private long repliesPosted;

    /** Supporters the user has rallied across all mobilize actions. */
    private long alliesMobilized;

    /** Abuse reports the user has filed. */
    private long abuseReportsFiled;

    /** Of those filed, how many platforms upheld (posts removed). */
    private long abuseReportsUpheld;

    /** Display-ready sentences for the non-zero metrics, ordered most-rewarding first. */
    private List<String> highlights;
}
