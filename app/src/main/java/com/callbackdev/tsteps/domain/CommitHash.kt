package com.callbackdev.tsteps.domain

import java.security.MessageDigest
import java.time.LocalDate

/**
 * The fake-but-stable commit hash of a day. Deterministic from the date alone so
 * the same day shows the same hash on every device, forever — a real hash would
 * pretend there is content-addressed storage behind the metaphor; a stable one
 * just gives each day its git-shaped name.
 */
object CommitHash {

    fun of(date: LocalDate): String =
        MessageDigest.getInstance("SHA-1")
            .digest("tsteps:$date".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(7)
}
