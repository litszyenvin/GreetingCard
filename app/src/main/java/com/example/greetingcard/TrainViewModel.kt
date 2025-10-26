package com.example.greetingcard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.greetingcard.data.TrainRepository
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

    private fun startPolling() {
        viewModelScope.launch {
            var delayMs = 0L
            while (isActive) {
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
                    // exponential-ish backoff on errors
                    delayMs = if (delayMs == 0L) refreshMs else min(delayMs * 2, maxBackoffMs)
                }
                delay(delayMs)
            }
        }
    }
}
