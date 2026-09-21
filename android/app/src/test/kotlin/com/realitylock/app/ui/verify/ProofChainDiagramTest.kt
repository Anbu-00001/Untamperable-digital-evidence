package com.realitylock.app.ui.verify

import com.realitylock.app.verify.VerificationReport
import com.realitylock.app.verify.VerificationReport.Check
import com.realitylock.app.verify.VerificationReport.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The diagram is only honest if every link's colour comes from a real check.
 * These pin the mapping, and the two ways it could flatter a package: a missing
 * check drawn as tested, and a failing attestation hidden behind passing siblings.
 */
class ProofChainDiagramTest {

    private fun report(vararg checks: Pair<String, Outcome>) = VerificationReport(
        verdict = VerificationReport.Verdict.INCOMPLETE,
        checks = checks.map { (name, outcome) -> Check(name, outcome) },
        notes = emptyList(),
        advisories = emptyList(),
        limitations = emptyList(),
    )

    @Test
    fun `each link takes the outcome of the check that tested it`() {
        val links = proofChainLinks(
            report(
                "mediaHashMatch" to Outcome.FAIL,
                "metadataHashMatch" to Outcome.PASS,
                "merkleRootMatch" to Outcome.PASS,
                "signatureValid" to Outcome.PASS,
                "attestationPresent" to Outcome.PASS,
            ),
        )
        assertEquals(
            listOf(Outcome.FAIL, Outcome.PASS, Outcome.PASS, Outcome.PASS, Outcome.PASS),
            links.map { it.outcome },
        )
        assertEquals(PROOF_CHAIN_REVEAL_STEPS, links.size)
    }

    @Test
    fun `a link with no reported check is drawn as not checkable, never as passed`() {
        val links = proofChainLinks(report("signatureValid" to Outcome.PASS))
        assertEquals(Outcome.UNAVAILABLE, links.first { it.label == "Photo" }.outcome)
        assertEquals(Outcome.UNAVAILABLE, links.first { it.label == "Key" }.outcome)
    }

    @Test
    fun `the hardware link shows the worst attestation outcome`() {
        val links = proofChainLinks(
            report(
                "attestationPresent" to Outcome.PASS,
                "attestationChainValid" to Outcome.PASS,
                "attestationRootTrusted" to Outcome.FAIL,
                "attestationNotRevoked" to Outcome.UNAVAILABLE,
            ),
        )
        assertEquals(Outcome.FAIL, links.first { it.label == "Key" }.outcome)
    }
}
