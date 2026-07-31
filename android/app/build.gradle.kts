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
//   1. -P on the command line (explicit, this invocation only)
//   2. local.properties  (gitignored, per-developer / secret overrides)
//   3. gradle.properties (committed dev defaults)
//   4. a safe literal fallback (last resort only)
// Feature code never sees these literals; it reads BuildConfig via AppConfig.
//
// Tier 1 reads `startParameter.projectProperties` rather than `findProperty`,
// and the distinction is the whole point: `findProperty` also returns
// gradle.properties values, so consulting it first would let a committed default
// shadow a developer's local.properties — which is exactly why the original
// ordering put local.properties first and had no -P tier at all.
//
// That omission was a real defect, not a style choice. `run_sync_e2e.sh` starts
// the backend on a free port and passes `-PREALITYLOCK_BACKEND_BASE_URL` to point
// the app at it; local.properties won, so the app always called whatever port was
// written there. The run only ever passed when that port happened to match — the
// dynamic-port support had never actually worked, and the failure surfaced as
// "the capture did not sync", which reads as a broken sync engine rather than a
// build-config override that was silently discarded.
// --------------------------------------------------------------------------
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(key: String, fallback: String): String =
    gradle.startParameter.projectProperties[key]
        ?: localProperties.getProperty(key)
        ?: (project.findProperty(key) as String?)
        ?: fallback

// Same precedence as [cfg], but with no literal fallback — for release-signing
// secrets, where there is no safe default to fall back to. Returns null when
// unset, so the caller can leave the release build genuinely unsigned rather
// than silently substituting something (see `signingConfigs` below).
fun cfgOrNull(key: String): String? =
    gradle.startParameter.projectProperties[key]
        ?: localProperties.getProperty(key)
        ?: (project.findProperty(key) as String?)

// Package identity, declared once. `namespace` (R-class package) and
// `applicationId` (Play Store identity) are the same value today but remain
// separately assignable, since they are allowed to diverge later.
val appPackage = "com.realitylock.app"

// JVM level for javac and kotlinc, from the version catalog (one source).
val javaVersion = JavaVersion.toVersion(libs.versions.javaVersion.get())

android {
    namespace = appPackage
    compileSdk = libs.versions.compileSdk.get().toInt()

    signingConfigs {
        // Deliberately not created at all unless every one of the four values
        // below is present — a partially-configured signing config, or worse,
        // a release build silently falling back to Android's default debug
        // signing, would be exactly the kind of silent-wrong-behavior this
        // project's own verifier logic refuses to allow itself elsewhere. If
        // these are unset, `release` below simply has no signing config, and
        // Android's own tooling refuses to install the result — a loud,
        // honest failure instead of a quiet one.
        //
        // The four keys go in the developer's own gitignored local.properties,
        // never in gradle.properties or committed anywhere — see
        // local.properties.example.
        val storeFilePath = cfgOrNull("REALITYLOCK_RELEASE_STORE_FILE")
        val storePw = cfgOrNull("REALITYLOCK_RELEASE_STORE_PASSWORD")
        val alias = cfgOrNull("REALITYLOCK_RELEASE_KEY_ALIAS")
        val keyPw = cfgOrNull("REALITYLOCK_RELEASE_KEY_PASSWORD")
        if (storeFilePath != null && storePw != null && alias != null && keyPw != null) {
            create("release") {
                storeFile = rootProject.file(storeFilePath)
                storePassword = storePw
                keyAlias = alias
                keyPassword = keyPw
            }
        }
    }

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
            // R8 shrinking/obfuscation, enabled in Phase 6. Anything it needed
            // kept is in proguard-rules.pro with the reason it is there — the
            // rules were derived from an actual build-and-run, not guessed at
            // up front, so the file records real constraints rather than
            // defensive copy-paste that would quietly disable shrinking.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
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

    testOptions {
        unitTests {
            // Robolectric resolves resources/manifest through the merged build
            // output; without this it starts and then fails on resource access,
            // which reads as a test bug rather than a missing build flag.
            isIncludeAndroidResources = true
        }
    }
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
    testImplementation(libs.kotlinx.coroutines.test)
    // Production uses Android's built-in org.json; android.jar's stub throws in
    // unit tests, so the real implementation is substituted on the test classpath.
    testImplementation(libs.org.json)
    // Validates serializer output against the real proof-package schema.
    testImplementation(libs.json.schema.validator)
    // A real HTTP server on localhost, so the sync client is tested against
    // actual sockets, status codes and request bodies rather than a mocked
    // interface that would agree with whatever the client happened to send.
    testImplementation(libs.okhttp.mockwebserver)
    // Robolectric (Phase 6). Enabled here for the framework-dependent paths that
    // no pure-JVM test can reach: the collector's `registerListener` →
    // `onSensorChanged` → buffer path, and — the reason that actually justifies
    // the dependency — `LocationSource.isMockCompat()`'s pre-API-31 branch. That
    // branch is a *security* check (mock-location detection) which the project's
    // only test device, an API 35 phone, can never execute. Robolectric runs it
    // at the minSdk it was written for.
    //
    // It does drag in large `android-all` artifacts, which is why it was kept off
    // the classpath until there were tests that genuinely needed it.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // GrantPermissionRule — the in-test permission grant. It matters here because
    // this project's target device refuses `adb shell pm grant` outright, so if
    // this route did not work either, no instrumented test could ever exercise
    // the camera or location paths.
    androidTestImplementation(libs.androidx.test.rules)
    // Compose UI tests (Phase 6). The BOM is applied to the androidTest classpath
    // as well, so the test artifacts resolve to the same Compose version the app
    // is built against rather than whatever the test library defaults to.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
