package one.wabbit.lang.md

/**
 * Index complete backtick runs once. Code/link/table readers can then find an exact closing run
 * without repeatedly counting the same malformed suffix. Each lookup is logarithmic in the
 * number of runs of that length; source scanning and retained indices are linear in input size.
 */
internal class MarkdownCodeSpans(source: String) {
    private val positionsByLength: Map<Int, List<Int>>

    init {
        val positions = mutableMapOf<Int, MutableList<Int>>()
        var cursor = 0
        while (cursor < source.length) {
            if (source[cursor] != '`') cursor++
            else {
                val start = cursor
                while (cursor < source.length && source[cursor] == '`') cursor++
                positions.getOrPut(cursor - start) { mutableListOf() } += start
            }
        }
        positionsByLength = positions
    }

    fun closing(start: Int, count: Int): Int? {
        val positions = positionsByLength[count] ?: return null
        var low = 0
        var high = positions.size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (positions[middle] < start) low = middle + 1 else high = middle
        }
        return positions.getOrNull(low)
    }
}
