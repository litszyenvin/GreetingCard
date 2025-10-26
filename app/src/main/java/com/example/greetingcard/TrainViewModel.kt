package com.example.greetingcard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.greetingcard.data.TrainRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TrainViewModel(
    private val repo: TrainRepository = TrainRepository()
) : ViewModel() {

    private val _statusText = MutableStateFlow("Loading train info…")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    init {
        // Example: St Albans City (SAC) to Farringdon (ZFD)
        val origin = "SAC"
        val destination = "ZFD"
        viewModelScope.launch {
            _statusText.value = repo.getStatusText(origin, destination, take = 4)
        }
    }
}
