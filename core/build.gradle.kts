plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // Publishes the generated fixture corpus so :app's tests use the same books
    // rather than a second, subtly different set.
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.jsoup)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testFixturesImplementation(libs.pdfbox)
    testFixturesImplementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.pdfbox)          // test-only: see plan Global Constraints
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

/**
 * Writes the shared fixture corpus to a directory other modules can package.
 *
 * Lives here because Gradle 9 forbids resolving another project's configurations,
 * and :core owns the testFixtures runtime classpath this needs.
 */
val fixtureAssetsDir: java.io.File = layout.buildDirectory.get().asFile
    .resolve("generated/fixtureAssets")

val generateFixtureAssets by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Generates the test book corpus for instrumented tests."
    dependsOn(tasks.named("testFixturesClasses"))
    classpath = sourceSets["testFixtures"].runtimeClasspath
    mainClass.set("app.folio.core.fixtures.FixtureCli")
    argumentProviders.add { listOf(fixtureAssetsDir.absolutePath) }
    outputs.dir(fixtureAssetsDir)
}
