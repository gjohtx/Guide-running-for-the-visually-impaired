package com.example.guiderunningfortheblind.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.guiderunningfortheblind.data.local.entity.RunningPlanEntity
import com.example.guiderunningfortheblind.data.repository.RunningRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class PlanCreatorViewModel(private val repository: RunningRepository) : ViewModel() {

    private val _distance = MutableStateFlow(5f)
    val distance: StateFlow<Float> = _distance.asStateFlow()

    private val _targetPace = MutableStateFlow("6:00")
    val targetPace: StateFlow<String> = _targetPace.asStateFlow()

    private val _obstaclePreference = MutableStateFlow(true)
    val obstaclePreference: StateFlow<Boolean> = _obstaclePreference.asStateFlow()

    fun updateDistance(newDistance: Float) {
        _distance.value = newDistance
    }

    fun updatePace(newPace: String) {
        _targetPace.value = newPace
    }

    fun updateObstaclePreference(enabled: Boolean) {
        _obstaclePreference.value = enabled
    }

    fun savePlan(planName: String) {
        viewModelScope.launch {
            val newPlan = RunningPlanEntity(
                planId = UUID.randomUUID().toString(),
                title = planName,
                goalDistance = _distance.value.toDouble() * 1000, // Convert km to meters
                targetPace = _targetPace.value,
                isWarmupIncluded = true
            )
            repository.insert(newPlan)
        }
    }
}
