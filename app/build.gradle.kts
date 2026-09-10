plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.folio.android"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "app.folio.android"
        minSdk = 26
        // compileSdk is 37 because Compose requires it. targetSdk stays at 36
        // because Robolectric 4.16 emulates no higher, and JVM-side Android tests
        // are worth more than targeting the very newest API.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    // :core exposes @Serializable model types, so :app needs the runtime too.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.pdfbox.android)
    implementation(libs.mlkit.text.recognition)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // Robolectric is JUnit 4 only, so :app runs JUnit 4 while :core runs JUnit 5.
    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    // The same fixture corpus :core is tested against. Fixtures are authored with
    // Apache PDFBox and read back with PdfBox-Android, so the writer and the reader
    // under test are independent implementations.
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.pdfbox)
}
