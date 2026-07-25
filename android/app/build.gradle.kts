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

// Package identity, declared once. `namespace` (R-class package) and
// `applicationId` (Play Store identity) are the same value today but remain
// separately assignable, since they are allowed to diverge later.
val appPackage = "com.realitylock.app"

// JVM level for javac and kotlinc, from the version catalog (one source).
val javaVersion = JavaVersion.toVersion(libs.versions.javaVersion.get())

android {
    namespace = appPackage
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = appPackage
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.appVersionCode.get().toInt()
        versionName = libs.versions.appVersionName.get()

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
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Source lives under src/main/kotlin (registered explicitly since Android's
    // default Kotlin source root is src/main/java).
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
    sourceSets["androidTest"].kotlin.srcDir("src/androidTest/kotlin")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString()))
    }
}

// Unit tests validate real serializer output against the *actual* shared schema
// file, so the Android producer cannot drift from the backend verifier. The path
// is passed in rather than hardcoded in the test, and resolved through the same
// layered `cfg()` chain as every other setting.
tasks.withType<Test>().configureEach {
    systemProperty(
        "realitylock.proofSchemaPath",
        rootProject.file(cfg("REALITYLOCK_PROOF_SCHEMA_PATH", "../docs/design/proof-package.schema.json"))
            .absolutePath,
    )
    systemProperty(
        "realitylock.proofExamplePath",
        rootProject.file(
            cfg("REALITYLOCK_PROOF_EXAMPLE_PATH", "../docs/design/examples/proof-package.example.json")
        ).absolutePath,
    )
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
    // A dependency-free JSON sidecar store, and it STAYS that way: ADR-0006 §3
    // keeps it for Phase 5 because sync state is the only mutable data, and
    // holding it in a separate `<eventId>.sync.json` leaves the signed package
    // file write-once. Room would also mean re-adding the KSP plugin that
    // ADR-0003 removed for build time. See also ADR-0003.
    // implementation(libs.androidx.room.runtime)
    // implementation(libs.androidx.room.ktx)
    // ksp(libs.androidx.room.compiler)

    // ---- Background sync (Phase 5) ----
    implementation(libs.androidx.work.runtime.ktx)

    // ---- Security / integrity (Phase 3 / Phase 4) ----
    // RFC 8785 (JCS) canonicalization, so logically-identical metadata always
    // hashes identically on producer and verifier.
    implementation(libs.json.canonicalization)
    // Tink is NOT used: signing happens with a key that never leaves the Android
    // Keystore, and java.security.Signature is the supported API for those.
    // Tink's value is safe key *handling*, which is moot when the key is
    // non-exportable hardware-resident. See ADR-0004.
    // implementation(libs.tink.android)
    // Play Integrity is a Phase-7 stretch, not a dependency — see ADR-0004.
    // implementation(libs.play.integrity)
    // EXIF-consistency forensic checks on candidate images (Phase 4).
    implementation(libs.androidx.exifinterface)

    // ---- Networking (Phase 5) ----
    // OkHttp alone, no Retrofit and no JSON converter. The proof package is a
    // signed document: it is forwarded as the exact bytes that were stored, never
    // parsed into objects and re-serialized, because a change in number
    // formatting or escaping would break the metadata hash and look precisely
    // like tampering (ADR-0006 §2).
    implementation(libs.okhttp.core)
    debugImplementation(libs.okhttp.logging.interceptor)

    // ---- Verification UI (Phase 5) ----
    // zxing CORE only — Phase 5 generates QR codes, it does not scan them, so the
    // journeyapps scanner Activity (and its camera permission) is not a
    // dependency. See ADR-0006 §4.
    implementation(libs.zxing.core)

    // ---- Testing ----
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    // Production uses Android's built-in org.json; android.jar's stub throws in
    // unit tests, so the real implementation is substituted on the test classpath.
    testImplementation(libs.org.json)
    // Validates serializer output against the real proof-package schema.
    testImplementation(libs.json.schema.validator)
    // A real HTTP server on localhost, so the sync client is tested against
    // actual sockets, status codes and request bodies rather than a mocked
    // interface that would agree with whatever the client happened to send.
    testImplementation(libs.okhttp.mockwebserver)
    // Robolectric is deferred to Phase 6, where the sensor/location tests that
    // need a simulated Android framework are written. It drags in very large
    // `android-all` artifacts, so keeping it off the test classpath until then
    // keeps the unit-test feedback loop fast.
    // testImplementation(libs.robolectric)
    // testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // GrantPermissionRule — the in-test permission grant. It matters here because
    // this project's target device refuses `adb shell pm grant` outright, so if
    // this route did not work either, no instrumented test could ever exercise
    // the camera or location paths.
    androidTestImplementation(libs.androidx.test.rules)
}
