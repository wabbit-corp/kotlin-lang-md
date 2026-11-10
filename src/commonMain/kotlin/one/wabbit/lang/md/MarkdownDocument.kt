package one.wabbit.lang.md

/**
 * Presentation syntax parsed from a Markdown source. Unlike [Markdown]'s section-building DSL,
 * this tree retains inline formatting, ordered-list starts and code literally. Renderers consume
 * these values without interpreting source text as HTML. The original source remains authoritative.
 */
data class MarkdownDocument(val blocks: List<MarkdownBlock>)

sealed interface MarkdownBlock {
    /** Unparsed source retained when a presentation parsing bound is reached. */
    data class Literal(val text: String) : MarkdownBlock
    data class Paragraph(val content: List<MarkdownInline>) : MarkdownBlock
    data class Heading(val level: Int, val content: List<MarkdownInline>) : MarkdownBlock
    data class Code(val text: String, val language: String?) : MarkdownBlock
    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock
    data class ListBlock(val items: List<MarkdownListItem>, val start: Int? = null) : MarkdownBlock
    data class Table(
        val headers: List<List<MarkdownInline>>,
        val alignments: List<MarkdownAlignment>,
        val rows: List<List<List<MarkdownInline>>>,
    ) : MarkdownBlock
    data object Rule : MarkdownBlock
}

/** A task marker is display-only; parsing a checklist never grants an editing action. */
data class MarkdownListItem(val blocks: List<MarkdownBlock>, val checked: Boolean? = null)

enum class MarkdownAlignment { DEFAULT, LEFT, CENTER, RIGHT }

sealed interface MarkdownInline {
    data class Text(val text: String) : MarkdownInline
    data class Emphasis(val content: List<MarkdownInline>) : MarkdownInline
    data class Strong(val content: List<MarkdownInline>) : MarkdownInline
    data class Strike(val content: List<MarkdownInline>) : MarkdownInline
    data class Code(val text: String) : MarkdownInline
    data class Link(val content: List<MarkdownInline>, val destination: String, val title: String? = null) : MarkdownInline
    data class Image(val description: List<MarkdownInline>, val destination: String, val title: String? = null) : MarkdownInline
    data object SoftBreak : MarkdownInline
    data object HardBreak : MarkdownInline
}
