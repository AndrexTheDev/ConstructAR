package com.constructar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.constructar.app.ui.AppRoot
import com.constructar.app.ui.theme.ConstructArTheme

/**
 * The only activity.
 *
 * Kept deliberately empty: the AR surface, the scan pipeline and the export all live in
 * composables/view models so that the activity cannot hold a stale `Session` across a
 * configuration change (which is the classic ARCore leak).
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ProjectViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ConstructArTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(viewModel = viewModel)
                }
            }
        }
    }
}
