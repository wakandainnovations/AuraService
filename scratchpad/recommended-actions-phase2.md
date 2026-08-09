This is Phase 2 of 2 for the "Recommended Actions" panel on the movie Command Center (Phase 1,
already implemented and reviewed, built `RecommendedActionCandidateService.buildCandidateActions
(Long entityId)`, returning fully-numeric `RecommendedActionCandidate` records — category,
confidencePct, windowStartDaysFromRelease/windowEndDaysFromRelease, windowLabel, and supporting
facts — with zero LLM involvement). Do not modify Phase 1's logic or re-derive any of its numbers;
treat RecommendedActionCandidateService as a black box you consume. Phase 2 adds: an LLM step that
selects from and adds prose to those candidates, plus caching, scheduling, and the controller
endpoint.

HARD CONSTRAINT, carried over from Phase 1 and just as binding here: the LLM must never invent,
guess, estimate, or compute a number. category, confidencePct, and both window day-offsets come
from the Phase 1 candidate untouched — the LLM never sees a schema field for any of them and never
supplies one. Its only output is which candidates to keep and what prose to write about them, and
that prose may only restate numbers already present in the candidate's own supporting facts.

## LLM's role: select and phrase only, never emit a number

Send the LLM the full candidate list from
RecommendedActionCandidateService.buildCandidateActions(entityId) (each candidate already carrying
its final category, confidence, window, and supporting facts — presented as read-only context, not
as a schema for the LLM to reproduce) plus the entity's own facts (genre, language, industry, budget
tier, days to release). Ask it to:
(a) select which candidates are genuinely relevant/worth surfacing for this specific movie (e.g.
    skip an "off-screen controversy mitigation" candidate if nothing in the facts suggests
    controversy risk; skip brand-partnership candidates for a budget tier where that's not
    realistic), capping at a sensible ~8-15 selected actions across all phases;
(b) write a natural, specific one-to-two sentence "reason" for each selected candidate, using ONLY
    the numbers already present in that candidate's own supporting facts (it may restate them in
    prose, it must not add, alter, round creatively, or introduce any new figure).

The requested output JSON is an array of {candidateId, reason} (optionally {candidateId, title} if
you want the LLM to sharpen the title too) — it must NOT include confidencePct, category, or any
day-offset field. Any candidateId the LLM returns that doesn't match one you sent must be dropped
(defensive parse — log a warning, same spirit as CommandCenterSummaryService defaulting an
unrecognized highlight type rather than trusting the LLM's output blindly). As a cheap defensive
check, scan the returned reason text for digit sequences and log a warning if one doesn't appear
anywhere in that candidate's own supporting facts — not a hard requirement, but worth adding given
how central "no invented numbers" is to this feature.

Add llm.prompt.generate.recommended.actions to application.properties next to the other
llm.prompt.* entries, following their multi-line backslash-continuation string style. State
outright, the same way CommandCenterSummaryService's prompt does, that the model must not invent
any fact, number, or statistic beyond what's in the candidate list, and that it must return each
candidateId unchanged so the response can be merged back onto the full server-computed record.

## Data model

- New entity RecommendedActionsCache (uniqueConstraint on entity_id, mirroring
  CommandCenterSummaryCache): id, entityId, entityName, actionsJson (TEXT, serialized
  List<RecommendedActionItem> — the merged Phase 1 + LLM-selection result), daysToReleaseAtGeneration
  (int, informational), generatedAt (Instant).
- New repository RecommendedActionsCacheRepository with findByEntityId(Long).
- New DTO RecommendedActionItem: category, title, reason, confidencePct, relatedFactor,
  windowStartDaysFromRelease, windowEndDaysFromRelease, windowLabel — all populated from the Phase 1
  candidate; only `reason` (and optionally a refined `title`) is LLM-authored text layered onto an
  otherwise-Java-built record. windowStartDaysFromRelease/windowEndDaysFromRelease MUST stay numeric
  (signed ints), not a free-text label, so the service can filter deterministically — same reasoning
  as compareLabel() in CommandCenterSummaryService: never make the API layer parse prose to decide
  business logic.
- New DTO RecommendedActionsResponse: entityId, entityName, daysToRelease (today vs
  entity.releaseDate, signed int, null if entity has no releaseDate), actions (the list, already
  filtered — see below), generatedAt.

## Service

RecommendedActionsService, patterned exactly on AudiencePulseAspectsService's
@Transactional getCachedOrGenerate/regenerateAndStore/persist/toGeneratedContent shape (read that
class end-to-end before writing this one — same cache/@Scheduled/refresh-param shape):
- generate(): call RecommendedActionCandidateService.buildCandidateActions(entityId) (Phase 1,
  already built) -> selectAndPhraseWithLlm(candidates, facts) (the one LLM call in this feature,
  using the llm.prompt.generate.recommended.actions template) -> merge the LLM's
  {candidateId, reason} selections back onto their full candidate records by id -> persist the
  merged list. If the LLM call fails or returns nothing usable, fall back to persisting the
  candidates unfiltered/unphrased with a generic reason built from their own supporting facts (e.g.
  "N comparable [genre]/[language] releases support this") rather than surfacing nothing — document
  this fallback clearly with a comment.
- getRecommendedActions(entityId, refresh, boolean allPhases) reads/regenerates the cached plan,
  then by default filters to actions whose [windowStartDaysFromRelease, windowEndDaysFromRelease]
  currently contains today's signed day-offset from entity.releaseDate (compute this server-side,
  clock-injected like the existing services use Clock — do not use LocalDate.now() directly, for
  testability). allPhases=true (query param) returns the whole plan ungrouped/unfiltered so the
  marketing team can see the full campaign roadmap, not just what's due now.
