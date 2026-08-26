package com.callbackdev.tsteps.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.callbackdev.tsteps.MainActivity
import com.callbackdev.tsteps.R

/**
 * Builds the RemoteViews for one render pass (tweather's renderer, inherited
 * wholesale). [sizeMap] returns the API 31+ sizes-map RemoteViews: the launcher
 * picks the best-fitting tier by itself on every resize (and per orientation),
 * with no round-trip to the provider. Per-token colors travel as
 * [ForegroundColorSpan]s — a ParcelableSpan, safe across the RemoteViews IPC;
 * fonts stay in the XML (typeface spans don't parcel).
 */
object WidgetRenderer {

    private val LineIds = listOf(
        R.id.widget_line1, R.id.widget_line2, R.id.widget_line3,
        R.id.widget_line4, R.id.widget_line5, R.id.widget_line6,
        R.id.widget_line7, R.id.widget_line8, R.id.widget_line9,
        R.id.widget_line10, R.id.widget_line11
    )

    /** Slots the compact layout carries; taller transcripts use the large one. */
    private const val MediumSlots = 4

    // Measured, not estimated (tweather's rule): the renderer test binary-searches
    // the real laid-out minimum of every rung and fails if these drift. They carry
    // a deliberate margin over that measurement (~4dp chrome, ~2dp per line) for
    // the OEM's own monospace font — one line fewer is a far better failure than
    // one line sliced.
    private const val ChromeHeightDp = 64f
    private const val BodyLineHeightDp = 23f
    private const val SmallMinHeightDp = 52f

    fun sizeMap(
        context: Context,
        content: (WidgetTier) -> WidgetContent,
        palette: WidgetPalette,
        opacityPct: Int,
        syncing: Boolean = false
    ): RemoteViews = RemoteViews(
        breakpoints().entries.associate { (tier, size) ->
            size to render(context, content(tier), palette, opacityPct, tier, syncing)
        }
    )

    /**
     * A sizes-map key is a promise that the layout FITS in that many dp — the host
     * clips silently otherwise. Chrome (title bar, divider, prompt, bottom padding)
     * plus one line per slot. Below the smallest key the launcher falls back to it,
     * so a minimum-size widget still gets the glanceable strip.
     */
    internal fun minHeightDp(lines: Int): Float = ChromeHeightDp + lines * BodyLineHeightDp

    /**
     * One rung per transcript line, not a handful of named sizes — and none past
     * [WidgetContentBuilder.MAX_LINES]: a rung nothing can fill just promises
     * height it never uses.
     */
    internal fun breakpoints(): Map<WidgetTier, SizeF> = buildMap {
        put(WidgetTier.Small, SizeF(110f, SmallMinHeightDp))
        (MediumSlots..WidgetContentBuilder.MAX_LINES).forEach { lines ->
            put(WidgetTier.Terminal(lines), SizeF(160f, minHeightDp(lines)))
        }
    }

    internal fun render(
        context: Context,
        content: WidgetContent,
        palette: WidgetPalette,
        opacityPct: Int,
        tier: WidgetTier,
        syncing: Boolean = false
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layoutFor(tier))

        views.setInt(R.id.widget_bg_fill, "setColorFilter", palette.background)
        // setImageAlpha masks with 0xFF instead of clamping — never hand it an out-of-range value
        views.setInt(R.id.widget_bg_fill, "setImageAlpha", (opacityPct * 255 / 100).coerceIn(0, 255))
        views.setInt(R.id.widget_bg_border, "setColorFilter", palette.border)

        views.setTextViewText(R.id.widget_emoji, content.emoji ?: "")
        views.setViewVisibility(
            R.id.widget_emoji,
            if (content.emoji != null) View.VISIBLE else View.GONE
        )
        bindRefreshGlyph(context, views, palette, syncing)

