package tech.saltvedt.gcu.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import tech.saltvedt.gcu.data.BbqRepository
import tech.saltvedt.gcu.model.ControllerEvent
import tech.saltvedt.gcu.model.UiState

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BbqRepository(application)

    val uiState: StateFlow<UiState> = repository.uiState
    val events: SharedFlow<ControllerEvent> = repository.events

    /** Begin scanning + auto-connecting. Safe to call repeatedly. */
    fun start() = repository.start()

    fun flipBy(turns: Float) = repository.flipBy(turns)
    fun stop() = repository.stop()
    fun clearErrors() = repository.clearErrors()
    fun setMaxVelocity(velocity: Float) = repository.setMaxVelocity(velocity)
}
