package org.nomad.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val container: AppContainer? get() = (application as NomadApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as NomadApp
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val c = app.container
                    if (c == null) {
                        StartError(app.startError ?: "неизвестная ошибка")
                    } else {
                        AppUi(c)
                    }
                }
            }
        }
    }

    // The connection lives while the app is on the screen; background work comes with push notifications.
    override fun onStart() {
        super.onStart()
        container?.start()
    }

    override fun onStop() {
        super.onStop()
        container?.stop()
    }
}

@Composable
fun StartError(message: String) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Не удалось открыть сохранённые данные", style = MaterialTheme.typography.titleLarge)
        Text(message)
        Text(
            "Приложение не создаёт новую идентичность молча: так вы бы потеряли доступ к прежним чатам. " +
                "Если данные остались от старой версии, очистите их: Параметры, Приложения, Nomad, Хранилище, Очистить данные.",
        )
    }
}

sealed interface Screen {
    data object Home : Screen
    data object Profile : Screen
    data object AddContact : Screen
    data object NewGroup : Screen
    data class Chat(val chatId: String) : Screen
}

@Composable
fun AppUi(app: AppContainer) {
    var stack by remember { mutableStateOf<List<Screen>>(listOf(Screen.Home)) }
    val screen = stack.last()
    fun push(s: Screen) {
        stack = stack + s
    }
    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }
    BackHandler(enabled = stack.size > 1) { pop() }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        val notice = app.notice.value
        if (notice != null) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(notice, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { app.notice.value = null }) { Text("Скрыть") }
            }
        }
        when (screen) {
            Screen.Home -> HomeScreen(
                app,
                onOpenChat = { push(Screen.Chat(it)) },
                onProfile = { push(Screen.Profile) },
                onAddContact = { push(Screen.AddContact) },
                onNewGroup = { push(Screen.NewGroup) },
            )
            Screen.Profile -> ProfileScreen(app, onBack = { pop() })
            Screen.AddContact -> AddContactScreen(
                app,
                onBack = { pop() },
                onAdded = { chatId -> stack = stack.dropLast(1) + Screen.Chat(chatId) },
            )
            Screen.NewGroup -> NewGroupScreen(
                app,
                onBack = { pop() },
                onCreated = { chatId -> stack = stack.dropLast(1) + Screen.Chat(chatId) },
            )
            is Screen.Chat -> ChatScreen(app, screen.chatId, onBack = { pop() })
        }
    }
}
