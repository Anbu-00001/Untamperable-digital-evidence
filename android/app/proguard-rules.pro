# Reality Lock — ProGuard/R8 rules.
#
# Intentionally empty, and that is a finding rather than an oversight.
#
# R8 was enabled in Phase 6 (`isMinifyEnabled = true`) and the build was then
# verified end-to-end on real hardware: a capture made by the minified build was
# pulled off the device and run through the backend's own verifier, which
# returned `verdict: verified` with mediaHashMatch, metadataHashMatch,
# merkleRootMatch, signatureValid, attestationPresent, attestationChainValid and
# attestationKeyBinding all passing — structurally identical to a pre-R8 baseline
# captured on the same device. No rule was needed to get there.
#
# The reason nothing is required: this app has no reflective model binding. There
# is no Gson/Moshi/Retrofit converter reflecting over data classes, no Room (see
# ADR-0003), and no Tink (see ADR-0004). Proof packages are built field by field
# against org.json, so the field names R8 cannot see are string literals rather
# than class members. The two classes the framework *does* instantiate by name —
# `RealityLockApplication`/`MainActivity` (from the manifest) and `SyncWorker`
# (by WorkManager) — are kept by AGP's manifest handling and androidx.work's own
# consumer rules respectively; all three were confirmed present and unrenamed in
# the release mapping.
#
# Do NOT add speculative "just in case" keep rules here. Broad rules
# (e.g. `-keep class com.realitylock.app.** { *; }`) would silently disable the
# shrinking this file exists to make safe — the minified APK is 4.4 MB against
# 29 MB unminified.
#
# If a future change introduces reflection — a JSON binding library, a
# dynamically loaded class, an enum resolved via valueOf() from a string — add
# the narrowest rule that covers it, and record here what broke without it.
#
# NOTE on crash reports: R8 obfuscates, so a stack trace from a release build is
# unreadable without `app/build/outputs/mapping/release/mapping.txt` from the
# exact build that produced the APK. That file lives under build/ and is not
# committed; archive it alongside any APK that is distributed.
