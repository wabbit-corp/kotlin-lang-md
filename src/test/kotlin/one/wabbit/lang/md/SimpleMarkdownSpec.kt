package one.wabbit.lang.md

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

import one.wabbit.parsing.CharInput
import one.wabbit.parsing.EmptySpan

class MarkdownParserTest {

    // A simple helper for test input.
    // In your project, you might have CharInput.withTextOnlySpans(...) or a similar factory.
    private fun charInputOf(text: String): CharInput<EmptySpan> = CharInput.withEmptySpans(text)

    @Test
    fun testEmptyInput() {
        val input = charInputOf("")
        val result = parseMarkdown(input)
        // Expect a Paragraph with empty text (see code's fallback for no blocks)
        assertIs<Markdown.Empty>(result)
    }

    @Test
    fun testSingleHeading() {
        val input = charInputOf("# Hello World\n")
        val result = parseMarkdown(input)
        // Expect a single Section with level=1, title="Hello World"
        assertIs<Markdown.Section>(result)
        assertEquals(1, result.level)
        assertEquals("Hello World", result.title)
        assertIs<Markdown.Empty>(result.content, "Section should have no sub-content")
    }

    @Test
    fun testHeadingWithParagraph() {
        val input = charInputOf(
            """
            # Title
            This is a paragraph under Title.
            """.trimIndent()
        )
        val result = parseMarkdown(input)
        assertIs<Markdown.Section>(result)
        assertEquals(1, result.level)
        assertEquals("Title", result.title)
        // The section should have 1 content block, a Paragraph
        assertIs<Markdown.Paragraph>(result.content)
        val paragraph = result.content as Markdown.Paragraph
        assertEquals("This is a paragraph under Title.", paragraph.text)
    }

    @Test
    fun testTwoHeadings() {
        val input = charInputOf(
            """
            # Heading One
            Paragraph under one

            # Heading Two
            Another paragraph
            """.trimIndent()
        )
        val result = parseMarkdown(input)
        // We expect a Combine with two sections
        assertIs<Markdown.Combine>(result)
        assertEquals(2, result.parts.size)

        val sec1 = result.parts[0]
        assertIs<Markdown.Section>(sec1)
        assertEquals(1, sec1.level)
        assertEquals("Heading One", sec1.title)
        assertIs<Markdown.Paragraph>(sec1.content)
        assertEquals("Paragraph under one", (sec1.content as Markdown.Paragraph).text)

        val sec2 = result.parts[1]
        assertIs<Markdown.Section>(sec2)
        assertEquals(1, sec2.level)
        assertEquals("Heading Two", sec2.title)
        assertIs<Markdown.Paragraph>(sec2.content)
        assertEquals("Another paragraph", (sec2.content as Markdown.Paragraph).text)
    }

    @Test
    fun testNestedHeadings() {
        val input = charInputOf(
            """
            # Top Section
            Some text

            ## Sub Section
            More text

            #### Sub-sub Section
            Even more text

            # Another Top
            Last text
            """.trimIndent()
        )
        val result = parseMarkdown(input)
        /*
         * Expect something like:
         * Combine([
         *   Section(level=1, title="Top Section", contents=[
         *     Paragraph("Some text"),
         *     Section(level=1, title="Sub Section", contents=[
         *       Paragraph("More text"),
         *       Section(level=2, title="Sub-sub Section", contents=[
         *         Paragraph("Even more text")
         *       ])
         *     ])
         *   ]),
         *   Section(level=1, title="Another Top", contents=[
         *     Paragraph("Last text")
         *   ])
         * ])
         */
        assertIs<Markdown.Combine>(result)
        assertEquals(2, result.parts.size)

        // First top-level section
        val sec1 = result.parts[0]
        assertIs<Markdown.Section>(sec1)
        assertEquals("Top Section", sec1.title)
        assertIs<Markdown.Combine>(sec1.content)
        val sec1Content = sec1.content as Markdown.Combine
        assertEquals(2, sec1Content.parts.size)

        val p1 = sec1Content.parts[0]
        assertIs<Markdown.Paragraph>(p1)
        assertEquals("Some text", p1.text)

        // The sub section
        val subSec = sec1Content.parts[1]
        assertIs<Markdown.Section>(subSec)
        assertEquals(1, subSec.level)
        assertEquals("Sub Section", subSec.title)
        assertIs<Markdown.Combine>(subSec.content)
        val subSecContent = subSec.content as Markdown.Combine
        assertEquals(2, subSecContent.parts.size)

        val p2 = subSecContent.parts[0]
        assertIs<Markdown.Paragraph>(p2)
        assertEquals("More text", p2.text)

        val subSubSec = subSecContent.parts[1]
        assertIs<Markdown.Section>(subSubSec)
        assertEquals(2, subSubSec.level)
        assertEquals("Sub-sub Section", subSubSec.title)
        assertIs<Markdown.Paragraph>(subSubSec.content)
        assertEquals("Even more text", (subSubSec.content as Markdown.Paragraph).text)

        // The second top-level section
        val sec2 = result.parts[1]
        assertIs<Markdown.Section>(sec2)
        assertEquals(1, sec2.level)
        assertEquals("Another Top", sec2.title)
        val p3 = sec2.content
        assertIs<Markdown.Paragraph>(p3)
        assertEquals("Last text", p3.text)
    }

