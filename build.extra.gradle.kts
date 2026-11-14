import org.gradle.kotlin.dsl.withGroovyBuilder

// Browser consumers use the same pure parser; no JavaScript Markdown implementation is bundled.
extensions.getByName("kotlin").withGroovyBuilder {
    "js" {
        "browser"()
        "nodejs"()
    }
}
