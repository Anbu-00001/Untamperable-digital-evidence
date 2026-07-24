package com.realitylock.app.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.realitylock.app.capture.CameraController
import com.realitylock.app.capture.CapturedFrame
import com.realitylock.app.capture.model.CapturedEvent
import com.realitylock.app.core.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the capture screen renders from. */
data class CaptureUiState(
    val isCapturing: Boolean = false,
    val lastEvent: CapturedEvent? = null,
    val events: List<CapturedEvent> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * Drives the capture screen (MVVM per research/03 §1: ViewModel exposes
 * observable state via StateFlow, the UI only observes).
 */
class CaptureViewModel(container: AppContainer) : ViewModel() {

    private val sensors = container.createSensorCollector()
    private val coordinator = container.createCaptureCoordinator(sensors)
    private val repository = container.eventRepository

    /** Owned by the ViewModel so the camera survives recomposition. */
    val cameraController: CameraController = container.createCameraController()

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    /** True when the device actually has motion sensors to record. */
    val hasMotionSensors: Boolean get() = sensors.isAvailable

    init {
        refreshHistory()
    }

    /** Begin buffering sensor samples — call when the screen becomes visible. */
    fun onScreenActive() = sensors.start()

    /** Stop buffering — call when the screen is hidden, to save battery. */
    fun onScreenInactive() = sensors.stop()

    /**
     * Captures a frame and records it as an event.
     *
     * @param includeLocation false when location permission was not granted; the
     *        event is still recorded, with the absence explicit rather than faked.
     */
    fun capture(includeLocation: Boolean) {
        if (_uiState.value.isCapturing) return
        _uiState.update { it.copy(isCapturing = true, errorMessage = null) }

        viewModelScope.launch {
            runCatching {
                val frame: CapturedFrame = cameraController.capture()
                withContext(Dispatchers.IO) {
                    coordinator.record(frame, includeLocation = includeLocation)
                }
            }.onSuccess { event ->
                _uiState.update {
                    it.copy(isCapturing = false, lastEvent = event, errorMessage = null)
                }
                refreshHistory()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isCapturing = false,
                        errorMessage = error.message ?: "Capture failed",
                    )
                }
            }
        }
    }

    fun deleteEvent(eventId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.delete(eventId) }
            refreshHistory()
        }
    }

    fun dismissError() = _uiState.update { it.copy(errorMessage = null) }

    private fun refreshHistory() {
        viewModelScope.launch {
            val events = withContext(Dispatchers.IO) { repository.list() }
            _uiState.update { it.copy(events = events) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        sensors.stop()
        cameraController.release()
    }

    companion object {
        /** Supplies the [AppContainer] without a DI framework. */
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras,
                ): T = CaptureViewModel(container) as T
            }
    }
}
