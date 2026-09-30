package com.couchmode.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couchmode.app.ui.WizardScreen
import com.couchmode.app.ui.labelFor
import com.couchmode.app.ui.nameKey
import com.couchmode.app.wizard.WizardViewModel
import com.couchmode.app.ui.AddControllerScreen
import com.couchmode.app.ui.ControllerListScreen
import com.couchmode.app.ui.CouchModeTheme
import com.couchmode.app.ui.DaemonScreen
import com.couchmode.app.ui.MainViewModel

private enum class Screen { Controllers, AddController, DeveloperTools, Wizard }

// Screens follow docs/concept/app-screens.png. The wizard (screen 3) is not built yet.
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CouchModeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by rememberSaveable { mutableStateOf(Screen.Controllers) }
                    // Which controller the wizard is for, as its nameKey (survives rotation).
                    var wizardKey by rememberSaveable { mutableStateOf<String?>(null) }
                    val wizard: WizardViewModel = viewModel()
                    fun closeWizard() {
                        wizard.cancel()
                        wizardKey = null
                        screen = Screen.Controllers
                    }
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    BackHandler(enabled = screen != Screen.Controllers) {
                        if (screen == Screen.Wizard) closeWizard() else screen = Screen.Controllers
                    }

                    when (screen) {
                        Screen.Controllers -> ControllerListScreen(
                            state = state,
                            onReorder = viewModel::setPriority,
                            onRemove = viewModel::removeFromPriority,
                            onAddController = { screen = Screen.AddController },
                            onOpenDeveloperTools = { screen = Screen.DeveloperTools },
                            onSetRetroidCompat = viewModel::setRetroidCompatEnabled,
                            onOpenWizard = {
                                wizardKey = it.nameKey()
                                screen = Screen.Wizard
                            },
                            onSetUserName = viewModel::setUserName,
                        )
                        Screen.AddController -> AddControllerScreen(
                            state = state,
                            onPick = {
                                viewModel.addToPriority(it)
                                screen = Screen.Controllers
                            },
                            onBack = { screen = Screen.Controllers },
                        )
                        Screen.Wizard -> {
                            val entry = state.priority.firstOrNull { it.nameKey() == wizardKey }
                            if (entry == null) {
                                // The controller left the list (or the daemon is gone): nothing to set up.
                                if (state.daemonRunning != null) closeWizard()
                            } else {
                                LaunchedEffect(wizardKey) { wizard.start(entry) }
                                val wizardState by wizard.state.collectAsStateWithLifecycle()
                                WizardScreen(
                                    title = labelFor(entry.name, entry.nameKey(), state.userNames),
                                    state = wizardState,
                                    hasSavedMap = entry.mapping != 0,
                                    onSkip = wizard::skip,
                                    onRetry = wizard::retry,
                                    onBack = wizard::back,
                                    onClose = ::closeWizard,
                                    onClearSaved = { wizard.clearSaved(entry) },
                                    onStartTest = wizard::startTest,
                                    onStopTest = wizard::stopTest,
                                    onFinishNow = wizard::finishNow,
                                )
                            }
                        }
                        Screen.DeveloperTools -> Scaffold(
                            topBar = {
                                TopAppBar(
                                    title = { Text("Developer tools") },
                                    navigationIcon = {
                                        IconButton(onClick = { screen = Screen.Controllers }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                        }
                                    },
                                )
                            },
                        ) { innerPadding -> DaemonScreen(Modifier.padding(innerPadding)) }
                    }
                }
            }
        }
    }
}
