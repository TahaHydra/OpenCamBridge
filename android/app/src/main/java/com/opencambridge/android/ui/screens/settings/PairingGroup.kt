package com.opencambridge.android.ui.screens.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.opencambridge.android.pairing.*
import com.opencambridge.android.service.ServiceBridge
import com.opencambridge.android.ui.components.SettingsGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PairingGroup(running: Boolean, port: Int, wifi: Boolean) {
    val context = LocalContext.current
    val storage = remember { runCatching { PairingStore.get(context) } }
    val store = storage.getOrNull()
    if (store == null) {
        SettingsGroup(title = "Paired computers") {
            Text("Secure pairing storage is unavailable. Existing pairings have not been erased.", Modifier.padding(16.dp))
        }
        return
    }
    val pcs by store.pcs.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(false) }
    var ip by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var endpoints by remember { mutableStateOf(emptyList<PairingEndpoint>()) }
    var selected by remember { mutableStateOf<PairingEndpoint?>(null) }
    var pending by remember { mutableStateOf<PendingPairing?>(null) }

    fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Pairing could not finish. Check the code, private PC IP and firewall, then open a new invitation on your PC. Saved pairings remain available below." }
            finally { busy = false }
        }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { contents ->
            if (ServiceBridge.isServiceRunning) message = "Stop capture before scanning a QR code."
            else runAction {
                val invitation = PairingInput.qr(contents)
                var authenticated: PendingPairing? = null
                for (host in invitation.hosts) {
                    try {
                        authenticated = withContext(Dispatchers.IO) {
                            PairingClient.authenticate(PairingEndpoint(host, invitation.port, invitation.id, ""), invitation.secret, "qr")
                        }
                        break
                    } catch (e: java.io.IOException) { /* Another advertised local interface may be reachable. */ }
                }
                pending = checkNotNull(authenticated)
            }
        }
    }
    DisposableEffect(pending) { val session = pending; onDispose { session?.close() } }
    LaunchedEffect(pending) {
        if (pending != null) {
            delay(115_000)
            pending?.close(); pending = null
            message = "Pairing expired. Open a new invitation on your PC."
        }
    }
    SettingsGroup(title = "Paired computers", footer = "Pairing never starts capture. Wi-Fi mode is required when you later connect over the network; streaming HTTP is for a trusted LAN.") {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !running && !busy, onClick = {
                    if (!ServiceBridge.isServiceRunning) scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan the pairing QR code shown by OpenCamBridge on your PC")
                        .setBeepEnabled(false).setBarcodeImageEnabled(false))
                }) { Text("Scan PC QR") }
                TextButton(enabled = !busy, onClick = { manual = true; endpoints = emptyList(); selected = null; code = "" }) { Text("Enter PC code") }
            }
            if (running) Text("Stop capture to scan. Entering a code works while streaming.", style = MaterialTheme.typography.bodySmall)
            if (busy) Text("Working…")
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (pcs.isEmpty()) Text("No paired computers")
            pcs.forEach { pc ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text(pc.name); Text("PC ${pc.pcId.take(8)}", style = MaterialTheme.typography.bodySmall) }
                    TextButton(enabled = !busy, onClick = { runAction {
                        withContext(Dispatchers.IO) { store.revoke(pc.pcId) }
                        message = "${pc.name} revoked. Its paired connections are closed."
                    } }) { Text("Revoke") }
                }
            }
        }
    }
    if (manual) AlertDialog(
        onDismissRequest = { if (!busy) manual = false },
        title = { Text("Enter PC pairing code") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Open Pair phone on your computer, then find it below. If discovery fails, enter its private IPv4 address.")
                OutlinedTextField(value = ip, onValueChange = { ip = it.take(15); selected = null; endpoints = emptyList() },
                    label = { Text("PC IP (optional)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                TextButton(enabled = !busy, onClick = { runAction {
                    endpoints = PairingClient.discover(ip.trim())
                    selected = endpoints.singleOrNull()
                    if (endpoints.isEmpty()) message = "No invitation found. Check the PC IP and allow the pairing app through the private-network firewall."
                } }) { Text("Find PCs") }
                endpoints.forEach { endpoint ->
                    TextButton(enabled = !busy, onClick = { selected = endpoint }) {
                        Text("${if (selected == endpoint) "✓ " else ""}${endpoint.name} · ${endpoint.host}")
                    }
                }
                Text("Discovery names are unverified. The authenticated name appears after entering the code.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = code, onValueChange = { code = it.filter { c -> c in '0'..'9' || c == ' ' }.take(9) },
                    label = { Text("8-digit PC code") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                message?.let { Text(it) }
            }
        },
        confirmButton = { TextButton(enabled = !busy && selected != null && code.replace(" ", "").length == 8, onClick = { runAction {
            pending = withContext(Dispatchers.IO) { PairingClient.authenticate(selected!!, PairingInput.code(code), "code") }
            code = ""; manual = false
        } }) { Text(if (busy) "Working…" else "Verify PC") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { manual = false; code = "" }) { Text("Cancel") } }
    )
    pending?.let { session ->
        AlertDialog(
            onDismissRequest = { if (!busy) { session.close(); pending = null } },
            title = { Text("Allow ${session.name}?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This PC authenticated with your pairing code or QR secret. Approve only if this is the computer you intended to pair.")
                Text("PC ${session.pcId.take(8)}")
                if (!wifi) Text("Wi-Fi mode is currently off. Pairing saves this PC; select Wi-Fi yourself before connecting over LAN.")
                message?.let { Text(it) }
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = { runAction {
                session.approve(store, Build.MODEL.take(80).ifBlank { "Android phone" }, port)
                pending = null
                message = "Paired with ${session.name}. Start capture when you are ready to connect."
            } }) { Text(if (busy) "Saving…" else "Approve PC") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { session.close(); pending = null }) { Text("Cancel") } }
        )
    }
}
