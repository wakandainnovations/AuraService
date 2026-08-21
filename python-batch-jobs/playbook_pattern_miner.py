"""F7 -- Cross-movie non-obvious playbook miner (sequential pattern mining).

Standalone batch job, not part of AuraService or AuraMath's Java build. Connects
directly to the shared `aura` Postgres DB, reads Checkpoint.checkpointType (F2),
entity_daily_vmi (F1), and entity_daily_behavior_features (F3), and mines ordered
symbol sequences that distinguish higher- from lower-viewership movies within the
same (industry, language) market.

Run with: AURA_DB_URL=postgresql://user:pass@host:port/aura python playbook_pattern_miner.py
See README.md in this directory for the full connection/scheduling contract shared
with F4 (causal precedence engine) and F5 (non-obvious lever miner) once those land.
"""

from __future__ import annotations

import os
from collections import defaultdict
from dataclasses import dataclass, field
from statistics import mean
from typing import Iterable

import psycopg2
import psycopg2.extras
from prefixspan import PrefixSpan
from scipy.stats import fisher_exact
from statsmodels.stats.multitest import multipletests

# SentimentAlertService.SPIKE_MULTIPLIER (AuraService, java/.../SentimentAlertService.java:36).
# Reused verbatim per this doc's own convention (F3/F7) rather than inventing a second threshold.
SPIKE_MULTIPLIER = 1.5

# net_sentiment_delta spike detection needs *some* trailing history to average over. A day's
# trailing window can have at most 7 prior day_index rows; requiring at least 4 of them present
# avoids calling a 1-2 point average a "trailing 7-day average" while still tolerating the sparse
# day-to-day coverage real tracked entities have (mirrors F1's own "skip below N cohort-mates"
# discipline rather than requiring a full, gapless 7 days).
MIN_SPIKE_BASELINE_DAYS = 4
TRAILING_WINDOW_DAYS = 7

# PrefixSpan mining bounds, per the F7 prompt.
PATTERN_MIN_LEN = 2
PATTERN_MAX_LEN = 4

# "Appears in at least 2 sequences within its tier." Tier sizes here are n // 3 of a cohort (or
# pooled-fallback) group that itself has >= MIN_COHORT_ENTITIES entities, so a tier realistically
# holds single digits to low tens of sequences -- support=2 is the loosest threshold that still
# means "this pattern repeated across different movies," not "this happened to one movie twice."
MIN_PATTERN_SUPPORT = 2

# Cohort-size fallback and FDR bar, both explicit in the F7 prompt.
MIN_COHORT_ENTITIES = 6
FDR_ALPHA = 0.10


# --------------------------------------------------------------------------
# Data model
# --------------------------------------------------------------------------


@dataclass
class EntityInput:
    entity_id: int
    industry: str | None
    language: str | None
    sequence: tuple[str, ...] = field(default_factory=tuple)
    latest_cumulative_ev: float | None = None


@dataclass
class PatternResult:
    cohort: str
    pattern_sequence: tuple[str, ...]
    support_top_tier: int
    support_bottom_tier: int
    n_entities: int
    p_value: float
    fdr_q_value: float | None = None


# --------------------------------------------------------------------------
# Sequence construction (step 1)
# --------------------------------------------------------------------------


def detect_sentiment_spike_days(day_deltas: Iterable[tuple[int, float]]) -> set[int]:
    """day_deltas: (day_index, net_sentiment_delta) pairs for one entity.

    Flags day_index where net_sentiment_delta exceeds SPIKE_MULTIPLIER times the mean of the
    available net_sentiment_delta values over the preceding TRAILING_WINDOW_DAYS day_indexes --
    the same "current > baseline * 1.5" shape as SentimentAlertService, applied to this entity's
    own delta series since the raw trailing sentiment ratio itself isn't persisted, only its
    day-over-day delta (entity_daily_behavior_features.net_sentiment_delta).
    A baseline <= 0 is skipped, mirroring SentimentAlertService's baselineRatio<=0 guard -- a
    positive multiplier threshold isn't meaningful against a non-positive baseline.
    """
    by_day = dict(day_deltas)
    spikes: set[int] = set()
    for day_index in sorted(by_day):
        window = [
            by_day[d]
            for d in range(day_index - TRAILING_WINDOW_DAYS, day_index)
            if d in by_day
        ]
        if len(window) < MIN_SPIKE_BASELINE_DAYS:
            continue
        baseline = mean(window)
        if baseline > 0 and by_day[day_index] > baseline * SPIKE_MULTIPLIER:
            spikes.add(day_index)
    return spikes