- Entities with no releaseDate: cannot compute a current window, so return the full plan
  (equivalent to allPhases=true) rather than an empty list — document this fallback clearly.
- @Scheduled refresh: since the underlying facts (genre/budget/historical comps) change rarely but
  the *current phase* changes daily, you don't need to regenerate the LLM plan more than once a
  day — use @Scheduled(fixedDelayString = "PT24H") rather than copying the PT6H used for
  sentiment-driven panels; explain the difference in a comment the way the existing services
  explain their own cadence.

## Controller

Add to DashboardController (same file as ai-summary/todays-highlights/audience-pulse-aspects),
following their exact shape — @GetMapping("/{entityId}/recommended-actions"), assertOwned(entityId)
first, refresh + allPhases query params (both defaultValue "false"), delegate to the service,
ResponseEntity.ok(...). Do not add feature-gating (AGGREGATED_INTEL etc.) unless asked — the
sibling panels on this same controller are ungated. Even though this endpoint internally reuses
DashboardService.getHourlyActivity (via Phase 1's candidate service), that method is itself already
ungated on the same controller, so this stays consistent — do not accidentally pull in a gated
dependency (e.g. the /api/marketing/audience-patterns feature, which IS gated behind
AGGREGATED_INTEL) anywhere in this chain.

## Testing

Write RecommendedActionsServiceTest and a controller test for the new endpoint, matching the style
of the existing sibling tests you can find for CheckpointImpactTest/DashboardControllerWhatsNewTest/
SentimentDeltaTest in src/test/java/com/aura/service/. Mock LLMService and
RecommendedActionCandidateService (both interfaces) and the repositories — do NOT attempt to mock
concrete classes like ManagedEntity or the JPA entities directly; this project's Java 25 setup
breaks Mockito's inline mocking of concrete classes, so only interfaces get mocked. Cover:
- cache hit returns without calling the LLM or the candidate service,
- cache miss calls the candidate service then the LLM, merges correctly, and persists,
- the merge step drops any candidateId the mocked LLM returns that wasn't in the candidate list it
  was given,
- a mocked-LLM-failure still returns a usable (fallback) response rather than throwing,
- the day-offset window filtering logic against today's date (test the boundary days explicitly —
  this is the part most likely to have an off-by-one),
- the no-releaseDate fallback (returns the full plan),
- allPhases=true bypasses the window filter.

After implementing, run the full test suite and confirm it passes before considering this done.