    @Test
    fun testSingleList() {
        val input = charInputOf(
            """
            - One
            - Two
            - Three
            """.trimIndent()
        )
        val result = parseMarkdown(input)
        /*
         * Expect: List([
         *   Paragraph("One"),
         *   Paragraph("Two"),
         *   Paragraph("Three")
         * ])
         */
        assertIs<Markdown.List>(result)
        assertEquals(3, result.items.size)
        assertIs<Markdown.Paragraph>(result.items[0])
        assertIs<Markdown.Paragraph>(result.items[1])
        assertIs<Markdown.Paragraph>(result.items[2])
        assertEquals("One", (result.items[0] as Markdown.Paragraph).text)
        assertEquals("Two", (result.items[1] as Markdown.Paragraph).text)
        assertEquals("Three", (result.items[2] as Markdown.Paragraph).text)
    }

    @Test
    fun testMixedBullets() {
        val input = charInputOf(
            """
            * Alpha
            + Beta
            - Gamma
            """.trimIndent()
        )
        val result = parseMarkdown(input)
        // Same as a single List with three items.
        assertIs<Markdown.List>(result)
        assertEquals(3, result.items.size)
        assertEquals("Alpha", (result.items[0] as Markdown.Paragraph).text)
        assertEquals("Beta", (result.items[1] as Markdown.Paragraph).text)
        assertEquals("Gamma", (result.items[2] as Markdown.Paragraph).text)
    }

    @Test
    fun testNestedList() {
        val input = charInputOf(
            """
            - Outer
              + Inner 1
              + Inner 2
            - Outer 2
            """.trimIndent()
        )
        val result = parseMarkdown(input)
        /*
         * Expect:
         * List([
         *   Combine(
         *     Paragraph("Outer"),
         *     List([
         *       Paragraph("Inner 1"),
         *       Paragraph("Inner 2")
         *     ])
         *   ),
         *   Paragraph("Outer 2")
         * ])
         */
        assertIs<Markdown.List>(result)
        assertEquals(2, result.items.size)

        val item1 = result.items[0]
        assertIs<Markdown.Combine>(item1)
        val combineParts = item1.parts
        assertEquals(2, combineParts.size)
        assertIs<Markdown.Paragraph>(combineParts[0])
        assertEquals("Outer", (combineParts[0] as Markdown.Paragraph).text)

        val nestedList = combineParts[1]
        assertIs<Markdown.List>(nestedList)
        assertEquals(2, nestedList.items.size)
        assertEquals("Inner 1", (nestedList.items[0] as Markdown.Paragraph).text)
        assertEquals("Inner 2", (nestedList.items[1] as Markdown.Paragraph).text)

        val item2 = result.items[1]
        assertIs<Markdown.Paragraph>(item2)
        assertEquals("Outer 2", item2.text)
    }

    @Test
    fun testHeadingThenList() {
        val input = charInputOf(
            """
            # Heading
            - First
            - Second
            """.trimIndent()
        )
        val result = parseMarkdown(input)
        /*
         * Expect:
         * Section(level=1, title="Heading", contents=[
         *   List([
         *     Paragraph("First"),
         *     Paragraph("Second")
         *   ])
         * ])
         */
        assertIs<Markdown.Section>(result)
        assertEquals("Heading", result.title)

        val contentBlock = result.content
        assertIs<Markdown.List>(contentBlock)
        assertEquals(2, contentBlock.items.size)
        assertEquals("First", (contentBlock.items[0] as Markdown.Paragraph).text)
        assertEquals("Second", (contentBlock.items[1] as Markdown.Paragraph).text)
    }
}