def build_entity_sequence(
    checkpoints: list[tuple[object, str | None]],
    day_index_to_date: dict[int, object],
    spillover_rows: list[tuple[int, str]],
    sentiment_spike_days: set[int],
) -> tuple[str, ...]:
    """Merge checkpoint/spillover/sentiment-spike symbols into one date-ordered sequence.

    checkpoints: (checkpoint_date, checkpoint_type) pairs.
    spillover_rows: (day_index, platform) pairs for non-null spillover_event rows.
    Every day_index-based symbol is resolved to a calendar date via day_index_to_date (from
    entity_daily_vmi) so it can be sorted against checkpoint_date on one timeline. A secondary
    sort key (source order, then symbol) makes same-day ordering deterministic across re-runs.
    """
    events: list[tuple[object, int, str]] = []

    for checkpoint_date, checkpoint_type in checkpoints:
        events.append((checkpoint_date, 0, checkpoint_type or "OTHER"))

    for day_index, platform in spillover_rows:
        calendar_date = day_index_to_date.get(day_index)
        if calendar_date is None:
            continue
        events.append((calendar_date, 1, f"SPILLOVER_{platform}"))

    for day_index in sentiment_spike_days:
        calendar_date = day_index_to_date.get(day_index)
        if calendar_date is None:
            continue
        events.append((calendar_date, 2, "SENTIMENT_SPIKE"))

    events.sort(key=lambda e: (e[0], e[1], e[2]))
    return tuple(e[2] for e in events)


# --------------------------------------------------------------------------
# Outcome tiers and cohort grouping (step 2)
# --------------------------------------------------------------------------


def assign_cohort_groups(
    entities: dict[int, EntityInput],
) -> tuple[dict[str, list[int]], bool]:
    """Group entity ids eligible for tiering (those with a known latest_cumulative_ev) by
    (industry, language). Cohorts under MIN_COHORT_ENTITIES are folded into one pooled 'ALL'
    group spanning every eligible entity, computed once for the whole run (not once per small
    cohort) and only if that pooled group itself clears MIN_COHORT_ENTITIES.

    Returns (groups, used_pooled_fallback).
    """
    eligible = {eid: e for eid, e in entities.items() if e.latest_cumulative_ev is not None}

    by_cohort: dict[tuple[str | None, str | None], list[int]] = defaultdict(list)
    for eid, e in eligible.items():
        by_cohort[(e.industry, e.language)].append(eid)

    groups: dict[str, list[int]] = {}
    fallback_needed = False
    for (industry, language), eids in by_cohort.items():
        if len(eids) >= MIN_COHORT_ENTITIES:
            groups[f"{industry}|{language}"] = sorted(eids)
        else:
            fallback_needed = True

    used_pooled_fallback = False
    if fallback_needed:
        all_eids = sorted(eligible)
        if len(all_eids) >= MIN_COHORT_ENTITIES:
            groups["ALL"] = all_eids
            used_pooled_fallback = True
        # else: pooled fallback itself doesn't clear the bar -- those small cohorts' entities
        # simply aren't mined this run. Caller prints the corresponding warning.

    return groups, used_pooled_fallback


def assign_tiers(entity_ids: list[int], entities: dict[int, EntityInput]) -> tuple[list[int], list[int]]:
    """Rank by latest_cumulative_ev descending; top tertile = high, bottom tertile = low,
    middle third excluded. Ties broken by entity_id for determinism."""
    ranked = sorted(
        entity_ids,
        key=lambda eid: (-entities[eid].latest_cumulative_ev, eid),
    )
    n = len(ranked)
    k = n // 3
    high = ranked[:k]
    low = ranked[n - k:] if k > 0 else []
    return high, low


# --------------------------------------------------------------------------
# Pattern mining (step 3) and contingency testing (step 4)
# --------------------------------------------------------------------------


def mine_patterns(sequences: list[tuple[str, ...]]) -> set[tuple[str, ...]]:
    non_empty = [list(seq) for seq in sequences if seq]
    if len(non_empty) < MIN_PATTERN_SUPPORT:
        return set()
    ps = PrefixSpan(non_empty)
    ps.minlen = PATTERN_MIN_LEN
    ps.maxlen = PATTERN_MAX_LEN
    mined = ps.frequent(MIN_PATTERN_SUPPORT)
    return {tuple(pattern) for _support, pattern in mined}


def is_subsequence(pattern: tuple[str, ...], sequence: tuple[str, ...]) -> bool:
    """True if pattern occurs as an order-preserving (not necessarily contiguous) subsequence
    of sequence -- the same containment definition PrefixSpan mines under."""
    pos = 0
    for symbol in sequence:
        if pos < len(pattern) and symbol == pattern[pos]:
            pos += 1
            if pos == len(pattern):
                return True
    return pos == len(pattern)


