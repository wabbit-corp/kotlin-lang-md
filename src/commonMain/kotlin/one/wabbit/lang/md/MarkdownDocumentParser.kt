package one.wabbit.lang.md

/**
 * Parse chat/document Markdown into inert syntax, not HTML. Supports common block/inline forms,
 * pipe tables and task markers. Incomplete fences consume to end; malformed syntax stays text.
 * Input above 65,536 UTF-16 code units or nesting above 32 remains literal rather than allocating
 * an unbounded tree. This is a presentation parser, not a claim of complete CommonMark conformance.
 */
fun parseMarkdownDocument(source: String): MarkdownDocument {
    if (source.length > 65_536) return MarkdownDocument(listOf(MarkdownBlock.Literal(source)))
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val references = mutableMapOf<String, MarkdownReference>()
    // Use the same container grammar for reference discovery: quoted definitions count, code does not.
    BlockReader(lines, references, 0, collectReferences = true).read()
    return MarkdownDocument(BlockReader(lines, references, 0, collectReferences = false).read())
}

private data class MarkdownFence(val marker: Char, val count: Int, val indent: Int, val language: String?) {
    fun closes(line: String): Boolean {
        val spaces = line.indentation()
        if (spaces > 3) return false
        val content = line.drop(spaces)
        val run = content.takeWhile { it == marker }.length
        return run >= count && content.drop(run).isBlank()
    }
}

private data class MarkdownListMarker(val indent: Int, val contentIndent: Int, val start: Int?, val text: String)

/** Block containers recursively strip their own markers; code contents never enter inline parsing. */
private class BlockReader(
    private val lines: List<String>,
    private val references: MutableMap<String, MarkdownReference>,
    private val depth: Int,
    private val collectReferences: Boolean,
) {
    private var index = 0
    private fun inlines(text: String) = if (collectReferences) emptyList() else parseMarkdownInlines(text, references)

    fun read(): List<MarkdownBlock> {
        if (depth >= 32) return listOf(MarkdownBlock.Literal(lines.joinToString("\n")))
        val blocks = mutableListOf<MarkdownBlock>()
        while (index < lines.size) {
            val line = lines[index]
            val fence = line.markdownFence()
            val heading = line.markdownHeading()
            val reference = line.markdownReference()
            when {
                line.isBlank() -> index++
                fence != null -> blocks += codeFence(fence)
                line.indentation() >= 4 || line.startsWith('\t') -> blocks += indentedCode()
                reference != null -> {
                    if (collectReferences) references.getOrPut(reference.first) { reference.second }
                    index++
                }
                heading != null -> { blocks += MarkdownBlock.Heading(heading.first, inlines(heading.second)); index++ }
                line.isMarkdownRule() -> { blocks += MarkdownBlock.Rule; index++ }
                line.quoteContent() != null -> blocks += quote()
                line.listMarker() != null -> blocks += list()
                tableAlignments() != null -> blocks += table()
                else -> blocks += paragraph()
            }
        }
        return blocks
    }

    private fun codeFence(fence: MarkdownFence): MarkdownBlock.Code {
        index++
        val contents = mutableListOf<String>()
        while (index < lines.size && !fence.closes(lines[index])) {
            val line = lines[index++]
            contents += line.drop(minOf(fence.indent, line.indentation()))
        }
        if (index < lines.size) index++
        return MarkdownBlock.Code(contents.joinToString("\n"), fence.language)
    }

    private fun indentedCode(): MarkdownBlock.Code {
        val contents = mutableListOf<String>()
        while (index < lines.size) {
            val line = lines[index]
            when {
                line.isBlank() -> contents += ""
                line.startsWith('\t') -> contents += line.drop(1)
                line.indentation() >= 4 -> contents += line.drop(4)
                else -> break
            }
            index++
        }
        while (contents.lastOrNull() == "") contents.removeAt(contents.lastIndex)
        return MarkdownBlock.Code(contents.joinToString("\n"), null)
    }

    private fun quote(): MarkdownBlock.Quote {
        val contents = mutableListOf<String>()
        val continuation = MarkdownContainerContinuation()
        while (index < lines.size) {
            val line = lines[index]
            val quoted = line.quoteContent()
            if (quoted != null) {
                contents += quoted
                continuation.append(quoted)
            }
            else if (continuation.paragraphOpen && line.isNotBlank() && !line.startsMarkdownBlock()) {
                contents += line
                continuation.append(line)
            }
            else break
            index++
        }
        return MarkdownBlock.Quote(BlockReader(contents, references, depth + 1, collectReferences).read())
    }

    /** Each item's own indentation determines its nested blocks; sibling markers retain ordering. */
    private fun list(): MarkdownBlock.ListBlock {
        val first = lines[index].listMarker()!!
        val items = mutableListOf<MarkdownListItem>()
        while (index < lines.size) {
            val marker = lines[index].listMarker() ?: break
            if (marker.indent != first.indent || (marker.start == null) != (first.start == null)) break
            val contents = mutableListOf(marker.text)
            val continuation = MarkdownContainerContinuation().also { it.append(marker.text) }
            index++
            while (index < lines.size) {
                val line = lines[index]
                val peer = line.listMarker()
                if (peer != null && peer.indent <= first.indent) break
                val content = when {
                    line.isBlank() -> ""
                    line.indentation() >= marker.contentIndent -> line.drop(marker.contentIndent)
                    line.startsWith('\t') -> line.drop(1)
                    continuation.paragraphOpen && !line.startsMarkdownBlock() -> line
                    else -> break
                }
                contents += content
                continuation.append(content)
                index++
            }
            val task = contents[0].taskMarker()
            if (task != null) contents[0] = contents[0].drop(3).trimStart()
            items += MarkdownListItem(BlockReader(contents, references, depth + 1, collectReferences).read(), task)
        }
        return MarkdownBlock.ListBlock(items, first.start)
    }

    private fun tableAlignments(): List<MarkdownAlignment>? {
        if (index + 1 >= lines.size || !lines[index].contains('|')) return null
        val headers = lines[index].tableCells()
        val alignments = lines[index + 1].tableSeparator() ?: return null
        return alignments.takeIf { headers.size == it.size }
    }

    private fun table(): MarkdownBlock.Table {
        val alignments = tableAlignments()!!
        val headers = lines[index].tableCells().map(::inlines)
        index += 2
        val rows = mutableListOf<List<List<MarkdownInline>>>()
        while (index < lines.size && lines[index].isNotBlank() && !lines[index].startsMarkdownBlock()) {
            rows += lines[index++].tableCells().map(::inlines)
        }
        return MarkdownBlock.Table(headers, alignments, rows)
    }

    private fun paragraph(): MarkdownBlock {
        val contents = mutableListOf(lines[index++])
        while (index < lines.size) {
            val underline = lines[index].setextLevel()
            if (underline != null) {
                index++
                return MarkdownBlock.Heading(underline, inlines(contents.joinToString("\n")))
            }
            if (lines[index].isBlank() || lines[index].startsMarkdownBlock() || tableAlignments() != null) break
            contents += lines[index++].trimStart()
        }
        return MarkdownBlock.Paragraph(inlines(contents.joinToString("\n")))
    }
}

