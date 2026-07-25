package com.realitylock.app.ui.analyze

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.realitylock.app.core.di.AppContainer
import com.realitylock.app.forensics.ForensicAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the Analyze screen: runs ELA + EXIF heuristics on a user-picked image.
 *
 * Deliberately independent of the capture pipeline — it holds no reference to
 * the coordinator or signer, so there is structurally no way for an analysed
 * image to become a signed proof package.
 */
class AnalyzeViewModel(private val analyzer: ForensicAnalyzer) : ViewModel() {

    data class UiState(
        val analyzing: Boolean = false,
        val report: ForensicAnalyzer.AuthenticityReport? = null,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun analyze(uri: Uri) {
        _uiState.update { it.copy(analyzing = true, error = null, report = null) }
        viewModelScope.launch {
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
            return AnalyzeViewModel(container.createForensicAnalyzer()) as T
        }
    }
}
