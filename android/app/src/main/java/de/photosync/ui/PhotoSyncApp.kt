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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
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
    Scaffold(topBar = { TopAppBar(title = { Text("PhotoSync verbinden") }) }) { padding ->
        Screen(padding) {
            Text("Serveradresse", style = MaterialTheme.typography.headlineSmall)
            Text(if (BuildConfig.IS_PRODUCTION) "Produktionsserver (HTTPS)" else "Für Geräte außerhalb des Heimnetzes HTTPS verwenden.")
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = address,
                onValueChange = { if (!BuildConfig.IS_PRODUCTION) address = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                readOnly = BuildConfig.IS_PRODUCTION,
                label = { Text(if (BuildConfig.IS_PRODUCTION) "https://philsync.duckdns.org" else "https://photosync.example") },
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.configureAndTest(address) },
                enabled = !state.isWorking,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Verbindung testen und speichern") }
            Feedback(state)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticationScreen(state: AppUiState, viewModel: AppViewModel) {
    var page by rememberSaveable { mutableStateOf("pair") }
    val server = (state.session as? SessionState.NeedsAuthentication)?.server?.baseUrl.orEmpty()
    Scaffold(topBar = { TopAppBar(title = { Text("Gerät anmelden") }) }) { padding ->
        Screen(padding) {
            Text("Server: $server", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { page = "pair" }, enabled = !state.isWorking) { Text("Pairing") }
                TextButton(onClick = { page = "setup" }, enabled = !state.isWorking) { Text("Ersteinrichtung") }
                TextButton(onClick = viewModel::testCurrentServer, enabled = !state.isWorking) { Text("Test") }
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
    Text("Ersten Nutzer einrichten", style = MaterialTheme.typography.headlineSmall)
    Text("Dafür ist das einmalige Betreiber-Setup-Token nötig.")
    OutlinedTextField(setupToken, { setupToken = it }, Modifier.fillMaxWidth(), label = { Text("Setup-Token") }, visualTransformation = PasswordVisualTransformation())
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Dein Name") })
    OutlinedTextField(deviceName, { deviceName = it }, Modifier.fillMaxWidth(), label = { Text("Gerätename") })
    Button(
        onClick = { viewModel.setup(setupToken, name, deviceName) },
        enabled = !state.isWorking,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Erstes Gerät registrieren") }
}

@Composable
private fun PairForm(state: AppUiState, viewModel: AppViewModel) {
    var code by remember { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var deviceName by rememberSaveable { mutableStateOf("") }
    Text("Mit Pairing-Code anmelden", style = MaterialTheme.typography.headlineSmall)
    Text("Für den Partner Namen ausfüllen. Bei einem weiteren eigenen Gerät das Feld leer lassen.")
    OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text("Pairing-Code") }, visualTransformation = PasswordVisualTransformation())
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Dein Name (Partner)") })
    OutlinedTextField(deviceName, { deviceName = it }, Modifier.fillMaxWidth(), label = { Text("Gerätename") })
    Button(
        onClick = { viewModel.pair(code, name, deviceName) },
        enabled = !state.isWorking,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Gerät per Pairing verbinden") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(state: AppUiState, viewModel: AppViewModel, applicationContext: Context) {
    val session = (state.session as? SessionState.Authenticated)?.session ?: return
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(session.userDisplayName) },
            actions = {
                TextButton(onClick = viewModel::verifySession, enabled = !state.isWorking) { Text("Test") }
                TextButton(onClick = viewModel::signOut, enabled = !state.isWorking) { Text("Abmelden") }
            },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Mein Handy") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Partner") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Papierkorb") })
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("Einstellungen") })
            }
            if (selectedTab == 0) {
                LocalGallery(applicationContext, session.deviceId)
            } else if (selectedTab == 1) {
                val authenticated = state.session as SessionState.Authenticated
                PartnerGallery(applicationContext, authenticated.server.baseUrl, authenticated.session.userId)
            } else if (selectedTab == 2) {
                val authenticated = state.session as SessionState.Authenticated
                TrashScreen(applicationContext, authenticated.server.baseUrl, authenticated.session.userId)
            } else {
                val authenticated = state.session as SessionState.Authenticated
                SettingsScreen(applicationContext, authenticated.server.baseUrl, authenticated.session.userId)
            }
        }
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
