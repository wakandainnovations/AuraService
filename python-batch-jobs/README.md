# Standalone Python batch jobs

These are **not** part of either Java repo's build. They're scheduled offline analyses that
connect directly to the shared `aura` Postgres DB, write their findings into their own tables,
and rely on the "new table, instantly readable by every other service" convention already used
elsewhere in this workspace -- no HTTP call or Java service required to consume their output.

See `docs/AUDIENCE_BEHAVIOR_PATTERN_FEATURE_BREAKDOWN.md` at the repo root for the full design
rationale (F4, F5, F7). Only **F7** (`playbook_pattern_miner.py`) is built so far.

## Setup

```bash
cd python-batch-jobs
python3 -m venv venv && source venv/bin/activate
pip install -r requirements.txt
```

## Running

Every job reads its DB connection from `AURA_DB_URL` (a standard libpq connection string) --
never hardcode credentials into the script:

```bash
export AURA_DB_URL="postgresql://user:password@host:5432/aura"
python playbook_pattern_miner.py
```

## F7 -- Cross-movie non-obvious playbook miner (`playbook_pattern_miner.py`)

Mines ordered symbol sequences (`CheckpointType` events + `SPILLOVER_<platform>` +
`SENTIMENT_SPIKE`) per tracked movie, tiers movies into "high"/"low" viewership groups within
their `(industry, language)` cohort (latest `cumulative_engagement_volume` from
`entity_daily_vmi`), mines frequent subsequences per tier with PrefixSpan, and Fisher's-exact
+ BH-FDR tests every candidate pattern's association with tier. Surviving rows (`q < 0.10`) are
written to `playbook_patterns` (created on first run via `CREATE TABLE IF NOT EXISTS`).

**Depends on:** `Checkpoint.checkpointType` (F2), `entity_daily_vmi` (F1),
`entity_daily_behavior_features` (F3) already existing and populated in the shared DB.

**Idempotency:** each run deletes and re-inserts `playbook_patterns` rows only for the cohorts
(or the pooled `'ALL'` fallback) it actually processed this run -- re-running never appends
duplicates, and a cohort whose patterns stop clearing the FDR bar ends up with zero rows rather
than stale ones.

**Notable thresholds** (documented in code, not just here, since these are judgment calls given
current data volume -- revisit as `managed_entities` grows):
- Minimum 6 entities per `(industry, language)` cohort to tier it on its own; smaller cohorts are
  folded into one pooled `'ALL'` group for the whole run (once, not once per small cohort).
- Minimum pattern support of 2 sequences within a tier before PrefixSpan considers it frequent.
- `SENTIMENT_SPIKE` reuses `SentimentAlertService.SPIKE_MULTIPLIER = 1.5` (AuraService), applied
  to each entity's own `net_sentiment_delta` day-over-day series (the raw trailing ratio itself
  isn't persisted, only its delta) against the trailing 7-day average of that same series, and
  requires at least 4 of the possible 7 prior days present before testing a given day.
- FDR bar is `q < 0.10` (looser than the conventional 0.05), matching every other statistical
  feature in this doc's family, explicit because comps sample sizes here are small.

**Schedule:** run after F1/F3 have refreshed for the day; weekly is reasonable given how slowly
`entity_daily_vmi` accumulates new days per entity (same cadence recommended for F4/F5).

**Tests:** `python -m pytest` from this directory. Covers: an injected pattern difference between
tiers is detected by PrefixSpan and survives FDR correction; the `< 6`-entities cohort fallback
folds the small cohort into the pooled `'ALL'` group (and is skipped entirely if even the pooled
group doesn't clear the minimum); the 2x2 contingency table counts an entity absent from both
mined tiers' pattern sets as a "no" in its own tier's column, not as a missing/omitted row.
