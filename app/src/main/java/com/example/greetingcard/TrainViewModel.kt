package com.example.greetingcard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TrainViewModel : ViewModel() {
    private val _statusText = MutableStateFlow("Loading train info…")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    init {
        // Simulate your Pi script's output for now
        viewModelScope.launch {
            delay(1000)
            _statusText.value =
                "Station: London Bridge\n" +
                        "Next train: 3 min  • Platform 1\n" +
                        "Following: 8 min   • Platform 4"
        }
    }
}
