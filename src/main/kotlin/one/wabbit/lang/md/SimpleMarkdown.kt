package one.wabbit.lang.md

import kotlinx.serialization.Serializable
import one.wabbit.parsing.CharInput
import one.wabbit.parsing.CharInput.Companion.EOB

private typealias KList<T> = kotlin.collections.List<T>

@Serializable sealed interface Markdown {
    @Serializable data object Empty : Markdown

    @Serializable data class Combine(val parts: KList<Markdown>) : Markdown {
        init {
            require(parts.size > 1) { "Combine must have at least 2 parts" }
            require(parts.none { it is Empty }) { "Combine cannot contain Empty" }
            require(parts.none { it is Combine }) { "Combine cannot contain Combine" }
        }
        constructor(vararg parts: Markdown) : this(parts.toList())
    }

    @Serializable data class Section(val level: Int, val title: String, val content: Markdown) : Markdown {
        init {
            require(level > 0) { "Section level must be > 0" }
            require(title.isNotBlank()) { "Section title must not be blank" }
        }
    }

    @Serializable data class Paragraph(val text: String) : Markdown {
        init {
            require(text.isNotBlank()) { "Paragraph text must not be blank" }
        }
    }

    @Serializable data class List(val items: KList<Markdown>) : Markdown {
        init {
            require(items.isNotEmpty()) { "List must have at least one item" }
        }
        constructor(vararg items: Markdown) : this(items.toList())
    }

    fun toText(depth: Int = 0, level: Int = 0): String {
        val indent = "  ".repeat(depth)
        return when (this) {
            is Empty -> ""
            is Section -> {
                // For sections, the header itself is not indented,
                // but the contents are indented one level further.
                val header = "#".repeat(level + this.level) + " " + title
                val content = content.toText(depth, level = level + this.level)
                // If there are contents, separate the header and the contents with a newline.
                if (content.isNotBlank()) indent + header + "\n" + content
                else indent + header
            }
            is Paragraph -> indent + text
            is List ->
                // Each list item is prefixed with a bullet ("- ") and indented according to its level.
                items.joinToString("\n") {
                    // We call toText(depth + 1) on each item so that nested list items are indented further.
                    indent + "- " + it.toText(depth + 1, level = 0).trimStart()
                }
            is Combine ->
                parts.joinToString("\n") { it.toText(depth, level) }
        }
    }

    interface Parser<T> {
        fun parse(input: KList<Markdown>): T?
    }

    companion object {
        fun p(text: String): Markdown = if (text.isBlank()) Empty else Paragraph(text)

        fun opt(item: Markdown?): Markdown = item ?: Empty

        fun section(level: Int, title: String, contents: KList<Markdown>): Markdown =
            Section(level, title, combine(contents))
        fun section(level: Int, title: String, vararg contents: Markdown): Markdown =
            section(level, title, contents.toList())

        fun combine(parts: KList<Markdown>): Markdown {
            // println("combine($parts)")
            if (parts.isEmpty()) return Empty
            val list = parts.flatMap { part ->
                when (part) {
                    is Empty -> emptyList()
                    is Combine -> part.parts
                    else -> listOf(part)
                }
            }
            if (list.size == 0) return Empty
            if (list.size == 1) return list[0]
            check(list.none { it is Empty }) { "Combine cannot contain Empty" }
            // println("Returning Combine($list)")
            return Combine(list)
        }
        fun combine(vararg parts: Markdown): Markdown = combine(parts.toList())
        @JvmName("combineOpt") fun combine(vararg parts: Markdown?): Markdown = combine(parts.filterNotNull())
        @JvmName("combineOpt") fun combine(parts: KList<Markdown?>): Markdown = combine(parts.filterNotNull())

        fun list(vararg items: Markdown): Markdown = List(items.toList())
        fun list(items: KList<Markdown>): Markdown = List(items)
        @JvmName("listOpt") fun list(vararg items: Markdown?): Markdown = List(items.filterNotNull())
        @JvmName("listOpt") fun list(items: KList<Markdown?>): Markdown = List(items.filterNotNull())
    }
}

///////////////////////////////////////////////////////////////////////////
//   Helper functions
///////////////////////////////////////////////////////////////////////////

private fun <Span> peekRawLevel(input: CharInput<Span>): Int {
    val mark = input.mark()
    var count = 0
    while (input.current == '#') {
        count++
        input.advance()
    }
    input.reset(mark)
    return count
}

/** Skips spaces/tabs but stops at newlines or EOB. */
private fun <Span> skipSpaces(input: CharInput<Span>) {
    while (true) {
        val c = input.current
        if (c == EOB || c != ' ' && c != '\t') break
        input.advance()
    }
}

/** Skips all blank lines (any line that is entirely whitespace) */
private fun <Span> skipBlankLinesAndSpaces(input: CharInput<Span>) {
    while (true) {
        skipSpaces(input)
        if (input.current == '\n' || input.current == '\r') {
            // move past the newline(s)
            if (input.current == '\r') input.advance()
            if (input.current == '\n') input.advance()
        } else {
            break
        }
    }
}

