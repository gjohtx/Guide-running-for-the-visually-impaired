package com.example.guiderunningfortheblind.ui.history.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.guiderunningfortheblind.data.local.entity.RunningSessionEntity
import com.example.guiderunningfortheblind.data.repository.RunningSessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HistoryDetailViewModel(
    private val repository: RunningSessionRepository,
    private val sessionId: Long
) : ViewModel() {

    private val _session = MutableStateFlow<RunningSessionEntity?>(null)
    val session: StateFlow<RunningSessionEntity?> = _session.asStateFlow()

    init {
        loadSession()
    }

    private fun loadSession() {
        viewModelScope.launch {
            _session.value = repository.getSessionById(sessionId)
        }
    }

    class Factory(
        private val repository: RunningSessionRepository,
        private val sessionId: Long
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(HistoryDetailViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return HistoryDetailViewModel(repository, sessionId) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
