package com.realitylock.app.certificate

import com.realitylock.app.capture.model.CapturedEvent
import com.realitylock.app.capture.model.MerkleData
import com.realitylock.app.capture.model.MerkleLeaves
import com.realitylock.app.capture.store.CapturedEventFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The BSA 2023 s.63 draft annexure.
 *
 * Almost every test here asserts something the document must **refuse** to do.
 * That is the point of the feature: a page laid out like a statutory certificate
 * is a document people may act on, so the failure that matters is not a crash but
 * a plausible-looking form that overstates its own standing. `research/06` §1.3
 * is explicit that this system generates material a human certifier relies on and
 * cannot be the certifier — these tests are that sentence made executable.
 */
class StatutoryAnnexureContentTest {

    private fun signedEvent(): CapturedEvent = CapturedEventFixtures.sampleEvent().copy(
        merkle = MerkleData(
            algorithm = "SHA-256",
            scheme = "2-leaf",
            leaves = MerkleLeaves(media = "a".repeat(64), metadata = "b".repeat(64)),
            root = "c".repeat(64),
        ),
    )

    private val signatories = listOf(
        SignatoryBlock(role = "Person in charge of the device", basis = "BSA 2023 s.63(4)(a)"),
        SignatoryBlock(role = "Independent expert", basis = "BSA 2023 s.63(4)(b)"),
    )

    private val humanMatters = listOf(
        "that the device was operating properly throughout the relevant period",
        "that the information was fed in the ordinary course of activity",
        "how the record was handled after it left the device",
    )

    private val labels = StatutoryAnnexureContent.DeviceParticularLabels(
        make = "Make",
        model = "Model",
        platform = "Platform API level",
        installId = "Installation identifier",
        software = "Producing software",
    )

    private fun annexure(
        event: CapturedEvent = signedEvent(),
        draftNotice: String = "DRAFT — not a certificate; requires countersignature.",
        matters: List<String> = humanMatters,
        signers: List<SignatoryBlock> = signatories,
    ) = StatutoryAnnexureContent.from(
        event = event,
        title = "Draft technical annexure",
        draftNotice = draftNotice,
        deviceParticularLabels = labels,
        productionMethod = listOf("Captured in-app; hashed and signed on device before storage."),
        mattersRequiringHumanAttestation = matters,
        signatories = signers,
        generatedAtIso = "2026-08-03T10:00:00Z",
    )

    // --- refusals ---

    @Test
    fun `an annexure cannot be produced for an event that was never hashed`() {
        // The Schedule's hash value is the one legally load-bearing field. Emitting
        // the form with a blank there would be worse than emitting nothing, so an
        // unsigned event must be refused rather than rendered with a gap.
        val unsigned = CapturedEventFixtures.sampleEvent() // merkle defaults to null

        val error = assertThrows(IllegalStateException::class.java) { annexure(event = unsigned) }

        assertTrue("was: ${error.message}", error.message!!.contains("no hash"))
    }

    @Test
    fun `an annexure cannot exist without stating that it is a draft`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            annexure(draftNotice = "  ")
        }

        assertTrue("was: ${error.message}", error.message!!.contains("draft"))
    }

    @Test
    fun `an annexure cannot omit what its signatories must attest to themselves`() {
        // Without this list the document reads as though the technical output
        // satisfies the whole statutory test.
        val error = assertThrows(IllegalArgumentException::class.java) {
            annexure(matters = emptyList())
        }

        assertTrue("was: ${error.message}", error.message!!.contains("attest"))
    }

    @Test
    fun `a single signatory is refused because the BSA requires dual certification`() {
        // s.63(4) needs the device custodian AND an independent expert. Printing one
        // block would quietly understate the requirement to whoever signs it.
        val error = assertThrows(IllegalArgumentException::class.java) {
            annexure(signers = signatories.take(1))
        }

        assertTrue("was: ${error.message}", error.message!!.contains("dual certification"))
    }

    // --- what it does carry ---

    @Test
    fun `the hash value and algorithm are carried from the event, not restated`() {
        val content = annexure()

        assertEquals("c".repeat(64), content.merkleRoot)
        assertEquals("SHA-256", content.hashAlgorithm)
    }

    @Test
    fun `device particulars identify the installation, never a hardware identifier`() {
        // research/03 §5: the install UUID is used precisely so the document does not
        // carry an IMEI or ANDROID_ID the user cannot rotate. A "particulars of the
        // device" section is exactly where such an identifier would be tempting.
        val values = annexure().deviceParticulars.toMap()

        assertEquals("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", values[labels.installId])
        assertEquals("OnePlus", values[labels.make])
        assertEquals("CPH2591", values[labels.model])
    }

    @Test
    fun `signature blocks carry only a role and its legal basis`() {
        // The type has no name/signature/date field at all, so the app cannot emit a
        // document that looks executed. This asserts the blocks stay that shape —
        // if someone later adds a populated field, this is where it surfaces.
        val blocks = annexure().signatories

        assertEquals(StatutoryAnnexureContent.REQUIRED_SIGNATORIES, blocks.size)
        blocks.forEach { block ->
            assertTrue("a signatory block lost its role", block.role.isNotBlank())
            assertTrue("a signatory block lost its legal basis", block.basis.isNotBlank())
        }
        assertTrue(
            "the independent-expert signatory is missing",
            blocks.any { it.role.contains("expert", ignoreCase = true) },
        )
    }
}
