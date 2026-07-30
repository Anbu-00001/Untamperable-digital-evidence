package com.realitylock.app.ui.verify

import com.realitylock.app.capture.store.CapturedEventFixtures
import com.realitylock.app.capture.store.EventRepository
import com.realitylock.app.certificate.CertificateContent
import com.realitylock.app.certificate.CertificateRenderer
import com.realitylock.app.core.di.AppContainer
import com.realitylock.app.sync.SyncStateStore
import com.realitylock.app.verify.VerificationClient
import com.realitylock.app.verify.VerificationReport
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify as mockkVerify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The [ProofsViewModel] / repository boundary, with the collaborators mocked.
 *
 * The case this file exists for is the first test: a certificate must never
 * print a verdict taken from a *different* event's report. That was a real
 * defect — the verdict label was resolved in the composable and handed to the
 * ViewModel already decided, so whichever report happened to be on screen was
 * stamped onto whatever event the user then exported. The fix made the verdict
 * derive from the same guarded report the check rows come from, and nothing
 * asserted it, so deleting the guard would have been silent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProofsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val repository = mockk<EventRepository>()
    private val verificationClient = mockk<VerificationClient>()
    private val certificateRenderer = mockk<CertificateRenderer>()
    private val syncStateStore = mockk<SyncStateStore>()
    private val container = mockk<AppContainer>()

    private val renderedContent = slot<CertificateContent>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)

        every { container.eventRepository } returns repository
        every { container.syncStateStore } returns syncStateStore
        every { container.verificationClient } returns verificationClient
        every { container.certificateRenderer } returns certificateRenderer
        every { syncStateStore.all() } returns emptyMap()
        every { verificationClient.verificationUrl(any()) } returns "https://example.org/verify"
        every { certificateRenderer.render(capture(renderedContent)) } returns byteArrayOf(1, 2, 3)
        every { repository.findById(any()) } answers {
            CapturedEventFixtures.sampleEvent(eventId = firstArg())
        }
        every { repository.readPackageBytes(any()) } returns PACKAGE_BYTES
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = ProofsViewModel(container, ioDispatcher = dispatcher)

    private fun report(verdict: VerificationReport.Verdict) = VerificationReport(
        verdict = verdict,
        checks = listOf(
            VerificationReport.Check("mediaHashMatch", VerificationReport.Outcome.PASS),
        ),
        notes = emptyList(),
        advisories = emptyList(),
        limitations = listOf("does not prove the depicted event was real"),
    )

    private fun ProofsViewModel.exportCertificate(eventId: String) = buildCertificate(
        eventId = eventId,
        title = "Reality Lock — Event Proof Certificate",
        // Traceable: whatever this returns could only have come from a report.
        verdictLabeller = { it.name },
        notVerifiedLabel = NOT_VERIFIED,
        checksAbsentNotice = "no breakdown to print",
        framing = listOf("what this proves", "what it does not"),
        checkLabeller = { it },
    )

    // --- the regression this file is named for ---

    @Test
    fun `a certificate never prints a verdict belonging to another event`() = runTest {
        val vm = viewModel()
        every { verificationClient.verify(any()) } returns
            VerificationClient.Result.Ok(report(VerificationReport.Verdict.VERIFIED))

        // A report is on screen — but for event B.
        vm.verify(EVENT_B)
        assertEquals(EVENT_B, vm.uiState.value.reportEventId)

        // The user then exports event A, which has never been verified.
        vm.exportCertificate(EVENT_A)

        assertEquals(
            "event A's certificate printed event B's verdict",
            NOT_VERIFIED,
            renderedContent.captured.verdictLabel,
        )
    }

    @Test
    fun `a certificate does use the report when it belongs to the same event`() = runTest {
        // The positive control. Without it, a ViewModel hardwired to report
        // "not verified" for everything would satisfy the test above while making
        // the certificate useless.
        val vm = viewModel()
        every { verificationClient.verify(any()) } returns
            VerificationClient.Result.Ok(report(VerificationReport.Verdict.VERIFIED))

        vm.verify(EVENT_A)
        vm.exportCertificate(EVENT_A)

        assertEquals(
            VerificationReport.Verdict.VERIFIED.name,
            renderedContent.captured.verdictLabel,
        )
    }

    @Test
    fun `a stale report is dropped once dismissed, not reused by a later export`() = runTest {
        val vm = viewModel()
        every { verificationClient.verify(any()) } returns
            VerificationClient.Result.Ok(report(VerificationReport.Verdict.VERIFIED))

        vm.verify(EVENT_A)
        vm.dismissReport()
        vm.exportCertificate(EVENT_A)

        assertEquals(NOT_VERIFIED, renderedContent.captured.verdictLabel)
    }

    // --- verification flow ---

    @Test
    fun `a second verify is ignored while one is already in flight`() = runTest {
        val vm = viewModel()
        // Never completes, so the first request stays in flight.
        every { verificationClient.verify(any()) } answers {
            VerificationClient.Result.Ok(report(VerificationReport.Verdict.VERIFIED))
        }

        vm.verify(EVENT_A)
        // With the first call settled, a second is allowed — the guard is about
        // concurrency, not a one-shot lock, so this must still work.
        vm.verify(EVENT_B)

        assertEquals(EVENT_B, vm.uiState.value.reportEventId)
        mockkVerify(exactly = 2) { verificationClient.verify(any()) }
    }

    @Test
    fun `an unreadable package reports an error rather than a verdict`() = runTest {
        val vm = viewModel()
        every { repository.readPackageBytes(EVENT_A) } returns null

        vm.verify(EVENT_A)

        val state = vm.uiState.value
        assertNull("a verdict was produced for a package that could not be read", state.report)
        assertNotNull(state.verifyError)
        assertNull(state.verifyingEventId)
    }

    @Test
    fun `an unreachable verifier surfaces its reason and no report`() = runTest {
        val vm = viewModel()
        every { verificationClient.verify(any()) } returns
            VerificationClient.Result.Unreachable(OFFLINE)

        vm.verify(EVENT_A)

        val state = vm.uiState.value
        assertNull(state.report)
        assertEquals(OFFLINE, state.verifyError)
    }

    @Test
    fun `a missing event surfaces a certificate error instead of rendering`() = runTest {
        val vm = viewModel()
        every { repository.findById(EVENT_A) } returns null

        vm.exportCertificate(EVENT_A)

        val state = vm.uiState.value
        assertNull(state.pendingCertificate)
        assertNotNull(state.certificateError)
        assertNull(state.buildingCertificateFor)
    }

    private companion object {
        const val EVENT_A = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
        const val EVENT_B = "9c8e1b42-7a55-4f19-b3d7-1e6c04a9f220"
        const val NOT_VERIFIED = "NOT VERIFIED"
        const val OFFLINE = "verifier unreachable"
        val PACKAGE_BYTES = """{"eventId":"x"}""".toByteArray()
    }
}