private sealed interface MarkdownContainerLeaf {
    data class Paragraph(val lastLine: String) : MarkdownContainerLeaf
    data class Fence(val syntax: MarkdownFence) : MarkdownContainerLeaf
    data object Table : MarkdownContainerLeaf
    data object Other : MarkdownContainerLeaf
}

/** Only an open paragraph accepts an unmarked continuation; other leaf blocks do not. */
private class MarkdownContainerContinuation {
    private var leaf: MarkdownContainerLeaf = MarkdownContainerLeaf.Other
    val paragraphOpen: Boolean get() = leaf is MarkdownContainerLeaf.Paragraph

    fun append(line: String) {
        val current = leaf
        if (current is MarkdownContainerLeaf.Fence) {
            if (current.syntax.closes(line)) leaf = MarkdownContainerLeaf.Other
            return
        }
        if (current is MarkdownContainerLeaf.Paragraph && current.lastLine.contains('|') &&
            line.tableSeparator()?.size == current.lastLine.tableCells().size) {
            leaf = MarkdownContainerLeaf.Table
            return
        }
        val content = line.listMarker()?.text ?: line
        if (current == MarkdownContainerLeaf.Table && content.isNotBlank() && !content.startsMarkdownBlock()) return
        val opening = content.markdownFence()
        val paragraph = current as? MarkdownContainerLeaf.Paragraph
        leaf = when {
            opening != null -> MarkdownContainerLeaf.Fence(opening)
            content.isBlank() || content.markdownHeading() != null || content.isMarkdownRule() || content.quoteContent() != null -> MarkdownContainerLeaf.Other
            paragraph != null && content.setextLevel() != null -> MarkdownContainerLeaf.Other
            paragraph == null && (content.indentation() >= 4 || content.startsWith('\t') || content.markdownReference() != null) -> MarkdownContainerLeaf.Other
            else -> MarkdownContainerLeaf.Paragraph(content)
        }
    }
}

private fun String.indentation(): Int = takeWhile { it == ' ' }.length

private fun String.markdownFence(): MarkdownFence? {
    val spaces = indentation()
    if (spaces > 3) return null
    val content = drop(spaces)
    val marker = content.firstOrNull() ?: return null
    if (marker != '`' && marker != '~') return null
    val count = content.takeWhile { it == marker }.length
    if (count < 3) return null
    val info = content.drop(count).trim()
    if (marker == '`' && '`' in info) return null
    return MarkdownFence(marker, count, spaces, info.takeWhile { !it.isWhitespace() }.ifEmpty { null })
}

private fun String.markdownHeading(): Pair<Int, String>? {
    if (indentation() > 3) return null
    val content = trimStart()
    val level = content.takeWhile { it == '#' }.length
    if (level !in 1..6 || (content.length > level && !content[level].isWhitespace())) return null
    var title = content.drop(level).trimEnd()
    val closing = title.takeLastWhile { it == '#' }.length
    if (closing > 0 && title.getOrNull(title.length - closing - 1)?.isWhitespace() == true) title = title.dropLast(closing).trimEnd()
    return level to title.trim()
}

