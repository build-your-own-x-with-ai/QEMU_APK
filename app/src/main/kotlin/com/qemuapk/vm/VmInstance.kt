package com.qemuapk.vm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Date

/**
 * Represents the state of a virtual machine instance.
 */
enum class VmState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

/**
 * Represents a single virtual machine instance with its lifecycle state.
 */
class VmInstance(
    val config: VmConfig,
    val createdAt: Date = Date()
) {
    private val _state = MutableStateFlow(VmState.STOPPED)
    val state: StateFlow<VmState> = _state.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _uptimeMs = MutableStateFlow(0L)
    val uptimeMs: StateFlow<Long> = _uptimeMs.asStateFlow()

    private var startedAt: Long = 0

    val currentState: VmState get() = _state.value
    val isRunning: Boolean get() = _state.value == VmState.RUNNING
    val isStopped: Boolean get() = _state.value == VmState.STOPPED

    fun onStarting() {
        _state.value = VmState.STARTING
        _errorMessage.value = null
        startedAt = System.currentTimeMillis()
    }

    fun onRunning() {
        _state.value = VmState.RUNNING
    }

    fun onStopping() {
        _state.value = VmState.STOPPING
    }

    fun onStopped() {
        _state.value = VmState.STOPPED
        _uptimeMs.value = 0
    }

    fun onError(message: String) {
        _state.value = VmState.ERROR
        _errorMessage.value = message
    }

    fun updateUptime() {
        if (_state.value == VmState.RUNNING) {
            _uptimeMs.value = System.currentTimeMillis() - startedAt
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VmInstance) return false
        return config.id == other.config.id
    }

    override fun hashCode(): Int = config.id.hashCode()
}
