package de.photosync.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.photosync.BuildConfig
import de.photosync.domain.model.SessionState
import de.photosync.ui.gallery.LocalGallery
import de.photosync.ui.partner.PartnerGallery
import de.photosync.ui.settings.SettingsScreen
import de.photosync.ui.trash.TrashScreen

private object Route {
    const val SERVER = "server"
    const val AUTH = "auth"
    const val HOME = "home"
}

@Composable
fun PhotoSyncApp(applicationContext: Context) {
    val viewModel: AppViewModel = viewModel(factory = AppViewModel.factory(applicationContext))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    when (state.session) {
        SessionState.Loading -> LoadingScreen()
        SessionState.NeedsServer -> navController.NavigateWhenReady(Route.SERVER) {
            NavHost(navController, startDestination = Route.SERVER) {
                composable(Route.SERVER) { ServerScreen(state, viewModel) }
                composable(Route.AUTH) { AuthenticationScreen(state, viewModel) }
                composable(Route.HOME) { HomeScreen(state, viewModel, applicationContext) }
            }
        }
        is SessionState.NeedsAuthentication -> navController.NavigateWhenReady(Route.AUTH) {
            NavHost(navController, startDestination = Route.AUTH) {
                composable(Route.SERVER) { ServerScreen(state, viewModel) }
                composable(Route.AUTH) { AuthenticationScreen(state, viewModel) }
                composable(Route.HOME) { HomeScreen(state, viewModel, applicationContext) }
            }
        }
        is SessionState.Authenticated -> navController.NavigateWhenReady(Route.HOME) {
            NavHost(navController, startDestination = Route.HOME) {
                composable(Route.SERVER) { ServerScreen(state, viewModel) }
                composable(Route.AUTH) { AuthenticationScreen(state, viewModel) }
                composable(Route.HOME) { HomeScreen(state, viewModel, applicationContext) }
            }
        }
    }
}

@Composable
private fun androidx.navigation.NavHostController.NavigateWhenReady(destination: String, content: @Composable () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(destination) {
        if (currentDestination?.route != destination) {
            navigate(destination) { popUpTo(graph.startDestinationId) { inclusive = true } }
        }
    }
    content()
}

