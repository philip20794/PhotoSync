package de.photosync.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import de.photosync.data.PhotoSyncRepository
import de.photosync.data.local.AppDatabase
import de.photosync.data.local.SecureCredentialStore
import de.photosync.domain.ServerAddress
import de.photosync.domain.model.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import retrofit2.HttpException
import java.io.IOException

data class AppUiState(
    val session: SessionState = SessionState.Loading,
    val isWorking: Boolean = false,
    val connectionMessage: String? = null,
    val errorMessage: String? = null,
)

class AppViewModel(private val repository: PhotoSyncRepository) : ViewModel() {
    private val actionState = MutableStateFlow(AppUiState())
    private val sessionState = repository.sessionState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionState.Loading)
    val state: StateFlow<AppUiState> = kotlinx.coroutines.flow.combine(actionState, sessionState) { action, session ->
        action.copy(session = session)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiState())

    fun configureRelease(address: String) = runAction {
        val baseUrl = ServerAddress.normalize(address) ?: error("Ungültige feste Serveradresse.")
        repository.saveServer(baseUrl)
    }

    fun configureAndTest(address: String) = runAction {
        val baseUrl = ServerAddress.normalize(address) ?: error("Bitte eine vollständige Serveradresse eingeben.")
        repository.testConnection(baseUrl)
        repository.saveServer(baseUrl)
        actionState.value = actionState.value.copy(connectionMessage = "Server ist erreichbar.")
    }

    fun testCurrentServer() = runAction {
        val server = (state.value.session as? SessionState.NeedsAuthentication)?.server
            ?: (state.value.session as? SessionState.Authenticated)?.server
            ?: error("Zuerst eine Serveradresse festlegen.")
        repository.testConnection(server.baseUrl)
        actionState.value = actionState.value.copy(connectionMessage = "Server ist erreichbar.")
    }

    fun login(username: String, password: String) = runAction {
        require(username.isNotBlank() && password.isNotEmpty()) { "Bitte Benutzername und Passwort eingeben." }
        repository.login(requireServer(), username, password)
    }

    fun verifySession() = runAction {
        repository.verifySession(requireServer())
        actionState.value = actionState.value.copy(connectionMessage = "Gerät ist angemeldet.")
    }

    fun signOut() = runAction { repository.signOut() }

    fun clearMessages() {
        actionState.value = actionState.value.copy(errorMessage = null, connectionMessage = null)
    }

    private fun requireServer(): String = when (val value = state.value.session) {
        is SessionState.NeedsAuthentication -> value.server.baseUrl
        is SessionState.Authenticated -> value.server.baseUrl
        else -> error("Zuerst eine Serveradresse festlegen.")
    }

    private fun runAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            actionState.value = actionState.value.copy(isWorking = true, errorMessage = null, connectionMessage = null)
            try {
                block()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                repository.clearInvalidSessionIfUnauthorized(error)
                actionState.value = actionState.value.copy(errorMessage = error.userMessage())
            } finally {
                actionState.value = actionState.value.copy(isWorking = false)
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = PhotoSyncRepository(context.applicationContext, AppDatabase.get(context), SecureCredentialStore(context))
                return AppViewModel(repository) as T
            }
        }
    }
}

private fun Throwable.userMessage(): String = when (this) {
    is IllegalStateException, is IllegalArgumentException -> message ?: "Eingabe prüfen."
    is IOException -> "Server nicht erreichbar. Adresse und Netzwerk prüfen."
    is HttpException -> when (code()) {
        400 -> "Bitte Eingaben prüfen."
        401 -> "Benutzername oder Passwort ist falsch."
        409 -> "Dieser Vorgang ist nicht mehr möglich."
        429 -> "Zu viele Versuche. Bitte kurz warten."
        else -> "Der Server konnte die Anfrage nicht verarbeiten."
    }
    else -> "Unerwarteter Fehler. Bitte erneut versuchen."
}