def build_contingency_counts(
    pattern: tuple[str, ...],
    high_ids: list[int],
    low_ids: list[int],
    entities: dict[int, EntityInput],
) -> tuple[int, int, int, int]:
    """2x2 counts: (appears_high, appears_low, not_appears_high, not_appears_low), evaluated
    across every entity in scope (high_ids + low_ids) -- an entity absent from both mined sets
    still gets counted here as a "no" for whichever tier it belongs to."""
    appears_high = sum(1 for eid in high_ids if is_subsequence(pattern, entities[eid].sequence))
    appears_low = sum(1 for eid in low_ids if is_subsequence(pattern, entities[eid].sequence))
    return appears_high, appears_low, len(high_ids) - appears_high, len(low_ids) - appears_low


def mine_and_test_cohort(
    cohort_label: str,
    entity_ids: list[int],
    entities: dict[int, EntityInput],
) -> list[PatternResult]:
    """Tier `entity_ids`, mine each tier's sequences, and Fisher's-exact-test every candidate
    pattern's 2x2 presence/tier table. fdr_q_value is left unset -- FDR correction happens once,
    globally, across every cohort's results in the run (see run_pipeline)."""
    high_ids, low_ids = assign_tiers(entity_ids, entities)
    if not high_ids or not low_ids:
        return []

    high_sequences = [entities[eid].sequence for eid in high_ids]
    low_sequences = [entities[eid].sequence for eid in low_ids]
    candidate_patterns = mine_patterns(high_sequences) | mine_patterns(low_sequences)

    n_entities = len(high_ids) + len(low_ids)
    results = []
    for pattern in sorted(candidate_patterns):
        appears_high, appears_low, not_high, not_low = build_contingency_counts(
            pattern, high_ids, low_ids, entities
        )
        table = [[appears_high, appears_low], [not_high, not_low]]
        _odds_ratio, p_value = fisher_exact(table)
        p_value = float(p_value)  # scipy returns numpy.float64, which psycopg2 can't adapt
        results.append(
            PatternResult(
                cohort=cohort_label,
                pattern_sequence=pattern,
                support_top_tier=appears_high,
                support_bottom_tier=appears_low,
                n_entities=n_entities,
                p_value=p_value,
            )
        )
    return results


# --------------------------------------------------------------------------
# Full in-memory pipeline (steps 2-5): DB-free so it's directly unit-testable.
# --------------------------------------------------------------------------


def run_pipeline(entities: dict[int, EntityInput]) -> tuple[list[PatternResult], bool]:
    groups, used_pooled_fallback = assign_cohort_groups(entities)

    all_results: list[PatternResult] = []
    for cohort_label, entity_ids in groups.items():
        all_results.extend(mine_and_test_cohort(cohort_label, entity_ids, entities))

    if not all_results:
        return [], used_pooled_fallback

    p_values = [r.p_value for r in all_results]
    _reject, q_values, _, _ = multipletests(p_values, alpha=FDR_ALPHA, method="fdr_bh")
    for result, q in zip(all_results, q_values):
        result.fdr_q_value = float(q)

    surviving = [r for r in all_results if r.fdr_q_value < FDR_ALPHA]
    return surviving, used_pooled_fallback


# --------------------------------------------------------------------------
# DB I/O
# --------------------------------------------------------------------------


def get_connection():
    dsn = os.environ.get("AURA_DB_URL")
    if not dsn:
        raise RuntimeError(
            "AURA_DB_URL is not set. See python-batch-jobs/README.md for the expected DSN format."
        )
    return psycopg2.connect(dsn)


