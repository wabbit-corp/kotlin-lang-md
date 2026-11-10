# Kotlin Markdown

This in-house Kotlin Multiplatform library contains two intentionally different APIs:

- `Markdown` builds section-oriented documents and prints them. Its existing DSL is unchanged.
- `parseMarkdownDocument(source)` reads presentation syntax into `MarkdownDocument`,
  `MarkdownBlock` and `MarkdownInline`. A UI can render that tree without interpreting HTML.

```kotlin
val document = parseMarkdownDocument("## Results\n\n**Four** rows.\n\n- Inspect `value`.")
val inline = parseMarkdownInlines("A *formatted* sentence with a [link](https://example.test).")
```

The document parser supports headings (ATX and setext), paragraphs and line breaks, nested
ordered/unordered lists, task markers, quotes, thematic breaks, fenced/indented code, pipe
tables, nested emphasis/strong/strike, code spans, links/images, autolinks and references.
Character references use the complete semicolon-terminated WHATWG named table and numeric
references. Code is kept literal. Raw HTML is ordinary text; URL navigation and image-loading
policy belong to the renderer, not the parser.

This is not a claim of complete CommonMark/GFM conformance. Unsupported/malformed syntax stays
readable; input above 65,536 UTF-16 code units and excessively nested blocks becomes an explicit
`MarkdownBlock.Literal`. Nesting is bounded at 32. Parsing does not perform I/O, call an external
parser, run scripts or change the original message. The named-reference data comes from
<https://html.spec.whatwg.org/entities.json>.

## Development

JavaScript browser/Node targets are added through the setup-managed build's supported
`build.extra.gradle.kts` extension point. The shared string input dependency has corresponding JS
actual declarations; JVM reader/channel handles remain unsupported there, as on Native.

Run the common parser cases on JVM and Node:

```sh
./gradlew jvmTest jsNodeTest --no-daemon --no-parallel --max-workers=1 \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Pwabbit.useLocalBuildOverrides=false -Pwabbit.useMavenLocalArtifacts=true
```

The existing ignored generative DSL round-trip test remains JVM-only because its generator has
no JS variant. The executable document/malformed-input tests run on both platforms. The new
browser artifacts are currently development-local; publishing requires a new character-input
release with JS metadata and publication of this Markdown module. Do not treat a local Maven
installation as proof that normal Maven Central consumers can resolve the new targets.
