package com.example.greetingcard

import android.util.Log
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

    companion object {
        private const val TAG = "TrainViewModel"
    }

    init {
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch {
            var backoffMs = 0L
            while (isActive) {
                try {
                    Log.d(TAG, "Starting fetch (backoff=${backoffMs}ms)")
                    // Fetch both directions sequentially
                    val a = repo.getStatusText(originA, destA, take = 4)  // SAC → ZFD
                    Log.d(TAG, "Got route A: ${a.take(100)}")
                    val b = repo.getStatusText(originB, destB, take = 4)  // ZFD → SAC
                    Log.d(TAG, "Got route B: ${b.take(100)}")

                    // Combine with a divider. The repository already includes a header line.
                    _statusText.value = a + "\n\n" + b

                    // reset backoff on success
                    backoffMs = 0L
                    Log.d(TAG, "Success, resetting backoff")
                    
                    // Wait before next refresh
                    delay(refreshMs)
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching data: ${e.message}", e)
                    _statusText.value = "Error fetching data: ${e.message ?: "unknown"}"
                    // exponential-ish backoff on errors: start at refreshMs, then double each attempt
                    backoffMs = if (backoffMs == 0L) refreshMs else min(backoffMs * 2, maxBackoffMs)
                    Log.d(TAG, "Error, setting backoff to ${backoffMs}ms")
                    delay(backoffMs)
                }
            }
        }
    }
}
