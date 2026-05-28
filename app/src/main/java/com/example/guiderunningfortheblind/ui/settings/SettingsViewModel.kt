package com.example.guiderunningfortheblind.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.guiderunningfortheblind.data.local.entity.UserProfileEntity
import com.example.guiderunningfortheblind.data.repository.UserProfileRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: UserProfileRepository) : ViewModel() {

    val userProfile: StateFlow<UserProfileEntity?> = repository.userProfileEntity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _saveStatus = MutableSharedFlow<Boolean>()
    val saveStatus: SharedFlow<Boolean> = _saveStatus.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            repository.refreshProfile()
        }
    }

    fun updateProfile(profile: UserProfileEntity) {
        viewModelScope.launch {
            try {
                repository.updateProfile(profile)
                _saveStatus.emit(true)
            } catch (e: Exception) {
                _saveStatus.emit(false)
            }
        }
    }

    class Factory(private val repository: UserProfileRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(repository) as T
        }
    }
}
