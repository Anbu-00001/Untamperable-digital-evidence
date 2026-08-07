package com.realitylock.app.ui.analyze

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.realitylock.app.core.di.AppContainer
import com.realitylock.app.forensics.ForensicAnalyzer
import com.realitylock.app.forensics.ProofLookup
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the Analyze screen: answers "is this a Reality Lock capture?" first,
 * then runs the ELA + EXIF heuristics underneath that answer.
 *
 * ## Still independent of the capture pipeline
 *
 * It holds no coordinator and no signer, so there remains structurally no way
 * for an analysed image to become a signed proof package. The [ProofLookup]
 * added here is **read-only** — it hashes bytes and searches stored events — so
 * the invariant that matters is untouched: nothing on this screen can create,
 * modify or sign evidence.
 *
 * That was worth checking rather than assuming, because the original comment
 * framed the isolation as "holds no reference to the repository". The reason
 * behind it was never repository access as such; it was that an *analysed* image
 * must not be able to acquire a proof it did not have.
 */
class AnalyzeViewModel(
    private val analyzer: ForensicAnalyzer,
    private val proofLookup: ProofLookup,
    private val openStream: (Uri) -> InputStream?,
) : ViewModel() {

    data class UiState(
        val analyzing: Boolean = false,
        /**
         * The headline answer, resolved before the heuristics run. Null until an
         * image is picked.
         */
        val proof: ProofLookup.Result? = null,
        val report: ForensicAnalyzer.AuthenticityReport? = null,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun analyze(uri: Uri) {
        _uiState.update { it.copy(analyzing = true, error = null, report = null, proof = null) }
        viewModelScope.launch {
            // The proof lookup lands first and separately. It is a hash and a
            // list scan — fast, and it cannot fail the way decoding can — so the
            // headline answer is on screen even if the heuristics below then
            // fall over on a malformed image.
            val proof = withContext(Dispatchers.IO) { proofLookup.lookup { openStream(uri) } }
            _uiState.update { it.copy(proof = proof) }

            runCatching {
                withContext(Dispatchers.IO) { analyzer.analyze(uri) }
            }.onSuccess { report ->
                _uiState.update { it.copy(analyzing = false, report = report) }
            }.onFailure { t ->
                _uiState.update { it.copy(analyzing = false, error = t.message) }
            }
        }
    }

    fun clear() = _uiState.update { UiState() }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return AnalyzeViewModel(
                analyzer = container.createForensicAnalyzer(),
                proofLookup = container.createProofLookup(),
                openStream = container::openInputStream,
            ) as T
        }
    }
}
