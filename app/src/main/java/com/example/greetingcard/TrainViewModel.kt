package com.example.greetingcard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.greetingcard.data.TrainRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

class TrainViewModel(
    private val repo: TrainRepository = TrainRepository()
) : ViewModel() {

    private val _statusText = MutableStateFlow("Loading train info…")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val pollSignals = Channel<PollTrigger>(Channel.CONFLATED)
    private var scheduledRetry: Job? = null

    // Two routes: SAC → ZFD and ZFD → SAC
    private val originA = "SAC"
    private val destA = "ZFD"
    private val originB = "ZFD"
    private val destB = "SAC"

    private val refreshMs = 30_000L     // 30 seconds
    private val maxBackoffMs = 5 * 60_000L

    init {
        startPolling()
    }

    override fun onCleared() {
        super.onCleared()
        scheduledRetry?.cancel()
        pollSignals.close()
    }

    fun onNetworkAvailable() {
        scheduledRetry?.cancel()
        if (_statusText.value.startsWith("Error")) {
            viewModelScope.launch {
                _statusText.value = "Loading train info…"
            }
        }
        pollSignals.trySend(PollTrigger.Immediate)
    }

    private fun startPolling() {
        viewModelScope.launch {
            var delayMs = 0L
            pollSignals.trySend(PollTrigger.Immediate)
            for (trigger in pollSignals) {
                if (!isActive) break

                val previousDelay = delayMs
                if (trigger == PollTrigger.Immediate) {
                    delayMs = 0L
                }

                try {
                    // Fetch both directions sequentially
                    val a = repo.getStatusText(originA, destA, take = 4)  // SAC → ZFD
                    val b = repo.getStatusText(originB, destB, take = 4)  // ZFD → SAC

                    // Combine with a divider. The repository already includes a header line.
                    _statusText.value = a + "\n\n" + b

                    // reset backoff on success
                    delayMs = refreshMs
                } catch (e: Exception) {
                    _statusText.value = "Error fetching data: ${e.message ?: "unknown"}"
                    val backoffBase = if (trigger == PollTrigger.Immediate) 0L else previousDelay
                    // exponential-ish backoff on errors
                    delayMs = if (backoffBase == 0L) refreshMs else min(backoffBase * 2, maxBackoffMs)
                }

                scheduleNextPoll(delayMs)
            }
        }
    }

    private fun scheduleNextPoll(delayMs: Long) {
        scheduledRetry?.cancel()
        scheduledRetry = viewModelScope.launch {
            if (delayMs <= 0L) {
                pollSignals.trySend(PollTrigger.Immediate)
            } else {
                delay(delayMs)
                pollSignals.trySend(PollTrigger.Scheduled)
            }
        }
    }

    private enum class PollTrigger {
        Immediate,
        Scheduled,
    }
}