def ensure_schema(conn) -> None:
    with conn.cursor() as cur:
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS playbook_patterns (
                id SERIAL PRIMARY KEY,
                pattern_sequence JSONB NOT NULL,
                cohort TEXT NOT NULL,
                support_top_tier INTEGER NOT NULL,
                support_bottom_tier INTEGER NOT NULL,
                p_value DOUBLE PRECISION NOT NULL,
                fdr_q_value DOUBLE PRECISION NOT NULL,
                n_entities INTEGER NOT NULL,
                computed_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """
        )
    conn.commit()


def load_entities(conn) -> dict[int, EntityInput]:
    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute("SELECT id, industry, language FROM managed_entities WHERE type = 'MOVIE'")
        rows = cur.fetchall()

    entities = {
        row["id"]: EntityInput(entity_id=row["id"], industry=row["industry"], language=row["language"])
        for row in rows
    }
    if not entities:
        return entities

    entity_ids = list(entities)
    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute(
            """
            SELECT managed_entity_id, checkpoint_date, checkpoint_type
            FROM checkpoints
            WHERE managed_entity_id = ANY(%s)
            ORDER BY managed_entity_id, checkpoint_date
            """,
            (entity_ids,),
        )
        checkpoint_rows = cur.fetchall()

        cur.execute(
            """
            SELECT entity_id, day_index, calendar_date, cumulative_engagement_volume
            FROM entity_daily_vmi
            WHERE entity_id = ANY(%s)
            ORDER BY entity_id, day_index
            """,
            (entity_ids,),
        )
        vmi_rows = cur.fetchall()

        cur.execute(
            """
            SELECT entity_id, day_index, net_sentiment_delta, spillover_event
            FROM entity_daily_behavior_features
            WHERE entity_id = ANY(%s)
            ORDER BY entity_id, day_index
            """,
            (entity_ids,),
        )
        behavior_rows = cur.fetchall()

    checkpoints_by_entity: dict[int, list[tuple[object, str | None]]] = defaultdict(list)
    for row in checkpoint_rows:
        checkpoints_by_entity[row["managed_entity_id"]].append(
            (row["checkpoint_date"], row["checkpoint_type"])
        )

    day_index_to_date_by_entity: dict[int, dict[int, object]] = defaultdict(dict)
    for row in vmi_rows:
        day_index_to_date_by_entity[row["entity_id"]][row["day_index"]] = row["calendar_date"]

    latest_cev_by_entity: dict[int, float] = {}
    for row in vmi_rows:
        # rows are ordered by day_index ascending per entity, so the last one seen wins
        latest_cev_by_entity[row["entity_id"]] = row["cumulative_engagement_volume"]

    spillover_by_entity: dict[int, list[tuple[int, str]]] = defaultdict(list)
    delta_by_entity: dict[int, list[tuple[int, float]]] = defaultdict(list)
    for row in behavior_rows:
        eid = row["entity_id"]
        if row["spillover_event"]:
            spillover_by_entity[eid].append((row["day_index"], row["spillover_event"]))
        if row["net_sentiment_delta"] is not None:
            delta_by_entity[eid].append((row["day_index"], row["net_sentiment_delta"]))

    for eid, entity in entities.items():
        entity.latest_cumulative_ev = latest_cev_by_entity.get(eid)
        spike_days = detect_sentiment_spike_days(delta_by_entity.get(eid, []))
        entity.sequence = build_entity_sequence(
            checkpoints_by_entity.get(eid, []),
            day_index_to_date_by_entity.get(eid, {}),
            spillover_by_entity.get(eid, []),
            spike_days,
        )

    return entities


def persist_results(conn, results: list[PatternResult], processed_cohorts: Iterable[str]) -> None:
    with conn.cursor() as cur:
        for cohort in processed_cohorts:
            cur.execute("DELETE FROM playbook_patterns WHERE cohort = %s", (cohort,))
        for r in results:
            cur.execute(
                """
                INSERT INTO playbook_patterns
                    (pattern_sequence, cohort, support_top_tier, support_bottom_tier,
                     p_value, fdr_q_value, n_entities, computed_at)
                VALUES (%s, %s, %s, %s, %s, %s, %s, now())
                """,
                (
                    psycopg2.extras.Json(list(r.pattern_sequence)),
                    r.cohort,
                    r.support_top_tier,
                    r.support_bottom_tier,
                    r.p_value,
                    r.fdr_q_value,
                    r.n_entities,
                ),
            )
    conn.commit()


def main() -> None:
    conn = get_connection()
    try:
        ensure_schema(conn)
        entities = load_entities(conn)
        print(f"Loaded {len(entities)} MOVIE entities.")

        groups, used_pooled_fallback = assign_cohort_groups(entities)
        if used_pooled_fallback:
            print(
                "NOTE: one or more (industry, language) cohorts had fewer than "
                f"{MIN_COHORT_ENTITIES} entities; this run used the pooled 'ALL' fallback "
                "(all globally eligible entities) in place of per-cohort tiering for them."
            )
        if not groups:
            print(
                f"No cohort (or the pooled fallback) reached the {MIN_COHORT_ENTITIES}-entity "
                "minimum. Nothing to mine this run."
            )
            return

        results, _ = run_pipeline(entities)
        print(f"{len(results)} pattern(s) survived Fisher's exact test + FDR correction (q < {FDR_ALPHA}).")

        persist_results(conn, results, processed_cohorts=groups.keys())
        print(f"Replaced playbook_patterns rows for cohorts: {sorted(groups)}.")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
