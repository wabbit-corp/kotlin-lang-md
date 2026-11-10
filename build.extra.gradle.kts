import org.gradle.kotlin.dsl.withGroovyBuilder

// Browser consumers use the same pure parser; no JavaScript Markdown implementation is bundled.
extensions.getByName("kotlin").withGroovyBuilder {
    "js" {
        "browser"()
        "nodejs"()
    }
}

dependencies {
    add("commonMainImplementation", "one.wabbit:kotlin-parsing-charinput:2.0.0")
    add("jvmTestImplementation", "one.wabbit:kotlin-random-gen:3.0.0")
}

// The existing ignored generative test is JVM-only; the executable parser cases also run on JS.
configurations.named("commonTestImplementation") {
    dependencies.removeAll { it.group == "one.wabbit" && it.name == "kotlin-random-gen" }
}
