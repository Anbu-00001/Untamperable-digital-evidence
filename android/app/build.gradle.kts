import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // KSP/Room are deferred to Phase 5 — see docs/design/adr/ADR-0003-local-event-store.md
    // alias(libs.plugins.ksp)
}

// --------------------------------------------------------------------------
// Config resolution — layered, no hardcoding:
//   1. local.properties  (gitignored, per-developer / secret overrides)
//   2. gradle.properties (committed dev defaults)
//   3. a safe literal fallback (last resort only)
// Feature code never sees these literals; it reads BuildConfig via AppConfig.
// --------------------------------------------------------------------------
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(key: String, fallback: String): String =
    localProperties.getProperty(key)
        ?: (project.findProperty(key) as String?)
        ?: fallback

android {
    namespace = "com.realitylock.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.realitylock.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Runtime config injected as typed BuildConfig fields (see AppConfig.kt).
        buildConfigField(
            "String",
            "BACKEND_BASE_URL",
            "\"${cfg("REALITYLOCK_BACKEND_BASE_URL", "http://10.0.2.2:3000/")}\""
        )
        buildConfigField(
            "long",
            "PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER",
            "${cfg("REALITYLOCK_PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER", "0")}L"
        )
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Code shrinking/obfuscation is turned on during Phase 6 hardening.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Source lives under src/main/kotlin (registered explicitly since Android's
    // default Kotlin source root is src/main/java).
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // ---- Core / lifecycle (Phase 1) ----
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    // ---- Compose UI (Phase 1) ----
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ---- Capture pipeline (Phase 2) ----
    implementation(libs.bundles.camerax)
    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.play.services)

    // ---- Local queue (Phase 2) ----
    // Implemented as a dependency-free JSON sidecar store; Room + KSP are
    // deferred to Phase 5. See docs/design/adr/ADR-0003-local-event-store.md.
    // implementation(libs.androidx.room.runtime)
    // implementation(libs.androidx.room.ktx)
    // ksp(libs.androidx.room.compiler)

    // ---- Background sync (Phase 5) ----
    // implementation(libs.androidx.work.runtime.ktx)

    // ---- Security / integrity (Phase 3 / Phase 4) ----
    // implementation(libs.tink.android)
    // implementation(libs.play.integrity)
    // implementation(libs.json.canonicalization)
    // implementation(libs.androidx.exifinterface)

    // ---- Networking (Phase 5) ----
    // implementation(libs.retrofit.core)
    // implementation(libs.retrofit.converter.gson)
    // implementation(libs.okhttp.core)
    // implementation(libs.okhttp.logging.interceptor)

    // ---- Verification UI (Phase 5) ----
    // implementation(libs.zxing.android.embedded)

    // ---- Testing ----
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    // Production uses Android's built-in org.json; android.jar's stub throws in
    // unit tests, so the real implementation is substituted on the test classpath.
    testImplementation(libs.org.json)
    // Robolectric is deferred to Phase 6, where the sensor/location tests that
    // need a simulated Android framework are written. It drags in very large
    // `android-all` artifacts, so keeping it off the test classpath until then
    // keeps the unit-test feedback loop fast.
    // testImplementation(libs.robolectric)
    // testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
