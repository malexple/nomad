package org.nomad.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(applicationContext)
                }
            }
        }
    }
}

@Composable
fun HomeScreen(context: Context) {
    var status by remember { mutableStateOf("opening the saved state...") }
    var uid by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SelfTest.Step>>(emptyList()) }
    var running by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            try {
                val repository = StateRepository.forContext(context)
                uid = repository.state.identity.uid()
                status = if (repository.loadedFromDisk) {
                    "State restored from disk. This device keeps the same identity after a restart."
                } else {
                    "New identity created and saved (encrypted, key in the Android Keystore)."
                }
            } catch (e: Exception) {
                status = "ERROR, the saved state cannot be opened: $e"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Nomad", style = MaterialTheme.typography.headlineMedium)
        Text(status)
        if (uid.isNotEmpty()) {
            Text("uid: $uid")
        }
        Button(
            enabled = !running,
            onClick = {
                running = true
                results = emptyList()
                thread {
                    val dir = File(context.cacheDir, "selftest-${System.nanoTime()}")
                    results = SelfTest.runAll(dir) + SelfTest.keystoreStep(context)
                    dir.deleteRecursively()
                    running = false
                }
            },
        ) {
            Text(if (running) "Running..." else "Run crypto self-test on this device")
        }
        results.forEach { step ->
            Text((if (step.ok) "PASS  " else "FAIL  ") + step.name + "\n      " + step.detail)
        }
    }
}
