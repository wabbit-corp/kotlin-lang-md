package one.wabbit.lang.md

internal data class MarkdownCharacterReference(val text: String, val end: Int)

/** Markdown requires a semicolon, decodes once, and leaves unknown/invalid syntax untouched. */
internal fun markdownCharacterReference(source: String, start: Int): MarkdownCharacterReference? {
    if (source.getOrNull(start) != '&') return null
    var end = start + 1
    while (end < source.length && end - start <= 33 && source[end] != ';') end++
    if (source.getOrNull(end) != ';') return null
    val name = source.substring(start + 1, end)
    val text = if (name.startsWith('#')) {
        val hexadecimal = name.getOrNull(1) == 'x' || name.getOrNull(1) == 'X'
        val digits = name.drop(if (hexadecimal) 2 else 1)
        if (digits.length !in 1..(if (hexadecimal) 6 else 7) || digits.any {
                if (hexadecimal) it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' else it !in '0'..'9'
            }) return null
        val value = digits.toIntOrNull(if (hexadecimal) 16 else 10) ?: return null
        val point = if (value == 0 || value in 0xD800..0xDFFF || value > 0x10FFFF) 0xFFFD else value
        if (point <= 0xFFFF) point.toChar().toString()
        else ((point - 0x10000) / 0x400 + 0xD800).toChar().toString() + ((point - 0x10000) % 0x400 + 0xDC00).toChar()
    } else markdownNamedCharacterReference(name) ?: return null
    return MarkdownCharacterReference(text, end + 1)
}

/** Backslash punctuation escapes and character references have the same meaning in link metadata. */
internal fun decodeMarkdownText(source: String): String = buildString {
    var index = 0
    while (index < source.length) {
        val character = source[index]
        val next = source.getOrNull(index + 1)
        if (character == '\\' && next != null && (next in '!'..'/' || next in ':'..'@' || next in '['..'`' || next in '{'..'~')) {
            append(next)
            index += 2
        } else {
            val entity = if (character == '&') markdownCharacterReference(source, index) else null
            if (entity == null) { append(character); index++ }
            else { append(entity.text); index = entity.end }
        }
    }
}
