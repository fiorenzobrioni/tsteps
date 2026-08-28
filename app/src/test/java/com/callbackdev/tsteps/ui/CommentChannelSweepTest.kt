package com.callbackdev.tsteps.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard that reads the **sources**, not the resources.
 *
 * `RegisterRuleTest` checks that every note the app declares behaves — but it can
 * only see notes that exist. It cannot see a sentence somebody left as a literal,
 * which is exactly what went wrong in tweather's Fase 18: a sweep by hand kept
 * anchoring `//` to the start of the string, so an `append(",  // …")`
 * continuation never showed up in it, and the committente found one of them by
 * eye, on a screenshot. tsteps was swept with this guard from the start.
 *
 * So the sweep is a test now. It walks the Kotlin sources, finds every string
 * literal that carries a comment marker, and fails on any that reads like a
 * **sentence** — three plain lowercase words in a row, which is what separates
 * `// tap again to confirm` from `// active`, `// gps`, `// CC BY 4.0` and
 * `// 15 | 30 | 60 | 120`.
 *
 * The allowlist below is the written record of what stays English and why. Adding
 * to it should feel like a decision, because it is one.
 */
class CommentChannelSweepTest {

    /**
     * Sentences that are deliberately literals. Each one needs a reason, and
     * "it is a comment" is not one — that was the belief Fase 20 corrected.
     */
    private val allowed: Map<String, String> = mapOf(
        "// tsteps editor canvas" to
            "A @Preview sample inside CodeCanvas: nobody reads it but a developer."
    )

    private val sourceRoot: File
        get() = listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("cannot find the Kotlin sources: this guard must not pass by default")

    @Test
    fun `every sentence in the comment channel is a resource, not a literal`() {
        val offenders = mutableListOf<String>()
        sourceRoot.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                literalsIn(line)
                    .filter { it.carriesAComment() && it.readsLikeASentence() }
                    .filterNot { allowed.containsKey(it.trim()) }
                    .forEach { offenders += "${file.name}:${index + 1}  $it" }
            }
        }
        assertEquals(
            "these read like sentences and are still literals:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders
        )
    }

    /** The allowlist must not rot into a list of things nobody looked at. */
    @Test
    fun `every allowlisted sentence still exists`() {
        val all = sourceRoot.walkTopDown().filter { it.extension == "kt" }
            .flatMap { it.readLines().asSequence().flatMap { line -> literalsIn(line).asSequence() } }
            .map { it.trim() }
            .toSet()
        allowed.keys.forEach { assertTrue("'$it' is allowlisted but no longer in the sources", it in all) }
    }

    // ---- the sweep itself --------------------------------------------------

    /** Double-quoted literals on one line. Kotlin has no multi-line plain strings. */
    private fun literalsIn(line: String): List<String> =
        Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(line).map { it.groupValues[1] }.toList()

    private fun String.carriesAComment(): Boolean =
        (contains("//") || contains("# ")) && !startsWith("http") && !contains("://")

    /**
     * Three plain lowercase words in a row, after the marker.
     *
     * A token is anything with a dot, an underscore, a digit, a colon or a capital
     * in it — a file name, a key, a licence, a value list — and a token breaks the
     * run. That is the whole difference between a sentence and a label.
     */
    private fun String.readsLikeASentence(): Boolean {
        val after = when {
            contains("//") -> substringAfter("//")
            else -> substringAfter("# ")
        }
        var run = 0
        after.split(" ", "\t").forEach { token ->
            run = if (token.matches(Regex("[a-z]{2,}"))) run + 1 else 0
            if (run >= 3) return true
        }
        return false
    }
}
