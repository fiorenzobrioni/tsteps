package com.callbackdev.tsteps.domain

import java.time.LocalDate

/**
 * Personal records as `tag:` refs — informational, computed on read from the
 * committed days (never stored, so there is no record state to corrupt). Fase 5
 * ships `best-day`; the walk-based tags (longest-walk) arrive with sessions.
 */
object Records {

    /** The committed day with the most steps; ties go to the most recent day. */
    fun bestDay(days: List<Pair<LocalDate, Long>>): LocalDate? =
        days.maxWithOrNull(compareBy({ it.second }, { it.first }))?.first
}
