package com.example.guiderunningfortheblind.ui.running

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.example.guiderunningfortheblind.MainApplication
import com.example.guiderunningfortheblind.R
import com.example.guiderunningfortheblind.ui.plan.PlanCreatorScreen
import com.example.guiderunningfortheblind.ui.plan.PlanCreatorViewModel
import com.example.guiderunningfortheblind.ui.theme.GuideRunningFortheBlindTheme
import kotlinx.coroutines.delay

class PreRunFragment : Fragment() {

    private val sessionViewModel: RunningSessionViewModel by activityViewModels {
        val app = requireActivity().application as MainApplication
        RunningSessionViewModel.Factory(
            app.runningSessionRepository,
            app.locationManager,
            app.healthConnectManager,
            app.userProfileRepository
        )
    }

    private val planViewModel: PlanCreatorViewModel by activityViewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PlanCreatorViewModel((requireActivity().application as MainApplication).runningRepository) as T
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {

            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )

            setContent {
                GuideRunningFortheBlindTheme {
                    PlanCreatorScreen(
                        viewModel = planViewModel,
                        onPlanSaved = {
                            sessionViewModel.startSession()
                            findNavController().navigate(R.id.action_preRun_to_running)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PreRunScreen(onStartRun: () -> Unit) {
    var isCheckingDevices by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(1500) // Simulate device check
        isCheckingDevices = false
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isCheckingDevices) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Checking connected devices...")
        } else {
            Text("Devices Ready", modifier = Modifier.padding(16.dp))
            Button(onClick = onStartRun) {
                Text("Start Run Now")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreRunScreenPreview() {
    GuideRunningFortheBlindTheme {
        PreRunScreen(onStartRun = {})
    }
}
