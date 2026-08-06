package com.realitylock.app.ui.verify

import com.realitylock.app.verify.VerificationReport
import com.realitylock.app.verify.VerificationReport.Check
import com.realitylock.app.verify.VerificationReport.Outcome

/**
 * Grouping of the verifier's per-check results for display.
 *
 * ## Why group at all
 *
 * Thirteen flat rows is a wall, and a wall is skimmed. Grouping restores a shape a
 * reader can hold — *integrity*, *signature*, *attestation*, *context* — without
 * throwing anything away: every check the backend reported is still present inside
 * its group, and a check this app version has never heard of still lands in a group
 * ([CheckGroupId.OTHER]) rather than being dropped.
 *
 * ## Why the group's state is the worst state inside it
 *
 * A group heading is the thing a reader actually reads. If a heading could show
 * "pass" while a check underneath it failed, the summary would be a lie about
 * evidence — the exact failure mode this whole screen exists to avoid. So a group's
 * state is always the **worst** state it contains, ranked
 * `FAIL > UNKNOWN > UNAVAILABLE > PASS` ([severity]). One failing attestation check
 * makes the Attestation group red, no matter how many of its siblings passed.
 *
 * `UNKNOWN` outranks `UNAVAILABLE` and `PASS` on purpose: "the backend reported a
 * check we cannot interpret" is a bigger hole in the reader's knowledge than "a
 * check could not be run", because we do not even know what was being claimed.
 *
 * Ranking alone would still be too coarse — "red" does not say whether one check or
 * all six failed — so every group also carries a literal [CheckGroup.fraction]
 * ("5/6") and a [CheckGroup.breakdown] sentence. Colour says *the worst thing in
 * here*; the fraction says *how much of it passed*. Neither is allowed to stand
 * alone.
 *
 * ## Why the copy lives in Kotlin and not `strings.xml`
 *
 * Group titles, per-check explanations and the outcome sentences are declared here
 * as constants rather than as string resources. That keeps this file the single
 * place where "what the UI asserts about a check" is written down, and it makes the
 * wording assertable from a plain JVM unit test with no Robolectric resource
 * plumbing. The pre-existing per-check *labels* remain resource-backed
 * (`R.string.check_*`); this table supplies the sentence underneath them, plus
 * labels for the checks a newer backend added that have no resource yet.
 */
enum class CheckGroupId(val title: String, val summary: String) {
    INTEGRITY(
        title = "Integrity",
        summary = "Whether the bytes and their record still match what was captured.",
    ),
    SIGNATURE(
        title = "Signature",
        summary = "Whether the package was signed by the key it names.",
    ),
    ATTESTATION(
        title = "Attestation",
        summary = "Hardware evidence that the signing key lived in this device's secure element.",
    ),
    CONTEXT(
        title = "Context",
        summary = "Whether the recorded time and place are plausible. Plausible is not proof.",
    ),
    OTHER(
        title = "Other checks",
        summary = "Reported by the backend and shown as received; this app version does not " +
            "know where they belong.",
    ),
}

/**
 * One display group: its checks, and the state derived from them.
 *
 * [state], [passed] and [total] are computed rather than passed in so that no
 * caller can construct a group whose headline disagrees with its contents.
 */
data class CheckGroup(val id: CheckGroupId, val checks: List<Check>) {

    /** The worst outcome in the group — see the ranking note on [CheckGroupId]. */
    val state: Outcome = checks.maxByOrNull { it.outcome.severity() }?.outcome ?: Outcome.UNAVAILABLE

    val passed: Int = checks.count { it.outcome == Outcome.PASS }

    val total: Int = checks.size

    /** Always rendered next to the state, so a group is never a single word. */
    val fraction: String = "$passed/$total"

    /**
     * Per-state counts spelled out — "4 passed · 1 failed". Built from the states
     * actually present so a clean group does not read as a list of zeroes.
     */
    val breakdown: String
        get() = Outcome.entries
            .sortedByDescending { it.severity() }
            .mapNotNull { outcome ->
                val n = checks.count { it.outcome == outcome }
                if (n == 0) null else "$n ${outcome.countWord()}"
            }
            .joinToString(" · ")
}

/**
 * Ranks outcomes by how much attention they demand.
 *
 * `UNAVAILABLE` sits *above* `PASS` but *below* `FAIL` deliberately: a check that
 * could not be run is a hole, not an accusation (ADR-0006 §5). Folding it into
 * either neighbour would turn absence of evidence into evidence.
 */
internal fun Outcome.severity(): Int = when (this) {
    Outcome.PASS -> 0
    Outcome.UNAVAILABLE -> 1
    Outcome.UNKNOWN -> 2
    Outcome.FAIL -> 3
}

/** The plural-safe word used in [CheckGroup.breakdown]. */
private fun Outcome.countWord(): String = when (this) {
    Outcome.PASS -> "passed"
    Outcome.FAIL -> "failed"
    Outcome.UNAVAILABLE -> "not checkable"
    Outcome.UNKNOWN -> "unrecognised"
}