private fun String.setextLevel(): Int? {
    if (indentation() > 3) return null
    val text = trim()
    if (text.isEmpty()) return null
    return when {
        text.all { it == '=' } -> 1
        text.all { it == '-' } -> 2
        else -> null
    }
}

private fun String.isMarkdownRule(): Boolean {
    if (indentation() > 3) return false
    val text = filterNot { it == ' ' || it == '\t' }
    return text.length >= 3 && text.first() in "-_*" && text.all { it == text.first() }
}

private fun String.quoteContent(): String? {
    val spaces = indentation()
    if (spaces > 3 || getOrNull(spaces) != '>') return null
    val start = spaces + 1
    return drop(start + if (getOrNull(start) == ' ' || getOrNull(start) == '\t') 1 else 0)
}

private fun String.listMarker(): MarkdownListMarker? {
    val spaces = indentation()
    if (spaces > 3) return null
    var end = spaces
    val start: Int?
    if (getOrNull(end) in listOf('-', '+', '*')) { start = null; end++ }
    else {
        while (getOrNull(end) in '0'..'9') end++
        if (end - spaces !in 1..9 || getOrNull(end) !in listOf('.', ')')) return null
        start = substring(spaces, end).toIntOrNull() ?: return null
        end++
    }
    if (end < length && !this[end].isWhitespace()) return null
    val padding = drop(end).takeWhile { it == ' ' || it == '\t' }.length
    val consumed = if (padding in 1..4) padding else minOf(1, padding)
    return MarkdownListMarker(spaces, end + maxOf(1, consumed), start, drop(end + consumed))
}

private fun String.taskMarker(): Boolean? {
    if (length < 3 || this[0] != '[' || this[2] != ']' || (length > 3 && !this[3].isWhitespace())) return null
    return when (this[1]) { ' ' -> false; 'x', 'X' -> true; else -> null }
}

private fun String.startsMarkdownBlock(): Boolean = markdownFence() != null || markdownHeading() != null ||
    isMarkdownRule() || quoteContent() != null || listMarker() != null

private fun String.tableSeparator(): List<MarkdownAlignment>? = tableCells().map { cell ->
    val value = cell.trim()
    val dashes = value.removePrefix(":").removeSuffix(":")
    if (dashes.isEmpty() || dashes.any { it != '-' }) return null
    when {
        value.startsWith(':') && value.endsWith(':') -> MarkdownAlignment.CENTER
        value.endsWith(':') -> MarkdownAlignment.RIGHT
        value.startsWith(':') -> MarkdownAlignment.LEFT
        else -> MarkdownAlignment.DEFAULT
    }
}

/** Ignore escaped pipes and pipes inside matching code spans; cell text still enters the inline parser. */
private fun String.tableCells(): List<String> {
    val source = trim()
    val cells = mutableListOf<String>()
    val value = StringBuilder()
    val codeSpans = MarkdownCodeSpans(source)
    var index = if (source.startsWith('|')) 1 else 0
    var codeRun = 0
    while (index < source.length) {
        val character = source[index]
        if (character == '\\' && index + 1 < source.length) {
            value.append(character).append(source[index + 1]); index += 2
            continue
        }
        if (character == '`') {
            var end = index
            while (end < source.length && source[end] == '`') end++
            val count = end - index
            if (codeRun == 0 && codeSpans.closing(index + count, count) != null) codeRun = count
            else if (codeRun == count) codeRun = 0
            value.append("`".repeat(count)); index += count
            continue
        }
        if (character == '|' && codeRun == 0) {
            cells += value.toString().trim()
            value.clear()
        } else value.append(character)
        index++
    }
    if (value.isNotEmpty() || !source.endsWith('|')) cells += value.toString().trim()
    return cells
}

private fun String.markdownReference(): Pair<String, MarkdownReference>? {
    if (indentation() > 3) return null
    val line = trim()
    if (!line.startsWith('[')) return null
    val close = line.indexOf("]:")
    if (close <= 1) return null
    val key = line.substring(1, close).markdownReferenceKey()
    val rest = line.drop(close + 2).trim()
    val end = if (rest.startsWith('<')) rest.indexOf('>').takeIf { it >= 0 }?.plus(1) ?: return null
        else rest.indexOfFirst { it.isWhitespace() }.takeIf { it >= 0 } ?: rest.length
    if (end == 0) return null
    val destination = rest.substring(0, end).removeSurrounding("<", ">")
    val titleText = rest.drop(end).trim()
    val title = if (titleText.isEmpty()) null else {
        val first = titleText.first()
        val last = if (first == '(') ')' else first
        if (first !in listOf('"', '\'', '(') || titleText.length < 2 || titleText.last() != last) return null
        decodeMarkdownText(titleText.substring(1, titleText.lastIndex))
    }
    return key to MarkdownReference(decodeMarkdownText(destination), title)
}
