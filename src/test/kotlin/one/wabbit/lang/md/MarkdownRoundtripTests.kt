package one.wabbit.lang.md

import one.wabbit.parsing.CharInput
import one.wabbit.parsing.EmptySpan
import one.wabbit.random.gen.Gen
import one.wabbit.random.gen.foreachMin
import java.util.SplittableRandom
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownRoundtripTests {
    val string = Gen.listOf(1..5, Gen.oneOfGen(Gen.range('a'..'z'), Gen.const(' ')))
        .map { it.joinToString("").trim() }.filter { it.isNotEmpty() }

    val genMarkdown: Gen<Markdown> = Gen.recursive { gen ->
        Gen.oneOfGen(
            string.map { Markdown.Paragraph(it) },
            (Gen.range(1..2) zip string zip Gen.listOf(2, gen))
                .map { t -> Markdown.Section(t.first.first, t.first.second, Markdown.combine(t.second)) },
            Gen.listOf(2..3, gen).filter { it.isNotEmpty() }.map { Markdown.combine(it) },
            Gen.listOf(1..3, gen).filter { it.isNotEmpty() }.map { Markdown.list(it) }
        )
    }


    // A simple helper for test input.
    // In your project, you might have CharInput.withTextOnlySpans(...) or a similar factory.
    private fun charInputOf(text: String): CharInput<EmptySpan> = CharInput.withEmptySpans(text)

    @Ignore
    @Test
    fun testRoundtrip() {
        val rng = SplittableRandom(2)
        genMarkdown.foreachMin(rng, 100) {
            val markdown = it
            val text = markdown.toText()
            val parsed = parseMarkdown(charInputOf(text))
            assertEquals(markdown, parsed, "Roundtrip failed for: $markdown:\n$text")
        }
    }
}
