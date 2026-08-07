package com.realitylock.app.ui.verify

import com.realitylock.app.verify.VerificationReport
import com.realitylock.app.verify.VerificationReport.Check
import com.realitylock.app.verify.VerificationReport.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grouping rules behind the verification panel's headline.
 *
 * These are the assertions that stop the summary from reading better than the
 * evidence it summarises: a group takes the **worst** state inside it, an
 * unrecognised outcome is never quietly treated as a pass, and no check the backend
 * reported can be lost on the way to the screen.
 */
class CheckGroupsTest {

    private fun checks(vararg pairs: Pair<String, Outcome>) =
        pairs.map { (name, outcome) -> Check(name, outcome) }

    private fun group(id: CheckGroupId, groups: List<CheckGroup>) =
        groups.single { it.id == id }

    @Test
    fun `one failing attestation check makes the whole attestation group fail`() {
        val groups = groupChecks(
            checks(
                "attestationPresent" to Outcome.PASS,
                "attestationChainValid" to Outcome.PASS,
                "attestationKeyBinding" to Outcome.PASS,
                "attestationRootTrusted" to Outcome.PASS,
                "attestationNotRevoked" to Outcome.FAIL,
                "attestationSecurityLevel" to Outcome.PASS,
            ),
        )

        val attestation = group(CheckGroupId.ATTESTATION, groups)
        assertEquals(1, groups.size)
        assertEquals(6, attestation.total)
        assertEquals(Outcome.FAIL, attestation.state)
        // Colour alone would say "attestation is broken"; the fraction says how
        // much of it actually passed, and both must be shown.
        assertEquals("5/6", attestation.fraction)
    }

    @Test
    fun `an unrecognised outcome outranks pass and unavailable but not fail`() {
        val withUnknown = groupChecks(
            checks(
                "schemaValid" to Outcome.PASS,
                "mediaHashMatch" to Outcome.PASS,
                "metadataHashMatch" to Outcome.UNAVAILABLE,
                "merkleRootMatch" to Outcome.UNKNOWN,
            ),
        )
        assertEquals(Outcome.UNKNOWN, group(CheckGroupId.INTEGRITY, withUnknown).state)

        val withFail = groupChecks(
            checks(
                "schemaValid" to Outcome.UNKNOWN,
                "mediaHashMatch" to Outcome.FAIL,
            ),
        )
        assertEquals(Outcome.FAIL, group(CheckGroupId.INTEGRITY, withFail).state)
    }

    @Test
    fun `an unrunnable check outranks pass, so a group with a hole is never green`() {
        val groups = groupChecks(
            checks(
                "timestampPlausible" to Outcome.PASS,
                "locationPlausible" to Outcome.UNAVAILABLE,
            ),
        )
        val context = group(CheckGroupId.CONTEXT, groups)
        assertEquals(Outcome.UNAVAILABLE, context.state)
        assertEquals("1/2", context.fraction)
    }

    @Test
    fun `an all-passing group is the only way to reach pass`() {
        val groups = groupChecks(
            checks(
                "signatureValid" to Outcome.PASS,
            ),
        )
        val signature = group(CheckGroupId.SIGNATURE, groups)
        assertEquals(Outcome.PASS, signature.state)
        assertEquals("1/1", signature.fraction)
    }

    @Test
    fun `a check name this app version does not know is still grouped and shown`() {
        val groups = groupChecks(
            checks(
                "signatureValid" to Outcome.PASS,
                "quantumResistance" to Outcome.UNKNOWN,
            ),
        )

        val other = group(CheckGroupId.OTHER, groups)
        assertEquals(listOf("quantumResistance"), other.checks.map { it.name })
        assertEquals(Outcome.UNKNOWN, other.state)
        assertEquals("Quantum resistance", humaniseCheckName("quantumResistance"))
    }

    @Test
    fun `a newer attestation check joins the attestation group and can fail it`() {
        val groups = groupChecks(
            checks(
                "attestationPresent" to Outcome.PASS,
                "attestationBootStateVerified" to Outcome.FAIL,
            ),
        )

        val attestation = group(CheckGroupId.ATTESTATION, groups)
        assertEquals(2, attestation.total)
        assertEquals(Outcome.FAIL, attestation.state)
        assertEquals(CheckGroupId.ATTESTATION, groupIdFor("attestationBootStateVerified"))
    }

    @Test
    fun `every check in the display order survives grouping`() {
        val all = VerificationReport.DISPLAY_ORDER.map { Check(it, Outcome.PASS) }
        val groups = groupChecks(all)

        assertEquals(all.size, groups.sumOf { it.total })
        assertEquals(
            VerificationReport.DISPLAY_ORDER.toSet(),
            groups.flatMap { g -> g.checks.map { it.name } }.toSet(),
        )
        // Four groups, not five: nothing in the shipped set falls through to OTHER.
        assertEquals(
            listOf(
                CheckGroupId.INTEGRITY,
                CheckGroupId.SIGNATURE,
                CheckGroupId.ATTESTATION,
                CheckGroupId.CONTEXT,
            ),
            groups.map { it.id },
        )
    }

    @Test
    fun `checks keep the spec display order inside their group`() {
        val groups = groupChecks(
            checks(
                "merkleRootMatch" to Outcome.PASS,
                "schemaValid" to Outcome.PASS,
                "mediaHashMatch" to Outcome.PASS,
            ),
        )
        assertEquals(
            listOf("schemaValid", "mediaHashMatch", "merkleRootMatch"),
            group(CheckGroupId.INTEGRITY, groups).checks.map { it.name },
        )
    }

    @Test
    fun `the breakdown spells out every state present, worst first`() {
        val groups = groupChecks(
            checks(
                "schemaValid" to Outcome.PASS,
                "mediaHashMatch" to Outcome.FAIL,
                "metadataHashMatch" to Outcome.UNAVAILABLE,
                "merkleRootMatch" to Outcome.UNKNOWN,
            ),
        )
        assertEquals(
            "1 failed · 1 unrecognised · 1 not checkable · 1 passed",
            group(CheckGroupId.INTEGRITY, groups).breakdown,
        )
    }

    @Test
    fun `unavailable and fail never share wording`() {
        val unavailable = outcomeSentence(Outcome.UNAVAILABLE)
        val fail = outcomeSentence(Outcome.FAIL)
        assertNotNull(unavailable)
        assertNotNull(fail)
        assertTrue(unavailable!!.contains("could not be run"))
        assertTrue(unavailable.contains("not a finding"))
        assertTrue(fail!!.contains("proved a problem"))
    }

    @Test
    fun `an unrecognised outcome says the gap is in this app, not the package`() {
        val sentence = outcomeSentence(Outcome.UNKNOWN)
        assertNotNull(sentence)
        assertTrue(sentence!!.contains("does not recognise this check"))
        // A pass needs no finding sentence -- the label already carries it.
        assertEquals(null, outcomeSentence(Outcome.PASS))
    }

    @Test
    fun `every shipped check has an explanatory sentence`() {
        val missing = VerificationReport.DISPLAY_ORDER.filter { checkDetail(it) == null }
        assertEquals(emptyList<String>(), missing)
    }
}
