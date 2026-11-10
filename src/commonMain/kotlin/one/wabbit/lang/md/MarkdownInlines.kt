package one.wabbit.lang.md

/** Parse inline syntax without rendering HTML or performing I/O. Unmatched delimiters stay literal. */
fun parseMarkdownInlines(source: String): List<MarkdownInline> = InlineReader(source, emptyMap(), 0).read()

internal data class MarkdownReference(val destination: String, val title: String?)

internal fun parseMarkdownInlines(source: String, references: Map<String, MarkdownReference>): List<MarkdownInline> =
    InlineReader(source, references, 0).read()

private data class InlineChunk(val node: MarkdownInline, val depth: Int = 0)
private data class InlineDelimiter(
    val character: Char,
    var count: Int,
    val position: Int,
    val canClose: Boolean,
)

/**
 * Scan code, escapes and links first, then pair emphasis delimiters around those atomic values.
 * The opener stack permits nested formatting without interpreting code contents. Bounded source
 * and nesting keep malformed model output finite; an over-deep inline remains readable verbatim.
 */
private class InlineReader(
    private val source: String,
    private val references: Map<String, MarkdownReference>,
    private val depth: Int,
    private val allowLinks: Boolean = true,
) {
    private val chunks = mutableListOf<InlineChunk>()
    private val openers = mutableListOf<InlineDelimiter>()
    private val text = StringBuilder()
    private val codeSpans by lazy(LazyThreadSafetyMode.NONE) { MarkdownCodeSpans(source) }
    private var index = 0
    private var angleEnd: Int? = null

    fun read(): List<MarkdownInline> {
        if (source.length > 65_536 || depth >= 32) return listOf(MarkdownInline.Text(source))
        while (index < source.length) {
            when (val character = source[index]) {
                '\\' -> escape()
                '\n' -> lineBreak()
                '`' -> code()
                '[', '!' -> if (!link()) { text.append(character); index++ }
                '<' -> if (!autolink()) { text.append(character); index++ }
                '*', '_', '~' -> if (!delimiter(character)) return listOf(MarkdownInline.Text(source))
                '&' -> {
                    val entity = markdownCharacterReference(source, index)
                    if (entity == null) { text.append(character); index++ }
                    else { text.append(entity.text); index = entity.end }
                }
                else -> { text.append(character); index++ }
            }
        }
        flush()
        return chunks.map { it.node }
    }

    private fun flush() {
        if (text.isNotEmpty()) {
            chunks += InlineChunk(MarkdownInline.Text(text.toString()))
            text.clear()
        }
    }

    private fun append(node: MarkdownInline, nodeDepth: Int = 0) {
        flush()
        chunks += InlineChunk(node, nodeDepth)
    }

    private fun escape() {
        val next = source.getOrNull(index + 1)
        when {
            next == '\n' -> { append(MarkdownInline.HardBreak); index += 2 }
            next != null && next.isAsciiPunctuation() -> { text.append(next); index += 2 }
            else -> { text.append('\\'); index++ }
        }
    }

    private fun lineBreak() {
        val spaces = text.takeLastWhile { it == ' ' }.length
        if (spaces >= 2) text.setLength(text.length - spaces)
        append(if (spaces >= 2) MarkdownInline.HardBreak else MarkdownInline.SoftBreak)
        index++
    }

    private fun code() {
        val count = runLength(index, '`')
        val end = codeSpans.closing(index + count, count)
        if (end == null) {
            text.append("`".repeat(count))
            index += count
            return
        }
        var value = source.substring(index + count, end).replace('\n', ' ')
        if (value.startsWith(' ') && value.endsWith(' ') && value.any { it != ' ' }) value = value.drop(1).dropLast(1)
        append(MarkdownInline.Code(value))
        index = end + count
    }

    /** Resolve only matching, properly flanking delimiters; unused markers remain literal nodes. */
    private fun delimiter(character: Char): Boolean {
        val count = runLength(index, character)
        if (character == '~' && count != 2) {
            text.append("~".repeat(count)); index += count
            return true
        }
        val before = source.getOrNull(index - 1)
        val after = source.getOrNull(index + count)
        val left = after != null && !after.isWhitespace() &&
            (!after.isPunctuation() || before == null || before.isWhitespace() || before.isPunctuation())
        val right = before != null && !before.isWhitespace() &&
            (!before.isPunctuation() || after == null || after.isWhitespace() || after.isPunctuation())
        val canOpen = left && (character != '_' || !right || before.isPunctuation())
        val canClose = right && (character != '_' || !left || after.isPunctuation())
        flush()
        var remaining = count
        while (canClose && remaining > 0) {
            val match = openers.indexOfLast { opener ->
                opener.character == character && (character == '~' ||
                    !((opener.canClose || canOpen) && (opener.count + remaining) % 3 == 0 &&
                        (opener.count % 3 != 0 || remaining % 3 != 0)))
            }
            if (match < 0) break
            val opener = openers[match]
            val children = chunks.subList(opener.position + 1, chunks.size).toList()
            val nestedDepth = (children.maxOfOrNull { it.depth } ?: 0) + 1
            if (depth + nestedDepth >= 32) return false
            val used = if (opener.count >= 2 && remaining >= 2) 2 else 1
            val content = children.map { it.node }
            val wrapped = when {
                character == '~' -> MarkdownInline.Strike(content)
                used == 2 -> MarkdownInline.Strong(content)
                else -> MarkdownInline.Emphasis(content)
            }
            while (openers.size > match + 1) openers.removeAt(openers.lastIndex)
            while (chunks.size > opener.position + 1) chunks.removeAt(chunks.lastIndex)
            opener.count -= used
            remaining -= used
            if (opener.count == 0) {
                chunks.removeAt(chunks.lastIndex)
                openers.removeAt(match)
            } else chunks[opener.position] = InlineChunk(MarkdownInline.Text(character.toString().repeat(opener.count)))
            chunks += InlineChunk(wrapped, nestedDepth)
        }
        if (remaining > 0) {
            if (canOpen) {
                if (openers.size >= 32) return false
                openers += InlineDelimiter(character, remaining, chunks.size, canClose)
            }
            chunks += InlineChunk(MarkdownInline.Text(character.toString().repeat(remaining)))
        }
        index += count
        return true
    }

    /** Balanced labels/destinations preserve escaped punctuation and never nest interactive links. */
    private fun link(): Boolean {
        val image = source[index] == '!'
        val start = index + if (image) 1 else 0
        if (source.getOrNull(start) != '[' || (!image && !allowLinks)) return false
        val close = closingBracket(start) ?: return false
        val label = source.substring(start + 1, close)
        val inline = if (source.getOrNull(close + 1) == '(') destination(close + 2) else null
        var end = inline?.second ?: close + 1
        val target = inline?.first ?: run {
            var reference = label
            if (source.getOrNull(close + 1) == '[') {
                val referenceEnd = source.indexOf(']', close + 2)
                if (referenceEnd < 0) return false
                reference = source.substring(close + 2, referenceEnd).ifEmpty { label }
                end = referenceEnd + 1
            }
            references[reference.markdownReferenceKey()] ?: return false
        }
        val content = InlineReader(label, references, depth + 1, allowLinks = false).read()
        val node = if (image) MarkdownInline.Image(content, target.destination, target.title)
            else MarkdownInline.Link(content, target.destination, target.title)
        append(node, depth + 1)
        index = end
        return true
    }

    private fun closingBracket(start: Int): Int? {
        var nesting = 0
        var cursor = start + 1
        while (cursor < source.length) {
            when (source[cursor]) {
                '\\' -> cursor++
                '[' -> if (++nesting > 32) return null
                ']' -> if (nesting-- == 0) return cursor
                '`' -> {
                    val count = runLength(cursor, '`')
                    val end = codeSpans.closing(cursor + count, count)
                    cursor = if (end != null) end + count - 1 else cursor + count - 1
                }
            }
            cursor++
        }
        return null
    }

    private fun destination(start: Int): Pair<MarkdownReference, Int>? {
        var cursor = start
        while (source.getOrNull(cursor)?.isWhitespace() == true) cursor++
        val begin = cursor
        val angled = source.getOrNull(cursor) == '<'
        if (angled) cursor++
        var nesting = 0
        while (cursor < source.length) {
            val char = source[cursor]
            if (char == '\\' && source.getOrNull(cursor + 1)?.isAsciiPunctuation() == true) { cursor += 2; continue }
            if (angled && (char == '\n' || char == '<')) return null
            if (angled && char == '>') { cursor++; break }
            if (!angled && (char.isWhitespace() || (char == ')' && nesting == 0))) break
            if (!angled && char == '(' && ++nesting > 32) return null
            if (!angled && char == ')') nesting--
            cursor++
        }
        if (angled && source.getOrNull(cursor - 1) != '>') return null
        val raw = source.substring(begin + if (angled) 1 else 0, cursor - if (angled) 1 else 0)
        val separator = cursor
        while (source.getOrNull(cursor)?.isWhitespace() == true) cursor++
        var title: String? = null
        val quote = source.getOrNull(cursor)
        if (cursor > separator && (quote == '"' || quote == '\'' || quote == '(')) {
            val closing = if (quote == '(') ')' else quote
            val titleStart = ++cursor
            while (cursor < source.length && source[cursor] != closing) {
                if (source[cursor] == '\\') cursor++
                cursor++
            }
            if (cursor >= source.length) return null
            title = decodeMarkdownText(source.substring(titleStart, cursor++))
            while (source.getOrNull(cursor)?.isWhitespace() == true) cursor++
        }
        if (source.getOrNull(cursor) != ')') return null
        return MarkdownReference(decodeMarkdownText(raw), title) to cursor + 1
    }

    private fun autolink(): Boolean {
        if (!allowLinks) return false
        // Several '<' candidates can share one closing angle. Do not rescan/copy that suffix.
        val cached = angleEnd
        val end = if (cached == null || (cached >= 0 && cached <= index))
            source.indexOf('>', index + 1).also { angleEnd = it } else cached
        if (end < 0) return false
        val nested = source.indexOf('<', index + 1)
        if (nested in (index + 1) until end) return false
        val value = source.substring(index + 1, end)
        if (value.any { it.isWhitespace() || it == '<' }) return false
        val scheme = value.substringBefore(':', "")
        val uri = scheme.length in 2..32 && scheme.first().isLetter() && scheme.all { it.isLetterOrDigit() || it in "+.-" }
        val email = value.count { it == '@' } == 1 && value.substringAfter('@').contains('.') && value.none { it in "():" }
        if (!uri && !email) return false
        append(MarkdownInline.Link(listOf(MarkdownInline.Text(value)), if (email) "mailto:$value" else value))
        index = end + 1
        return true
    }

    private fun runLength(start: Int, character: Char): Int {
        var end = start
        while (end < source.length && source[end] == character) end++
        return end - start
    }
}

internal fun String.markdownReferenceKey(): String = trim().split(Regex("\\s+")).joinToString(" ").lowercase()

private fun Char.isPunctuation(): Boolean = !isLetterOrDigit() && !isWhitespace()
private fun Char.isAsciiPunctuation(): Boolean = this in '!'..'/' || this in ':'..'@' || this in '['..'`' || this in '{'..'~'