@Composable
private fun LoadingScreen() = Screen { CircularProgressIndicator() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerScreen(state: AppUiState, viewModel: AppViewModel) {
    var address by rememberSaveable { mutableStateOf(BuildConfig.DEFAULT_SERVER_URL) }
    Scaffold(topBar = { TopAppBar(title = { Text("PhotoSync", fontWeight = FontWeight.SemiBold) }) }) { padding ->
        Screen(padding) {
            Spacer(Modifier.height(20.dp))
            Text("Deine private Fotobibliothek", style = MaterialTheme.typography.headlineMedium)
            Text("Verbinde PhotoSync mit deinem Server, um loszulegen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = address,
                onValueChange = { if (!BuildConfig.IS_PRODUCTION) address = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                readOnly = BuildConfig.IS_PRODUCTION,
                label = { Text("Serveradresse") },
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.configureAndTest(address) },
                enabled = !state.isWorking,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Weiter") }
            Feedback(state)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticationScreen(state: AppUiState, viewModel: AppViewModel) {
    var page by rememberSaveable { mutableStateOf("pair") }
    Scaffold(topBar = { TopAppBar(title = { Text("Gerät anmelden") }) }) { padding ->
        Screen(padding) {
            Text("Willkommen bei PhotoSync", style = MaterialTheme.typography.headlineMedium)
            Text("Verbinde dieses Gerät mit deiner privaten Fotobibliothek.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
                Row(Modifier.fillMaxWidth().padding(4.dp)) {
                    Tab(selected = page == "pair", onClick = { page = "pair" }, modifier = Modifier.weight(1f), text = { Text("Pairing") })
                    Tab(selected = page == "setup", onClick = { page = "setup" }, modifier = Modifier.weight(1f), text = { Text("Ersteinrichtung") })
                }
            }
            if (page == "setup") SetupForm(state, viewModel) else PairForm(state, viewModel)
            Feedback(state)
        }
    }
}

@Composable
private fun SetupForm(state: AppUiState, viewModel: AppViewModel) {
    var setupToken by remember { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var deviceName by rememberSaveable { mutableStateOf("") }
    SetupFormContent(setupToken, name, deviceName, !state.isWorking, { setupToken = it }, { name = it }, { deviceName = it }) {
        viewModel.setup(setupToken, name, deviceName)
    }
}

@Composable
private fun PairForm(state: AppUiState, viewModel: AppViewModel) {
    var code by remember { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var deviceName by rememberSaveable { mutableStateOf("") }
    PairingFormContent(code, name, deviceName, !state.isWorking, { code = it }, { name = it }, { deviceName = it }) {
        viewModel.pair(code, name, deviceName)
    }
}

/** Authentication form content, independent of server state for Android Studio previews. */
@Composable
internal fun SetupFormContent(
    setupToken: String, name: String, deviceName: String, enabled: Boolean,
    onTokenChange: (String) -> Unit = {}, onNameChange: (String) -> Unit = {}, onDeviceNameChange: (String) -> Unit = {}, onSubmit: () -> Unit = {},
) {
    Spacer(Modifier.height(8.dp))
    Text("Neue Bibliothek einrichten", style = MaterialTheme.typography.headlineSmall)
    Text("Lege dein erstes Profil und dieses Gerät an.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(setupToken, onTokenChange, Modifier.fillMaxWidth(), label = { Text("Setup-Token") }, visualTransformation = PasswordVisualTransformation())
    OutlinedTextField(name, onNameChange, Modifier.fillMaxWidth(), label = { Text("Dein Name") })
    OutlinedTextField(deviceName, onDeviceNameChange, Modifier.fillMaxWidth(), label = { Text("Gerätename") })
    Button(onClick = onSubmit, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Erstes Gerät registrieren") }
}

@Composable
internal fun PairingFormContent(
    code: String, name: String, deviceName: String, enabled: Boolean,
    onCodeChange: (String) -> Unit = {}, onNameChange: (String) -> Unit = {}, onDeviceNameChange: (String) -> Unit = {}, onSubmit: () -> Unit = {},
) {
    Spacer(Modifier.height(8.dp))
    Text("Gerät verbinden", style = MaterialTheme.typography.headlineSmall)
    Text("Gib den Pairing-Code ein, den du auf einem verbundenen Gerät erhalten hast.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(code, onCodeChange, Modifier.fillMaxWidth(), label = { Text("Pairing-Code") }, visualTransformation = PasswordVisualTransformation())
    OutlinedTextField(name, onNameChange, Modifier.fillMaxWidth(), label = { Text("Dein Name (Partner)") })
    OutlinedTextField(deviceName, onDeviceNameChange, Modifier.fillMaxWidth(), label = { Text("Gerätename") })
    Button(onClick = onSubmit, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Gerät per Pairing verbinden") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(state: AppUiState, viewModel: AppViewModel, applicationContext: Context) {
    val session = (state.session as? SessionState.Authenticated)?.session ?: return
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var secondaryScreen by rememberSaveable { mutableStateOf<String?>(null) }
    val homeScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    androidx.activity.compose.BackHandler(enabled = secondaryScreen != null) { secondaryScreen = null }
    Scaffold(modifier = Modifier.nestedScroll(homeScrollBehavior.nestedScrollConnection), topBar = {
        PhotoSyncHomeTopBar(
            title = when (secondaryScreen) {
                "settings" -> "Einstellungen"
                "trash" -> "Papierkorb"
                else -> if (selectedTab == 0) "Meine Alben" else "Partner"
            },
            userName = session.userDisplayName,
            secondary = secondaryScreen != null,
            working = state.isWorking,
            onBack = { secondaryScreen = null },
            onSettings = { secondaryScreen = "settings" },
            onTrash = { secondaryScreen = "trash" },
            onSignOut = viewModel::signOut,
            scrollBehavior = homeScrollBehavior,
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (secondaryScreen == null) PhotoSyncHomeTabs(selectedTab) { selectedTab = it }
            if (secondaryScreen == "settings") {
                val authenticated = state.session as SessionState.Authenticated
                SettingsScreen(applicationContext, authenticated.server.baseUrl, authenticated.session.userId)
            } else if (secondaryScreen == "trash") {
                val authenticated = state.session as SessionState.Authenticated
                TrashScreen(applicationContext, authenticated.server.baseUrl, authenticated.session.userId)
            } else if (selectedTab == 0) {
                LocalGallery(applicationContext, session.deviceId)
            } else {
                val authenticated = state.session as SessionState.Authenticated
                PartnerGallery(applicationContext, authenticated.server.baseUrl, authenticated.session.userId)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoSyncHomeTopBar(
    title: String,
    userName: String,
    secondary: Boolean,
    working: Boolean = false,
    onBack: () -> Unit = {},
    onSettings: () -> Unit = {},
    onTrash: () -> Unit = {},
    onSignOut: () -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Column {
                Text(title, fontWeight = FontWeight.SemiBold)
                if (!secondary) Text(userName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        navigationIcon = { if (secondary) TextButton(onClick = onBack) { Text("Zurück") } },
        actions = {
            if (!secondary) {
                IconButton(onClick = { menuExpanded = true }) { Text("⋮", style = MaterialTheme.typography.headlineSmall) }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Einstellungen") }, onClick = { menuExpanded = false; onSettings() })
                    DropdownMenuItem(text = { Text("Papierkorb") }, onClick = { menuExpanded = false; onTrash() })
                    DropdownMenuItem(text = { Text("Abmelden") }, enabled = !working, onClick = { menuExpanded = false; onSignOut() })
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        scrollBehavior = scrollBehavior,
    )
}

@Composable
internal fun PhotoSyncHomeTabs(selectedTab: Int, onSelect: (Int) -> Unit = {}) {
    TabRow(selectedTabIndex = selectedTab, containerColor = MaterialTheme.colorScheme.background) {
        Tab(selected = selectedTab == 0, onClick = { onSelect(0) }, text = { Text("Meine Alben") })
        Tab(selected = selectedTab == 1, onClick = { onSelect(1) }, text = { Text("Partner") })
    }
}

@Composable
private fun Feedback(state: AppUiState) {
    if (state.isWorking) {
        Spacer(Modifier.height(16.dp))
        CircularProgressIndicator()
    }
    state.connectionMessage?.let {
        Spacer(Modifier.height(16.dp))
        Text(it, color = MaterialTheme.colorScheme.primary)
    }
    state.errorMessage?.let {
        Spacer(Modifier.height(16.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun Screen(padding: PaddingValues = PaddingValues(24.dp), content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.Start,
        content = content,
    )
}
