package com.callbackdev.tsteps.ui.format

/**
 * Pipe tables formatted the way a developer — or the markdown formatter in their
 * editor — writes them: every column padded to its widest cell so the file reads as
 * a rectangle instead of a ragged pile of pipes. `README.md` is rendered as SOURCE
 * (the GitHub "Code" view), and in a source view an unaligned table is
 * simply badly formatted. Numeric columns get the real markdown right-alignment
 * marker (`---:`), so the padding and the syntax agree on what the column is.
 *
 * Emoji are the whole difficulty. None of the twelve weather emoji exist in
 * JetBrains Mono, so every one of them is drawn from the system emoji font
 * (~1.25em advance against the 0.6em of JetBrains Mono, i.e. about two character
 * cells). Two rules follow, and together they are what makes the columns line up:
 *
 * - a cell is measured on its TEXT only, never on the string that carries the emoji
 *   (`"🌧️".length` is 3 UTF-16 units for a single glyph, and that glyph is not one
 *   character wide anyway);
 * - an emoji always sits at the RIGHT edge of its cell, exactly one per cell, so
 *   whatever its real width turns out to be on a given device it is the same
 *   constant on every row and the following pipe still falls in column.
 *
 * [EmojiCells] therefore only decides how much padding the emoji-free header and
 * separator rows get; 2 is what markdown formatters and terminals assume for the
 * same glyphs, and it lands within a pixel of the real advance ratio at 13sp.
 *
 * The alignment lives in the source view alone — a markdown renderer would collapse
 * the padding — which is exactly right for an app that has no preview: it is the
 * same craft as lining up the `=` in a config file.
 */

/** Two character cells per emoji glyph — see the file header. */
private const val EmojiCells = 2

/** Markdown's own minimum separator (`---`), so a narrow column stays legal. */
private const val MinSeparator = 3

internal enum class TableAlign { LEFT, RIGHT }

internal data class TableColumn(val header: String, val align: TableAlign = TableAlign.LEFT)

/** A cell: [text] against the left edge, optional [emoji] against the right one. */
internal data class TableCell(val text: String, val emoji: String? = null) {
    companion object {
        /** Icon-only cell (the hourly table's sky column). */
        fun icon(emoji: String) = TableCell("", emoji)
    }
}

/** Header, separator and one line per row, all padded to the same column widths. */
internal fun markdownTable(
    columns: List<TableColumn>,
    rows: List<List<TableCell>>
): List<String> {
    val widths = columns.mapIndexed { i, column ->
        (rows.map { it[i].cells() } + column.header.length + MinSeparator).max()
    }
    return buildList {
        add(columns.mapIndexed { i, c -> pad(TableCell(c.header), widths[i], c.align) }.toRow())
        add(columns.mapIndexed { i, c -> c.align.separator(widths[i]) }.toRow())
        rows.forEach { row ->
            add(row.mapIndexed { i, cell -> pad(cell, widths[i], columns[i].align) }.toRow())
        }
    }
}

private fun List<String>.toRow(): String = joinToString(" | ", "| ", " |")

/** Width in character cells: text length plus the emoji and the gap it needs. */
private fun TableCell.cells(): Int = when {
    emoji == null -> text.length
    text.isEmpty() -> EmojiCells
    else -> text.length + 1 + EmojiCells
}

private fun pad(cell: TableCell, width: Int, align: TableAlign): String = when {
    cell.emoji == null ->
        if (align == TableAlign.RIGHT) cell.text.padStart(width) else cell.text.padEnd(width)
    // Text left, emoji right: the padding goes between them, never after the emoji,
    // so every row in the column puts its glyph at the same offset.
    else -> {
        val gap = (width - cell.text.length - EmojiCells).coerceAtLeast(0)
        cell.text + " ".repeat(gap) + cell.emoji
    }
}

private fun TableAlign.separator(width: Int): String = when (this) {
    TableAlign.LEFT -> "-".repeat(width)
    TableAlign.RIGHT -> "-".repeat(width - 1) + ":"
}
