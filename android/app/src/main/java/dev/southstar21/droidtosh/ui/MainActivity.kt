package dev.southstar21.droidtosh.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.southstar21.droidtosh.BuildConfig
import dev.southstar21.droidtosh.launch.AppVisibility
import dev.southstar21.droidtosh.update.Updates
import dev.southstar21.droidtosh.ui.theme.DroidtoshTheme

class MainActivity : ComponentActivity() {
    /** A tab another surface asked for (a transfer notification, the share sheet). */
    private val requestedDestination = mutableStateOf<Destination?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) requestedDestination.value = destinationFrom(intent)
        val state: AppState by viewModels()
        Updates.start(this)
        setContent {
            DroidtoshTheme {
                val isPaired by state.isPaired.collectAsStateWithLifecycle()
                val phase = if (isPaired) Phase.Main else Phase.Onboarding
                val slowEffects = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
                val slowSpatial = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
                val fastEffects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
                AnimatedContent(
                    targetState = phase,
                    transitionSpec = {
                        (fadeIn(slowEffects) + scaleIn(slowSpatial, initialScale = 0.92f)).togetherWith(fadeOut(fastEffects))
                    },
                    label = "phase",
                ) { current ->
                    when (current) {
                        Phase.Onboarding -> OnboardingFlow(state)
                        Phase.Main -> MainShell(
                            state = state,
                            requestedDestination = requestedDestination.value,
                            onDestinationHandled = { requestedDestination.value = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        destinationFrom(intent)?.let { requestedDestination.value = it }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.onActivityStarted()
    }

    override fun onStop() {
        AppVisibility.onActivityStopped()
        super.onStop()
    }

    private enum class Phase { Onboarding, Main }

    companion object {
        private const val EXTRA_DESTINATION = "${BuildConfig.APPLICATION_ID}.extra.DESTINATION"

        /** Opens the app on [destination] (or brings it forward and switches to it). */
        fun intent(context: Context, destination: Destination): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_DESTINATION, destination.name)

        private fun destinationFrom(intent: Intent?): Destination? =
            intent?.getStringExtra(EXTRA_DESTINATION)?.let { name -> Destination.entries.firstOrNull { it.name == name } }
    }
}
