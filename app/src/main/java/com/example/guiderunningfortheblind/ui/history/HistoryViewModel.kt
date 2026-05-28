package com.example.guiderunningfortheblind.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.guiderunningfortheblind.data.local.entity.RunningSessionEntity
import com.example.guiderunningfortheblind.data.repository.RunningSessionRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(private val repository: RunningSessionRepository) : ViewModel() {

    // Observe local DB
    val sessions: StateFlow<List<RunningSessionEntity>> = repository.allSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Trigger remote fetch
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            repository.refreshSessions()
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
        }
    }

    class Factory(private val repository: RunningSessionRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return HistoryViewModel(repository) as T
        }
    }
}
