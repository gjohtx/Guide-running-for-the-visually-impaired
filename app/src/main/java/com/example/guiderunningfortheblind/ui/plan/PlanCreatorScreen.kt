package com.example.guiderunningfortheblind.ui.plan

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PlanCreatorScreen(
    viewModel: PlanCreatorViewModel,
    onPlanSaved: () -> Unit
) {
    val distance by viewModel.distance.collectAsState()
    val targetPace by viewModel.targetPace.collectAsState()
    val obstaclePreference by viewModel.obstaclePreference.collectAsState()
    
    var planName by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(500)) + slideInVertically(initialOffsetY = { it / 2 })
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            Text("Create Running Plan", style = MaterialTheme.typography.headlineMedium)

            OutlinedTextField(
                value = planName,
                onValueChange = { planName = it },
                label = { Text("Plan Name") },
                modifier = Modifier.fillMaxWidth()
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Distance: ${"%.1f".format(distance)} km", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = distance,
                        onValueChange = { viewModel.updateDistance(it) },
                        valueRange = 1f..42f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            OutlinedTextField(
                value = targetPace,
                onValueChange = { viewModel.updatePace(it) },
                label = { Text("Target Pace (min/km)") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text("Obstacle Alerts", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = obstaclePreference,
                    onCheckedChange = { viewModel.updateObstaclePreference(it) }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    viewModel.savePlan(planName)
                    onPlanSaved()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = planName.isNotBlank()
            ) {
                Text("Save Plan")
            }
        }
    }
}
