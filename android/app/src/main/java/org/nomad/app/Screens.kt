package org.nomad.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import org.nomad.client.ChatStore
import org.nomad.client.NomadEngine

private fun timeText(millis: Long): String =
    if (millis <= 0) "" else SimpleDateFormat("HH:mm", Locale.forLanguageTag("ru")).format(Date(millis))

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Nomad", text))
}

private fun readClipboard(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
}

@Composable
fun Header(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) { Text("← Назад") }
        }
        Text(
            title,
            Modifier.weight(1f).padding(horizontal = 8.dp),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
        )
        actions()
    }
}

// ============================================================ chat list

@Composable
fun HomeScreen(
    app: AppContainer,
    onOpenChat: (String) -> Unit,
    onProfile: () -> Unit,
    onAddContact: () -> Unit,
    onNewGroup: () -> Unit,
) {
    val tick = app.version.intValue
    val connection = app.connection.value
    val chats = remember(tick) { app.chats.chats() }
    Column(Modifier.fillMaxSize()) {
        Header("Nomad", null)
        Text(
            when (connection) {
                NomadEngine.ConnectionState.ONLINE -> "● Подключено"
                NomadEngine.ConnectionState.OFFLINE -> "○ Нет связи с узлом"
                else -> "◌ Подключение..."
            },
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onProfile) { Text("Профиль") }
            OutlinedButton(onClick = onAddContact) { Text("+ Контакт") }
            OutlinedButton(onClick = onNewGroup) { Text("+ Группа") }
        }
        if (chats.isEmpty()) {
            Text(
                "Пока нет чатов. Откройте «Профиль», отправьте своё приглашение близким " +
                    "и добавьте их приглашения через «+ Контакт».",
                Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(chats, key = { it.chatId() }) { chat ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onOpenChat(chat.chatId()) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(app.chatTitle(chat.chatId()), fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(chat.lastText(), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(timeText(chat.lastTime()), style = MaterialTheme.typography.bodySmall)
                        if (chat.unread() > 0) {
                            Text(
                                " ${chat.unread()} ",
                                Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}

// ============================================================ chat

@Composable
fun ChatScreen(app: AppContainer, chatId: String, onBack: () -> Unit) {
    val tick = app.version.intValue
    val messages = remember(tick) { app.chats.messages(chatId) }
    var text by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val isGroup = chatId.startsWith("g:")

    DisposableEffect(chatId) {
        app.chats.setOpenChat(chatId)
        onDispose { app.chats.setOpenChat(null) }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Header(app.chatTitle(chatId), onBack) {
            if (!isGroup) {
                TextButton(onClick = { renaming = true }) { Text("Имя") }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), state = listState) {
            items(messages, key = { it.id() }) { m ->
                val mine = m.outgoing()
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                ) {
                    Surface(
                        color = if (mine) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.widthIn(max = 300.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            if (isGroup && !mine) {
                                Text(
                                    app.nameOf(m.senderUid()),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(m.text())
                            val state = when (m.status()) {
                                ChatStore.STATUS_SENDING -> " · отправляется"
                                ChatStore.STATUS_FAILED -> " · не отправлено"
                                else -> ""
                            }
                            Text(timeText(m.time()) + state, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Сообщение") },
                maxLines = 4,
            )
            Spacer(Modifier.padding(4.dp))
            Button(
                enabled = text.isNotBlank(),
                onClick = {
                    val body = text.trim()
                    text = ""
                    if (isGroup) {
                        app.sendToGroup(chatId.removePrefix("g:"), body)
                    } else {
                        val contact = app.chats.contact(chatId.removePrefix("u:"))
                        if (contact != null) app.sendDirect(contact, body)
                    }
                },
            ) { Text("Отправить") }
        }
    }

    if (renaming) {
        val uid = chatId.removePrefix("u:")
        var name by remember { mutableStateOf(app.chats.contact(uid)?.name() ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Имя контакта") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val contact = app.chats.contact(uid)
                    if (contact != null) app.chats.addOrUpdateContact(contact.sigKey(), name, true)
                    renaming = false
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Отмена") } },
        )
    }
}

// ============================================================ profile

@Composable
fun ProfileScreen(app: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val tick = app.version.intValue
    var name by remember { mutableStateOf(app.chats.myName()) }
    var url by remember { mutableStateOf(app.chats.nodeUrl()) }
    var diagnostics by remember { mutableStateOf<List<SelfTest.Step>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    val link = remember(tick) { app.inviteLink() }

    Column(Modifier.fillMaxSize()) {
        Header("Профиль", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, label = { Text("Моё имя") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { app.chats.setMyName(name) }) { Text("Сохранить имя") }

            OutlinedTextField(
                value = url, onValueChange = { url = it }, label = { Text("Адрес узла") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { app.changeNodeUrl(url) }) { Text("Сохранить адрес и переподключиться") }

            Text("Моё приглашение", fontWeight = FontWeight.Bold)
            Text(
                "Отправьте эту ссылку тому, с кем хотите переписываться, любым другим мессенджером. " +
                    "В ней нет секретов.",
            )
            SelectionContainer { Text(link, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { copyToClipboard(context, link) }) { Text("Копировать") }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, link)
                    }
                    context.startActivity(Intent.createChooser(send, "Поделиться приглашением"))
                }) { Text("Поделиться") }
            }

            Text("Мой идентификатор: ${app.myUid}", style = MaterialTheme.typography.bodySmall)

            OutlinedButton(
                enabled = !running,
                onClick = {
                    running = true
                    diagnostics = emptyList()
                    thread {
                        val dir = File(context.cacheDir, "selftest-${System.nanoTime()}")
                        diagnostics = SelfTest.runAll(dir) + SelfTest.keystoreStep(context)
                        dir.deleteRecursively()
                        running = false
                    }
                },
            ) { Text(if (running) "Проверка..." else "Диагностика криптографии") }
            diagnostics.forEach { step ->
                Text(
                    (if (step.ok) "PASS  " else "FAIL  ") + step.name + "\n      " + step.detail,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ============================================================ add contact

@Composable
fun AddContactScreen(app: AppContainer, onBack: () -> Unit, onAdded: (String) -> Unit) {
    val context = LocalContext.current
    var link by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Header("Добавить контакт", onBack)
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Вставьте приглашение, которое вам прислали (ссылка вида nomad://invite?...).")
            OutlinedTextField(
                value = link,
                onValueChange = { link = it; error = null },
                label = { Text("Приглашение") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            OutlinedButton(onClick = { link = readClipboard(context); error = null }) { Text("Вставить из буфера") }
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Имя (если пусто, возьмётся из приглашения)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            val message = error
            if (message != null) {
                Text(message, color = MaterialTheme.colorScheme.error)
            }
            Button(
                enabled = link.isNotBlank(),
                onClick = {
                    try {
                        onAdded(app.addContactFromInvite(link, name))
                    } catch (e: IllegalArgumentException) {
                        error = e.message ?: "не удалось прочитать приглашение"
                    }
                },
            ) { Text("Добавить") }
        }
    }
}

// ============================================================ new group

@Composable
fun NewGroupScreen(app: AppContainer, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val tick = app.version.intValue
    val contacts = remember(tick) { app.chats.contacts() }
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Header("Новая группа", onBack)
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, label = { Text("Название группы") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Text("Участники (вы будете администратором; в группе до 10 человек):")
            if (contacts.isEmpty()) {
                Text("Сначала добавьте контакты.")
            }
            LazyColumn(Modifier.weight(1f)) {
                items(contacts, key = { it.uid() }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selected = if (c.uid() in selected) selected - c.uid() else selected + c.uid()
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = c.uid() in selected,
                            onCheckedChange = { selected = if (it) selected + c.uid() else selected - c.uid() },
                        )
                        Text(app.nameOf(c.uid()))
                    }
                }
            }
            val message = error
            if (message != null) {
                Text(message, color = MaterialTheme.colorScheme.error)
            }
            Button(
                enabled = selected.isNotEmpty() && selected.size <= 9 && !creating,
                onClick = {
                    creating = true
                    val members = contacts.filter { it.uid() in selected }
                    app.createGroup(name.ifBlank { "Группа" }, members) { chatId, failure ->
                        creating = false
                        if (chatId != null) onCreated(chatId) else error = failure
                    }
                },
            ) { Text("Создать") }
        }
    }
}
