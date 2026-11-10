package one.wabbit.lang.md

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarkdownDocumentTest {
    @Test fun emptyAndWhitespaceDocumentsAreEmpty() {
        for (source in listOf("", " ", "\r\n\t\n")) assertTrue(parseMarkdownDocument(source).blocks.isEmpty())
    }

    @Test fun headingsAndParagraphsHaveTheirOwnFormatting() {
        val blocks = parseMarkdownDocument("# A **heading**\n\nA *paragraph*.\n\nSecond\n------").blocks
        val heading = assertIs<MarkdownBlock.Heading>(blocks[0])
        assertEquals(1, heading.level)
        assertTrue(heading.content.any { it is MarkdownInline.Strong })
        assertTrue(assertIs<MarkdownBlock.Paragraph>(blocks[1]).content.any { it is MarkdownInline.Emphasis })
        assertEquals(2, assertIs<MarkdownBlock.Heading>(blocks[2]).level)
        assertEquals("Second", headingText(blocks[2]))
    }

    @Test fun invalidHeadingMarkersAreLiteralAndEmptyHeadingsAreAllowed() {
        for (text in listOf("#not a heading", "####### too deep")) {
            assertEquals(text, paragraphText(parseMarkdownDocument(text).blocks.single()))
        }
        assertTrue(assertIs<MarkdownBlock.Heading>(parseMarkdownDocument("#").blocks.single()).content.isEmpty())
        assertTrue(assertIs<MarkdownBlock.Heading>(parseMarkdownDocument("## ###").blocks.single()).content.isEmpty())
    }

    @Test fun closingHeadingMarkersRequireWhitespace() {
        assertEquals("title", headingText(parseMarkdownDocument("## title ##").blocks.single()))
        assertEquals("title##", headingText(parseMarkdownDocument("## title##").blocks.single()))
    }

    @Test fun nestedEmphasisAndStrongDoNotUseRegexTokenSplitting() {
        val nodes = parseMarkdownInlines("**bold and *italic***")
        val strong = assertIs<MarkdownInline.Strong>(nodes.single())
        assertEquals("bold and italic", strong.content.plain())
        assertTrue(strong.content.any { it is MarkdownInline.Emphasis })
        val triple = parseMarkdownInlines("***both***")
        assertEquals("both", triple.plain())
        assertIs<MarkdownInline.Strong>(assertIs<MarkdownInline.Emphasis>(triple.single()).content.single())
    }

    @Test fun underscoresWithinIdentifiersRemainLiteral() {
        val source = "tenant_id and source_table_name"
        val nodes = parseMarkdownInlines(source)
        assertEquals(source, nodes.plain())
        assertTrue(nodes.all { it is MarkdownInline.Text })
    }

    @Test fun escapesAndUnmatchedFormattingRemainReadable() {
        assertEquals("*literal* [value]", parseMarkdownInlines("\\*literal\\* \\[value\\]").plain())
        for (source in listOf("**unfinished", "[unfinished", "~~unfinished", "`unfinished", "* spaced *")) {
            assertEquals(source, parseMarkdownInlines(source).plain())
        }
    }

    @Test fun codeSpanRunsKeepMarkupAndNormalizeOnlyCodeSpanWhitespace() {
        assertEquals("a ` b", assertIs<MarkdownInline.Code>(parseMarkdownInlines("``a ` b``").single()).text)
        assertEquals("**not bold** &amp;", assertIs<MarkdownInline.Code>(parseMarkdownInlines("`**not bold** &amp;`").single()).text)
        assertEquals("a b", assertIs<MarkdownInline.Code>(parseMarkdownInlines("` a\nb `").single()).text)
        assertEquals("   ", assertIs<MarkdownInline.Code>(parseMarkdownInlines("`   `").single()).text)
    }

    @Test fun hardAndSoftBreaksAreDistinct() {
        val nodes = parseMarkdownInlines("alpha  \nbeta\\\ngamma\nlast")
        assertEquals(2, nodes.count { it == MarkdownInline.HardBreak })
        assertEquals(1, nodes.count { it == MarkdownInline.SoftBreak })
        assertEquals("alpha\nbeta\ngamma\nlast", nodes.plain())
    }

    @Test fun fencesRetainNativeCodeAndDoNotCloseOnShorterRuns() {
        val code = assertIs<MarkdownBlock.Code>(parseMarkdownDocument("````json\n{\"value\": \"**literal**\"}\n```\n````").blocks.single())
        assertEquals("json", code.language)
        assertEquals("{\"value\": \"**literal**\"}\n```", code.text)
        assertEquals("SELECT 1;", assertIs<MarkdownBlock.Code>(parseMarkdownDocument("~~~sql\nSELECT 1;").blocks.single()).text)
    }

    @Test fun indentedCodeAndLineEndingsArePreserved() {
        val code = assertIs<MarkdownBlock.Code>(parseMarkdownDocument("    <xml>\r\n      nested\r\n    </xml>\r\n").blocks.single())
        assertEquals("<xml>\n  nested\n</xml>", code.text)
    }

    @Test fun nestedListsKeepOrderedStartsAndTaskMarkers() {
        val list = assertIs<MarkdownBlock.ListBlock>(parseMarkdownDocument("3. first\n   - [x] done\n   - [ ] pending\n4. second").blocks.single())
        assertEquals(3, list.start)
        assertEquals(2, list.items.size)
        val nested = assertIs<MarkdownBlock.ListBlock>(list.items.first().blocks[1])
        assertEquals(listOf(true, false), nested.items.map { it.checked })
        assertEquals("done", paragraphText(nested.items[0].blocks.single()))
    }

    @Test fun emptyListItemsAndBlankSeparatedParagraphsAreAccepted() {
        val blocks = parseMarkdownDocument("-\n- next\n\nOutside").blocks
        assertEquals(2, assertIs<MarkdownBlock.ListBlock>(blocks[0]).items.size)
        assertTrue(assertIs<MarkdownBlock.ListBlock>(blocks[0]).items.first().blocks.isEmpty())
        assertEquals("Outside", paragraphText(blocks[1]))
    }

    @Test fun blockquotesHaveNestedBlocksAndLazyParagraphContinuation() {
        val quote = assertIs<MarkdownBlock.Quote>(parseMarkdownDocument("> A quote\ncontinued\n>\n> - first\n> - second").blocks.single())
        assertEquals("A quote\ncontinued", paragraphText(quote.blocks[0]))
        assertEquals(2, assertIs<MarkdownBlock.ListBlock>(quote.blocks[1]).items.size)
    }

    @Test fun thematicBreaksAreNotLists() {
        for (source in listOf("---", "* * *", "___")) assertEquals(MarkdownBlock.Rule, parseMarkdownDocument(source).blocks.single())
    }

    @Test fun tablesRetainAlignmentEscapesAndCodePipes() {
        val table = assertIs<MarkdownBlock.Table>(parseMarkdownDocument("| Name | Value |\n| :--- | ---: |\n| a\\|b | `x|y` |").blocks.single())
        assertEquals(listOf(MarkdownAlignment.LEFT, MarkdownAlignment.RIGHT), table.alignments)
        assertEquals("a|b", table.rows.single()[0].plain())
        assertEquals("x|y", assertIs<MarkdownInline.Code>(table.rows.single()[1].single()).text)
    }

    @Test fun linksSupportBalancedDestinationsFormattingAndTitles() {
        val link = assertIs<MarkdownInline.Link>(parseMarkdownInlines("[**spec**](https://example.test/a_(b)?a=1&amp;b=2 \"Details\")").single())
        assertEquals("https://example.test/a_(b)?a=1&b=2", link.destination)
        assertEquals("Details", link.title)
        assertIs<MarkdownInline.Strong>(link.content.single())
    }

    @Test fun referencesWorkBeforeTheirDefinitionsButNotFromCodeFences() {
        val blocks = parseMarkdownDocument("[Read][SPEC]\n\n[spec]: <https://example.test> 'Title'").blocks
        val link = assertIs<MarkdownInline.Link>(assertIs<MarkdownBlock.Paragraph>(blocks.single()).content.single())
        assertEquals("https://example.test", link.destination)
        assertEquals("Title", link.title)
        val codeDefinition = parseMarkdownDocument("[Read][spec]\n\n```\n[spec]: https://example.test\n```").blocks
        assertEquals("[Read][spec]", paragraphText(codeDefinition[0]))
    }

    @Test fun referenceDiscoveryUsesTheSameQuoteListAndFenceStructure() {
        val fenced = parseMarkdownDocument("[Read][spec]\n\n- ```\n  [spec]: /wrong\n  ```").blocks
        assertEquals("[Read][spec]", paragraphText(fenced.first()))
        val quoted = parseMarkdownDocument("[Read][spec]\n\n> [spec]: /right").blocks
        assertEquals("/right", assertIs<MarkdownInline.Link>(assertIs<MarkdownBlock.Paragraph>(quoted.first()).content.single()).destination)
    }

    @Test fun unmarkedTextCannotContinueFencedCodeInsideAQuoteOrList() {
        for (source in listOf("> ```\n> inside\noutside\n> ```", "- ```\n  inside\noutside\n  ```")) {
            val blocks = parseMarkdownDocument(source).blocks
            assertTrue(blocks.any { it is MarkdownBlock.Paragraph && it.content.plain() == "outside" })
        }
    }

    @Test fun unmatchedBackticksDoNotSwallowTableCells() {
        val table = assertIs<MarkdownBlock.Table>(parseMarkdownDocument("| A | B |\n| - | - |\n| `oops | value |").blocks.single())
        assertEquals(listOf("`oops", "value"), table.rows.single().map { it.plain() })
    }

    @Test fun autolinksAndImagesAreTypedNotHtml() {
        assertEquals("https://example.test", assertIs<MarkdownInline.Link>(parseMarkdownInlines("<https://example.test>").single()).destination)
        assertEquals("mailto:a@example.test", assertIs<MarkdownInline.Link>(parseMarkdownInlines("<a@example.test>").single()).destination)
        val image = assertIs<MarkdownInline.Image>(parseMarkdownInlines("![A **chart**](https://example.test/image.png)").single())
        assertEquals("A chart", image.description.plain())
        val html = "<script>alert('x')</script>"
        assertEquals(html, parseMarkdownInlines(html).plain())
    }

    @Test fun characterReferencesAreDecodedOnceWithTheFullNamedTable() {
        assertEquals("& < \" ' © Ď ≂̸ 😀", parseMarkdownInlines("&amp; &lt; &quot; &apos; &copy; &Dcaron; &NotEqualTilde; &#x1F600;").plain())
        assertEquals("&lt; &unknown; &#xZZ;", parseMarkdownInlines("&amp;lt; &unknown; &#xZZ;").plain())
        assertEquals("� � �", parseMarkdownInlines("&#0; &#xD800; &#x110000;").plain())
    }

    @Test fun excessiveDepthAndSizeStayLiteralInsteadOfFailing() {
        val large = "**" + "x".repeat(65_536)
        assertEquals(large, assertIs<MarkdownBlock.Literal>(parseMarkdownDocument(large).blocks.single()).text)
        val nested = "> ".repeat(100) + "value"
        assertTrue(parseMarkdownDocument(nested).blocks.isNotEmpty())
        assertEquals("*".repeat(512) + "x" + "*".repeat(512), parseMarkdownInlines("*".repeat(512) + "x" + "*".repeat(512)).plain())
    }

    @Test fun manyUnmatchedAutolinkCandidatesDoNotRescanTheSameSuffix() {
        for (source in listOf("<".repeat(65_536), "<".repeat(65_535) + ">")) {
            assertEquals(source, parseMarkdownInlines(source).plain())
        }
    }

    @Test fun unmatchedCodeRunsInsideLabelsAreConsumedAtomically() {
        val source = "[" + "`".repeat(65_535)
        assertEquals(source, parseMarkdownInlines(source).plain())
    }

    @Test fun distinctUnmatchedCodeRunsShareTheirClosingRunIndex() {
        val source = "[".repeat(32) + (1..360).joinToString("x") { "`".repeat(it) }
        assertTrue(source.length <= 65_536)
        assertEquals(source, parseMarkdownInlines(source).plain())
    }

    @Test fun setextHeadingsAndTablesDoNotAcceptLazyParagraphContinuation() {
        for (source in listOf(
            "> title\n> ===\noutside",
            "- title\n  ===\noutside",
            "> A | B\n> - | -\n> first | second\noutside",
            "- A | B\n  - | -\n  first | second\noutside",
        )) {
            val blocks = parseMarkdownDocument(source).blocks
            assertTrue(blocks.any { it is MarkdownBlock.Paragraph && it.content.plain() == "outside" }, source)
        }
    }

    /** Fixed-seed malformed-input sweep also runs on JS, without a JVM-only generator dependency. */
    @Test fun malformedInputsAlwaysTerminateWithoutParserExceptions() {
        val random = Random(271828)
        val characters = "abc []()<>*_~`\\&;:#|\n\r\t0123456789"
        repeat(1_000) {
            val source = buildString { repeat(random.nextInt(128)) { append(characters[random.nextInt(characters.length)]) } }
            parseMarkdownDocument(source)
            parseMarkdownInlines(source)
        }
    }
}

private fun paragraphText(block: MarkdownBlock) = assertIs<MarkdownBlock.Paragraph>(block).content.plain()
private fun headingText(block: MarkdownBlock) = assertIs<MarkdownBlock.Heading>(block).content.plain()

private fun List<MarkdownInline>.plain(): String = joinToString("") { node -> when (node) {
    is MarkdownInline.Text -> node.text
    is MarkdownInline.Code -> node.text
    is MarkdownInline.Emphasis -> node.content.plain()
    is MarkdownInline.Strong -> node.content.plain()
    is MarkdownInline.Strike -> node.content.plain()
    is MarkdownInline.Link -> node.content.plain()
    is MarkdownInline.Image -> node.description.plain()
    MarkdownInline.HardBreak, MarkdownInline.SoftBreak -> "\n"
} }