/** Returns how many spaces/tabs are at the current line before any text or bullet. */
private fun <Span> parseIndentLevel(input: CharInput<Span>): Int {
    var count = 0
    while (true) {
        val c = input.current
        if (c == ' ' || c == '\t') {
            count++
            input.advance()
        } else {
            break
        }
    }
    return count
}

/* -------------------------------------------------------------------------
   Top-level parse entry
   ------------------------------------------------------------------------- */
fun <Span> parseMarkdown(input: CharInput<Span>): Markdown {
    skipBlankLinesAndSpaces(input)
    // Top-level has no parent, so use parentRaw = 0.
    val blocks = parseBlocks(input, parentRaw = 0)
    return when (blocks.size) {
        0 -> Markdown.Empty
        1 -> blocks[0]
        else -> Markdown.combine(blocks)
    }
}

/*
   parseBlocks: parses a sequence of blocks until EOB or until a heading is encountered
   that should terminate the current section.

   A heading terminates the current section if we’re inside a section (parentRaw != 0)
   and the new heading’s raw level is ≤ the parent’s raw level.
*/
private fun <Span> parseBlocks(input: CharInput<Span>, parentRaw: Int): MutableList<Markdown> {
    val blocks = mutableListOf<Markdown>()

    while (true) {
        skipBlankLinesAndSpaces(input)
        if (input.current == EOB) break

        val mark = input.mark()
        if (input.current == '#') {
            val raw = peekRawLevel(input)
            if (parentRaw != 0 && raw <= parentRaw) {
                // This heading is not nested—terminate the current block collection.
                input.reset(mark)
                break
            }
            val sec = parseSection(input, parentRaw)
            blocks.add(sec)
            continue
        }
        input.reset(mark)
        val block = parseBlock(input)
        blocks.add(block)
    }
    return blocks
}

/* -------------------------------------------------------------------------
   parseBlock decides if it’s a list or paragraph
   ------------------------------------------------------------------------- */
private fun <Span> parseBlock(input: CharInput<Span>): Markdown {
    val mark = input.mark()
    val indentLevel = parseIndentLevel(input)
    val c = input.current
    val cLower = c.lowercaseChar()

    // Check if bullet line ( '-', '*', '+' ) with whitespace after
    if ((cLower == '-' || cLower == '*' || cLower == '+') && input.peek(1).isWhitespace()) {
        input.reset(mark)
        return parseList(input)
    }

    // Else parse paragraph
    input.reset(mark)
    return parseParagraph(input)
}

/* -------------------------------------------------------------------------
   parseSection: read a heading, then parse *its* contents
   ------------------------------------------------------------------------- */
/**
 * Parses a section heading and its content.
 *
 * Reads the raw heading level (the number of '#' characters) and the title.
 * Then computes the effective level as follows:
 *
 * Finally, it parses the section’s content using the current raw level as the parent.
 */
private fun <Span> parseSection(input: CharInput<Span>, subtractLevel: Int = 0): Markdown.Section {
    skipSpaces(input)
    var rawLevel = 0
    while (input.current == '#') {
        rawLevel++
        input.advance()
    }
    skipSpaces(input)
    val title = buildString {
        while (input.current != EOB && input.current != '\n' && input.current != '\r') {
            append(input.current)
            input.advance()
        }
    }.trim()
    if (input.current == '\r') input.advance()
    if (input.current == '\n') input.advance()
    val effective = rawLevel - subtractLevel
    val contentBlocks = parseBlocks(input, parentRaw = rawLevel)
    return Markdown.Section(level = effective, title = title, content = Markdown.combine(contentBlocks))
}

/**
 * Instead of skipping all blank lines, this only skips spaces on the current line.
 * If the current char is a newline, we consume exactly one newline and stop.
 * That way we don't keep skipping all lines and lose indentation context.
 */
private fun <Span> skipLineSpaces(input: CharInput<Span>) {
    // skip horizontal spaces
    while (true) {
        val c = input.current
        if (c == CharInput.EOB || (c != ' ' && c != '\t')) break
        input.advance()
    }
    // optionally skip exactly one newline
    if (input.current == '\r') {
        input.advance()
        if (input.current == '\n') {
            input.advance()
        }
    } else if (input.current == '\n') {
        input.advance()
    }
}

/**
 * Returns the number of spaces/tabs at the beginning of the current line without consuming input.
 */
private fun <Span> peekIndent(input: CharInput<Span>): Int {
    val mark = input.mark()
    val indent = parseIndentLevel(input)
    input.reset(mark)
    return indent
}

