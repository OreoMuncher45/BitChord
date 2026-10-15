package com.music.bitchord.data.settings

/**
 * Which transition planner Automix runs: the V2 policy pack (entrance
 * rescoring onto drum/bass onsets, vocal-gated outros) layered over the
 * advanced plan, or the legacy advanced plan alone. V2 only moves a plan
 * when it has the analysis to justify it, and every move is tagged in the
 * plan's policy reasons — switching back restores byte-identical legacy
 * plans.
 */
enum class AutomixEngine(val label: String) {
    V2("V2 · phrase-aware"),
    LEGACY("Legacy"),
}
