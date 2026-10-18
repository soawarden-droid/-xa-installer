package com.xa.installer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { XaScreen(this) }
    }

    fun install(apks: List<File>): String {
        if (apks.isEmpty()) return "No APK components found."
        if (!packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return "Allow XA to install apps in Android settings, then tap Install again."
        }
        return try {
            val installer = packageManager.packageInstaller
            val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val id = installer.createSession(params)
            try {
                installer.openSession(id).use { session ->
                    apks.forEachIndexed { index, file ->
                        file.inputStream().use { input ->
                            session.openWrite("component-$index.apk", 0, file.length()).use { output ->
                                input.copyTo(output, 64 * 1024)
                                session.fsync(output)
                            }
                        }
                    }
                    val callbackIntent = Intent(this, InstallResultReceiver::class.java).apply {
                        action = "com.xa.installer.INSTALL_RESULT"
                        setPackage(packageName)
                    }
                    val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                    val pending = android.app.PendingIntent.getBroadcast(this, id, callbackIntent, flags)
                    session.commit(pending.intentSender)
                }
                "Installation submitted. Confirm in the Android system dialog."
            } catch (e: Exception) {
                try { installer.abandonSession(id) } catch (_: Exception) {}
                throw e
            }
        } catch (e: Exception) { "Installation failed: ${e.message ?: e.javaClass.simpleName}" }
    }
}

@Composable
fun XaScreen(activity: MainActivity) {
    val purple = Color(0xFFAA8CFF)
    var details by remember { mutableStateOf("Choose an APK, APKS, or XAPK to inspect.") }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            files = emptyList()
            busy = true
            details = "Inspecting selected package…"
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { PackageReader.read(activity, uri) }
                    files = result.first
                    details = result.second
                } catch (e: Exception) {
                    details = "Unable to inspect package: ${e.message}"
                } finally { busy = false }
            }
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = purple, background = Color(0xFF0B0B14), surface = Color(0xFF211B34))) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0B0B14)).verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Spacer(Modifier.height(12.dp))
            Text("XA", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black, color = purple)
            Text("UNIVERSAL ANDROID INSTALLER", style = MaterialTheme.typography.labelLarge)
            Text("Install Anything Android Supports.", style = MaterialTheme.typography.titleMedium)
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF211B34))) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Package engine", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Select a package to inspect its contents before installing.")
                    Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("SELECT PACKAGE") }
                    Text(details)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (files.isNotEmpty() && !busy) {
                        Text("${files.size} APK component(s) ready")
                        Button(onClick = { details = activity.install(files) }, modifier = Modifier.fillMaxWidth()) { Text("INSTALL WITH ANDROID") }
                    }
                }
            }
            Text("Security first", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("XA uses Android's official PackageInstaller. Your device controls permissions, compatibility and final installation approval.")
            Text("Supported in this build: APK, basic APKS and XAPK archives containing APK components. OBB asset deployment and proprietary APKM formats are not implemented.", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
        }
    }
}