/** Returns true if the current line looks like an optional indent + bullet marker. */
private fun <Span> isListLine(input: CharInput<Span>): Boolean {
    val mark = input.mark()
    // Consume any horizontal indentation on the current line (spaces/tabs).
    // We do NOT skip full blank lines here, so indentation remains meaningful.
    parseIndentLevel(input)

    // Check if the next character is '-', '*', or '+'
    // and if the character after that is whitespace (so it's a bullet marker).
    val c = input.current.lowercaseChar()
    val looksLikeBullet =
        (c == '-' || c == '*' || c == '+') &&
                input.peek( 1).isWhitespace()

    // Restore the input position so we don't lose this state.
    input.reset(mark)
    return looksLikeBullet
}


/**
 * Parses a list. Only lines whose raw indent is at least [minIndent] are included.
 * For a top-level list, call with minIndent = 0.
 */
private fun <Span> parseList(input: CharInput<Span>, minIndent: Int = 0): Markdown.List {
    val items = mutableListOf<Markdown>()
    while (true) {
        // Peek at the indent of the current line.
        val currentIndent = peekIndent(input)
        if (currentIndent < minIndent) break  // This line is too unindented to belong here.
        if (!isListLine(input)) break      // Not a bullet line? Break out.
        // Parse one list item; pass the current indent as the expected indent.
        items.add(parseListItem(input, expectedIndent = currentIndent))
    }
    return Markdown.List(items)
}

/**
 * Parses a single list item.
 *
 * It first consumes the current line’s indent (which must be >= [expectedIndent]),
 * then the bullet character, and then the remainder of the line as text.
 * After skipping the newline, if the next line’s raw indent is greater than the current item’s indent,
 * it is parsed as a nested list and attached to this item.
 *
 * If the bullet line had text and there’s nested content, they are combined into a Combine node.
 */
private fun <Span> parseListItem(input: CharInput<Span>, expectedIndent: Int): Markdown {
    // Consume the current line's indent.
    val actualIndent = parseIndentLevel(input)
    if (actualIndent < expectedIndent) {
        // (This should not happen in proper usage.)
        return Markdown.Paragraph("")
    }
    // Consume the bullet character.
    val bullet = input.current
    input.advance()

    // Skip spaces after the bullet.
    skipSpaces(input)
    // Read the remainder of the line as the item's text.
    val lineText = buildString {
        while (input.current != EOB && input.current != '\n' && input.current != '\r') {
            append(input.current)
            input.advance()
        }
    }.trim()
    // Skip the newline, if present.
    if (input.current == '\r') input.advance()
    if (input.current == '\n') input.advance()
    // Peek at the next line’s indent.
    val nextIndent = peekIndent(input)
    // If the next line is more indented and is a bullet line, parse it as a nested list.
    val nestedList: Markdown.List? = if (nextIndent > actualIndent && isListLine(input)) {
        parseList(input, minIndent = nextIndent)
    } else {
        null
    }
    return if (nestedList == null) {
        // No nested list; the item is just a paragraph.
        Markdown.Paragraph(lineText)
    } else {
        // If the bullet line had text, combine it with the nested list;
        // otherwise, the nested list is the entire content.
        if (lineText.isBlank()) nestedList
        else Markdown.Combine(Markdown.Paragraph(lineText), nestedList)
    }
}

/**
 * Determines whether the following lines form a nested list.
 * It peeks at the raw indent of the next line. If that indent is greater than [currentIndent]
 * and the next line starts with a bullet, it returns the parsed nested list.
 * Otherwise, it returns null.
 */
private fun <Span> parsePotentialNestedList(input: CharInput<Span>, currentIndent: Int): Markdown.List? {
    val nextIndent = peekNextLineIndent(input)
    if (nextIndent > currentIndent) {
        return parseList(input, minIndent = nextIndent)
    }
    return null
}

/**
 * Peeks at the indent level of the next line.
 * If the current character is a newline, consumes that newline (and its pair, if present)
 * and then returns the number of spaces/tabs at the start of the following line.
 */
private fun <Span> peekNextLineIndent(input: CharInput<Span>): Int {
    val mark = input.mark()
    // If the current character is a newline, consume it (and any paired newline)
    if (input.current == '\r' || input.current == '\n') {
        if (input.current == '\r') {
            input.advance()
            if (input.current == '\n') input.advance()
        } else {
            input.advance()
        }
    }
    val indent = parseIndentLevel(input)
    input.reset(mark)
    return indent
}

/* -------------------------------------------------------------------------
   parseParagraph: read text until newline, ignoring bullet logic
   ------------------------------------------------------------------------- */
private fun <Span> parseParagraph(input: CharInput<Span>): Markdown.Paragraph {
    val sb = StringBuilder()
    while (true) {
        val c = input.current
        if (c == EOB || c == '\n' || c == '\r') {
            if (c == '\r') input.advance()
            if (input.current == '\n') input.advance()
            break
        }
        sb.append(c)
        input.advance()
    }
    return Markdown.Paragraph(sb.toString().trim())
}