        if (tier is WidgetTier.Small) {
            views.setTextViewText(R.id.widget_small_value, content.smallValue.spannable(palette))
            views.setTextViewText(R.id.widget_small_label, content.smallLabel.spannable(palette))
        } else {
            views.setTextViewText(R.id.widget_title, content.headerTitle)
            views.setTextColor(R.id.widget_title, palette.title)
            views.setInt(R.id.widget_divider, "setBackgroundColor", palette.divider)
            views.setInt(R.id.widget_guide, "setBackgroundColor", palette.divider)
            views.setTextViewText(R.id.widget_prompt, content.promptLine.spannable(palette))

            LineIds.take(slotsFor(tier)).forEachIndexed { index, id ->
                val line = content.bodyLines.getOrNull(index)
                if (line != null) {
                    views.setTextViewText(id, line.spannable(palette))
                    views.setViewVisibility(id, View.VISIBLE)
                } else {
                    views.setViewVisibility(id, View.GONE)
                }
            }
        }

        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshIntent(context))
        return views
    }

    /**
     * The tap's only acknowledgment, and the one that reaches every tier: the
     * `# last_sync` line is last in the transcript, so the sizes most people place
     * never show it. Bound on both branches — the glyph must come back.
     */
    private fun bindRefreshGlyph(
        context: Context,
        views: RemoteViews,
        palette: WidgetPalette,
        syncing: Boolean
    ) {
        views.setTextViewText(
            R.id.widget_refresh,
            context.getString(
                if (syncing) R.string.widget_refresh_glyph_busy else R.string.widget_refresh_glyph
            )
        )
        views.setTextColor(R.id.widget_refresh, if (syncing) palette.comment else palette.plain)
        views.setContentDescription(
            R.id.widget_refresh,
            context.getString(
                if (syncing) R.string.cd_widget_refresh_busy else R.string.cd_widget_refresh
            )
        )
    }

    /** Slots this tier binds — never more than its layout carries. */
    private fun slotsFor(tier: WidgetTier): Int =
        minOf(WidgetContentBuilder.bodyLineBudget(tier), LineIds.size)

    internal fun layoutFor(tier: WidgetTier): Int = when {
        tier is WidgetTier.Small -> R.layout.widget_tsteps_small
        // the compact layout stops at four slots; past that the large one has them all
        WidgetContentBuilder.bodyLineBudget(tier) <= MediumSlots ->
            R.layout.widget_tsteps_medium
        else -> R.layout.widget_tsteps_large
    }

    private fun TerminalLine.spannable(palette: WidgetPalette): CharSequence =
        SpannableStringBuilder().apply {
            tokens.forEach { token ->
                val start = length
                append(token.text)
                setSpan(
                    ForegroundColorSpan(palette.colorFor(token.role)),
                    start,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

    // Neither intent ever varies, and both are rebuilt for every tier of every
    // render: six sizes × two intents = twelve trips to the ActivityManager to
    // hand back the same two objects. Cached, because the ↻ acknowledgment is the
    // one paint whose whole value is how little it does.
    @Volatile
    private var cachedOpenApp: PendingIntent? = null

    @Volatile
    private var cachedRefresh: PendingIntent? = null

    private fun openAppIntent(context: Context): PendingIntent =
        cachedOpenApp ?: buildOpenAppIntent(context).also { cachedOpenApp = it }

    private fun refreshIntent(context: Context): PendingIntent =
        cachedRefresh ?: buildRefreshIntent(context).also { cachedRefresh = it }

    private fun buildOpenAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                // SINGLE_TOP is what makes CLEAR_TOP resume the running activity:
                // without it MainActivity is finished and rebuilt, replaying the
                // splash (tweather's lesson).
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** ↻ = read the counter now: one expedited sample, never a schedule change. */
    private fun buildRefreshIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, TstepsWidgetProvider::class.java)
                .setAction(TstepsWidgetProvider.ACTION_REFRESH)
                // The background broadcast queue is serialized and can sit seconds
                // behind whatever the system is dispatching; on the foreground one
                // this lands immediately. That queue is the single biggest gap
                // between the finger and the `…`. The price is the receiver's 10s
                // deadline instead of 60s — see the provider's broadcast budget.
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}
