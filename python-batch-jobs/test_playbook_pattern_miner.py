from playbook_pattern_miner import (
    EntityInput,
    MIN_COHORT_ENTITIES,
    assign_cohort_groups,
    build_contingency_counts,
    run_pipeline,
)


def make_entity(entity_id, industry, language, sequence, latest_cev):
    return EntityInput(
        entity_id=entity_id,
        industry=industry,
        language=language,
        sequence=tuple(sequence),
        latest_cumulative_ev=latest_cev,
    )


def test_injected_pattern_difference_survives_fdr_correction():
    # 6 "high" entities all carry TRAILER -> SPILLOVER_X in order; 6 "low" entities never do.
    # Everything else in each sequence is noise shared across both tiers, so the only thing that
    # should discriminate high vs low is the injected pattern.
    entities = {}
    eid = 1
    for i in range(6):
        entities[eid] = make_entity(
            eid, "Bollywood", "Hindi",
            ["TEASER", "TRAILER", "SPILLOVER_X", "PROMO_EVENT"],
            latest_cev=1000.0 + i,
        )
        eid += 1
    for i in range(6):
        entities[eid] = make_entity(
            eid, "Bollywood", "Hindi",
            ["TEASER", "PROMO_EVENT", "CAST_ANNOUNCEMENT"],
            latest_cev=10.0 + i,
        )
        eid += 1

    surviving, used_fallback = run_pipeline(entities)

    assert not used_fallback
    patterns = {r.pattern_sequence for r in surviving}
    assert ("TRAILER", "SPILLOVER_X") in patterns
    injected = next(r for r in surviving if r.pattern_sequence == ("TRAILER", "SPILLOVER_X"))
    # n=12 entities -> tertile size is 12 // 3 = 4 per tier (middle 4 excluded from mining).
    assert injected.support_top_tier == 4
    assert injected.support_bottom_tier == 0
    assert injected.fdr_q_value < 0.10


def test_cohort_fallback_triggers_under_six_entities():
    entities = {}
    # Cohort A: only 4 entities -- below MIN_COHORT_ENTITIES, must be folded into the pooled group.
    for i in range(4):
        entities[i] = make_entity(i, "Kollywood", "Tamil", ["TEASER"], latest_cev=100.0 + i)
    # Cohort B: 6 entities -- clears the bar, tiered on its own.
    for i in range(6):
        entities[100 + i] = make_entity(
            100 + i, "Sandalwood", "Kannada", ["TRAILER"], latest_cev=50.0 + i
        )

    groups, used_pooled_fallback = assign_cohort_groups(entities)

    assert used_pooled_fallback is True
    assert "Kollywood|Tamil" not in groups
    assert "Sandalwood|Kannada" in groups
    assert groups["Sandalwood|Kannada"] == sorted(100 + i for i in range(6))
    assert "ALL" in groups
    assert set(groups["ALL"]) == set(entities.keys())
    assert len(groups["ALL"]) >= MIN_COHORT_ENTITIES


def test_cohort_fallback_not_triggered_when_every_cohort_is_large_enough():
    entities = {
        i: make_entity(i, "Bollywood", "Hindi", ["TEASER"], latest_cev=float(i))
        for i in range(6)
    }
    groups, used_pooled_fallback = assign_cohort_groups(entities)

    assert used_pooled_fallback is False
    assert "ALL" not in groups
    assert "Bollywood|Hindi" in groups


def test_pooled_fallback_skipped_when_globally_still_under_minimum():
    # Two tiny cohorts, 2 entities each -- pooled total (4) is still under MIN_COHORT_ENTITIES (6).
    entities = {}
    for i in range(2):
        entities[i] = make_entity(i, "Bollywood", "Hindi", ["TEASER"], latest_cev=float(i))
    for i in range(2):
        entities[10 + i] = make_entity(10 + i, "Kollywood", "Tamil", ["TRAILER"], latest_cev=float(i))

    groups, used_pooled_fallback = assign_cohort_groups(entities)

    assert used_pooled_fallback is False
    assert groups == {}


def test_p_value_and_q_value_are_plain_python_floats_not_numpy_scalars():
    # psycopg2 can't adapt numpy.float64 (it fails to match the registered `float` adapter by
    # exact type even though float64 subclasses float) -- caught via a real run against Postgres
    # where fisher_exact's raw p_value slipped through uncast. type(x) is float, not isinstance,
    # is the check that actually distinguishes the two.
    entities = {}
    eid = 1
    for i in range(6):
        entities[eid] = make_entity(eid, "X", "Y", ["TRAILER", "SPILLOVER_X"], 1000.0 + i)
        eid += 1
    for i in range(6):
        entities[eid] = make_entity(eid, "X", "Y", ["TEASER"], 10.0 + i)
        eid += 1

    surviving, _ = run_pipeline(entities)

    assert surviving
    for r in surviving:
        assert type(r.p_value) is float
        assert type(r.fdr_q_value) is float


def test_contingency_table_counts_entity_absent_from_both_mined_sets_as_no_in_both_tiers():
    # entity 3 (high tier) and entity 6 (low tier) contain neither TRAILER nor SPILLOVER_X at all
    # -- they were never candidates for either mined pattern, but must still count as a "no" in
    # their own tier's column of the 2x2 table for any pattern being tested.
    entities = {
        1: make_entity(1, "X", "Y", ["TRAILER", "SPILLOVER_X"], 100.0),
        2: make_entity(2, "X", "Y", ["TRAILER", "SPILLOVER_X"], 90.0),
        3: make_entity(3, "X", "Y", ["PRESS_MEET"], 80.0),
        4: make_entity(4, "X", "Y", ["OTHER"], 10.0),
        5: make_entity(5, "X", "Y", ["OTHER"], 9.0),
        6: make_entity(6, "X", "Y", ["CAST_ANNOUNCEMENT"], 8.0),
    }
    high_ids, low_ids = [1, 2, 3], [4, 5, 6]

    appears_high, appears_low, not_high, not_low = build_contingency_counts(
        ("TRAILER", "SPILLOVER_X"), high_ids, low_ids, entities
    )

    assert appears_high == 2  # entities 1, 2
    assert not_high == 1  # entity 3 counted as a "no", not omitted
    assert appears_low == 0
    assert not_low == 3  # entities 4, 5, 6 all counted as "no"