private val GROUP_BY_CHECK: Map<String, CheckGroupId> = mapOf(
    "schemaValid" to CheckGroupId.INTEGRITY,
    "mediaHashMatch" to CheckGroupId.INTEGRITY,
    "metadataHashMatch" to CheckGroupId.INTEGRITY,
    "merkleRootMatch" to CheckGroupId.INTEGRITY,
    "signatureValid" to CheckGroupId.SIGNATURE,
    "attestationPresent" to CheckGroupId.ATTESTATION,
    "attestationChainValid" to CheckGroupId.ATTESTATION,
    "attestationKeyBinding" to CheckGroupId.ATTESTATION,
    "attestationRootTrusted" to CheckGroupId.ATTESTATION,
    "attestationNotRevoked" to CheckGroupId.ATTESTATION,
    "attestationSecurityLevel" to CheckGroupId.ATTESTATION,
    "timestampPlausible" to CheckGroupId.CONTEXT,
    "locationPlausible" to CheckGroupId.CONTEXT,
)

/**
 * Which group a check name belongs to.
 *
 * The `attestation` prefix is honoured as a fallback so that an attestation check
 * added by a newer backend lands with its siblings — and, more importantly, so it
 * is counted when the Attestation group's worst-state is computed. An unknown name
 * that is not attestation-shaped goes to [CheckGroupId.OTHER]; it is never dropped.
 */
fun groupIdFor(name: String): CheckGroupId = GROUP_BY_CHECK[name]
    ?: if (name.startsWith("attestation")) CheckGroupId.ATTESTATION else CheckGroupId.OTHER

/**
 * Splits a report's checks into display groups, in [CheckGroupId] declaration order,
 * each group keeping [VerificationReport.DISPLAY_ORDER] internally.
 *
 * Empty groups are omitted — a heading with nothing under it says nothing — but no
 * *check* is ever omitted: `groupChecks(x).sumOf { it.total } == x.size` holds for
 * any input, which is the property that keeps a newer backend's extra check visible.
 */
fun groupChecks(checks: List<Check>): List<CheckGroup> {
    val byGroup = VerificationReport.sortForDisplay(checks).groupBy { groupIdFor(it.name) }
    return CheckGroupId.entries.mapNotNull { id ->
        byGroup[id]?.takeIf { it.isNotEmpty() }?.let { CheckGroup(id, it) }
    }
}

/**
 * What each check actually establishes, in one sentence.
 *
 * Written so the sentence is true regardless of the outcome — it says what the
 * check *tests*, not what it found. The finding is carried separately by
 * [outcomeSentence], which is what keeps "could not run" and "proved a problem"
 * from ever sharing wording.
 */
private val CHECK_DETAIL: Map<String, String> = mapOf(
    "schemaValid" to
        "Every field the proof-package schema requires is present and well formed.",
    "mediaHashMatch" to
        "The stored media hashes to the digest recorded at capture, so the bytes are unchanged.",
    "metadataHashMatch" to
        "The metadata hashes to the digest recorded at capture, so the record is unchanged.",
    "merkleRootMatch" to
        "The individual item hashes combine to the Merkle root this package publishes.",
    "signatureValid" to
        "The signature over the Merkle root verifies against the public key in the package.",
    "attestationPresent" to
        "The package carries a hardware key-attestation certificate chain.",
    "attestationChainValid" to
        "Each certificate in the attestation chain is signed by the next one up.",
    "attestationKeyBinding" to
        "The attested key is the same key that signed this package.",
    "attestationRootTrusted" to
        "The chain terminates at a pinned Google hardware-attestation root.",
    "attestationNotRevoked" to
        "No certificate in the chain appears on the vendor's revocation list.",
    "attestationSecurityLevel" to
        "The key was generated inside the TEE or StrongBox rather than in software.",
    "timestampPlausible" to
        "The recorded capture time agrees with the device's clock evidence.",
    "locationPlausible" to
        "The recorded location is self-consistent and did not come from a mock provider.",
)

/** The explanatory sentence for a check, or null when this app version has none. */
fun checkDetail(name: String): String? = CHECK_DETAIL[name]

/**
 * Labels for checks that have no `R.string.check_*` resource yet.
 *
 * `strings.xml` is owned elsewhere, and the attestation work landed three checks
 * ahead of it. Falling through to the raw camelCase key would still be *visible*,
 * which is the hard requirement, but it would read as debug output; these give the
 * same three checks a sentence-case label until resources catch up.
 */
private val FALLBACK_CHECK_LABEL: Map<String, String> = mapOf(
    "attestationRootTrusted" to "Attestation root trusted",
    "attestationNotRevoked" to "Attestation not revoked",
    "attestationSecurityLevel" to "Attested in secure hardware",
)

/** A label for a check with no resource, or null to fall back to [humaniseCheckName]. */
fun fallbackCheckLabel(name: String): String? = FALLBACK_CHECK_LABEL[name]

/**
 * Turns `attestationFooBar` into `Attestation foo bar` for a check name this app
 * version has never seen. Cosmetic only — the raw key is still displayed verbatim
 * next to it, because the key is the thing a reader would have to grep for.
 */
fun humaniseCheckName(name: String): String {
    val spaced = name.replace(CAMEL_BOUNDARY, " ").lowercase()
    return spaced.replaceFirstChar { it.uppercaseChar() }
}

private val CAMEL_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")

/**
 * The sentence that states the *finding*, kept separate from what the check tests.
 *
 * `UNAVAILABLE` and `FAIL` are worded to be unmistakable for one another, and
 * `UNKNOWN` says outright that the gap is in this app, not in the package.
 */
fun outcomeSentence(outcome: Outcome): String? = when (outcome) {
    Outcome.PASS -> null
    Outcome.FAIL -> "This check proved a problem."
    Outcome.UNAVAILABLE ->
        "This check could not be run. That is not a finding against the package."
    Outcome.UNKNOWN ->
        "Reported by the backend; this app version does not recognise this check."
}
