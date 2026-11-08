package dev.prowsee.permanent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.prowsee.permanent.proxy.ProxyMode
import dev.prowsee.permanent.proxy.ProxyProfile
import dev.prowsee.permanent.proxy.ProxyStatus
import dev.prowsee.permanent.proxy.RootProxy
import dev.prowsee.permanent.proxy.SubscriptionParser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxySheet(
    status: ProxyStatus,
    profiles: List<ProxyProfile>,
    activeProfile: ProxyProfile?,
    activeMode: ProxyMode,
    onApply: (ProxyProfile, ProxyMode) -> Unit,
    onDisable: () -> Unit,
    onAddProfiles: (List<ProxyProfile>) -> Unit,
    onRemoveProfile: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var importText by remember { mutableStateOf("") }
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("") }
    var manualScheme by remember { mutableStateOf("socks5") }
    var selectedMode by remember { mutableStateOf(activeMode) }
    var rootCheck by remember { mutableStateOf<RootProxy.RootCheck?>(null) }
    var importSummary by remember { mutableStateOf<String?>(null) }
    var showImport by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Proxy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onDismiss) { Text("Done") }
            }

            StatusCard(status)

            Spacer(Modifier.height(16.dp))
            Text(
                "HOW IT APPLIES",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProxyMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            selectedMode = mode
                            if (mode == ProxyMode.ROOT_REDIRECT) rootCheck = RootProxy.checkRoot()
                        }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedMode == mode,
                        onClick = {
                            selectedMode = mode
                            if (mode == ProxyMode.ROOT_REDIRECT) rootCheck = RootProxy.checkRoot()
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(mode.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            mode.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            rootCheck?.let { check ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (check.available) {
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
                            } else {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
                            },
                        )
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        check.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "YOUR SERVERS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "You supply these. A proxy operator can see every request you make " +
                    "through it, so only use a server you control or genuinely trust.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            if (profiles.isEmpty()) {
                Text(
                    "No servers yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                    items(profiles, key = { it.id }) { profile ->
                        val isActive = activeProfile?.id == profile.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    if (selectedMode != ProxyMode.OFF) onApply(profile, selectedMode)
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isActive) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    profile.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "${profile.scheme.uriName}://${profile.display()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onRemoveProfile(profile.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { showImport = !showImport },
                    modifier = Modifier.weight(1f),
                ) { Text(if (showImport) "Close import" else "Import") }
                Button(
                    onClick = { onDisable() },
                    modifier = Modifier.weight(1f),
                ) { Text("Turn off") }
            }

            if (showImport) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "ADD A SERVER",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = { manualHost = it },
                        label = { Text("Host") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = manualPort,
                        onValueChange = { manualPort = it.filter(Char::isDigit) },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("socks5", "http", "https").forEach { scheme ->
                        val selected = manualScheme == scheme
                        TextButton(
                            onClick = { manualScheme = scheme },
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent,
                                ),
                        ) { Text(scheme) }
                    }
                }
                Button(
                    onClick = {
                        val port = manualPort.toIntOrNull()
                        if (manualHost.isNotBlank() && port != null && port in 1..65535) {
                            ProxyProfile.parse("$manualScheme://${manualHost.trim()}:$port")
                                ?.let { onAddProfiles(listOf(it)) }
                            manualHost = ""
                            manualPort = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add server") }

                Spacer(Modifier.height(12.dp))
                Text(
                    "IMPORT FROM A SUBSCRIPTION",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Paste a subscription URI or a list of servers you already have. " +
                        "Nothing is downloaded automatically.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    label = { Text("Subscription or server list") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val result = SubscriptionParser.parse(importText)
                        if (result.profiles.isNotEmpty()) onAddProfiles(result.profiles)
                        importSummary = when {
                            result.profiles.isEmpty() && result.skipped.isEmpty() -> "Nothing to import."
                            result.profiles.isEmpty() ->
                                "None of these can be used as a browser proxy " +
                                    "(${result.skipped.size} skipped)."
                            result.skipped.isEmpty() -> "Added ${result.profiles.size}."
                            else -> "Added ${result.profiles.size}, skipped ${result.skipped.size}."
                        }
                        importText = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Import") }
                importSummary?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "WHAT THIS CANNOT DO",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "IP spoofing is not possible over TCP: a forged source address means " +
                    "replies never come back, so no connection forms. Any app claiming " +
                    "otherwise is showing you a proxy with a different label. This uses " +
                    "a real proxy you control, which actually hides your address from " +
                    "sites because the request genuinely comes from the proxy.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusCard(status: ProxyStatus) {
    val quad = when (status) {
        is ProxyStatus.Off -> Quad(
            MaterialTheme.colorScheme.surfaceVariant,
            null,
            "Proxy off",
            "Traffic goes straight out.",
        )
        is ProxyStatus.Applying -> Quad(
            MaterialTheme.colorScheme.surfaceVariant,
            null,
            "Applying…",
            "Waiting for the system to confirm.",
        )
        is ProxyStatus.Active -> Quad(
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
            Icons.Default.CheckCircle,
            if (status.verified) "Active and verified" else "Active",
            "${status.profile.label} · ${status.mode.label}",
        )
        is ProxyStatus.Failed -> Quad(
            MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
            Icons.Default.Error,
            "Not applied",
            status.reason + (status.hint?.let { "  $it" } ?: ""),
        )
    }

    val bg = quad.a
    val icon = quad.b
    val title = quad.c
    val detail = quad.d

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
            }
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class Quad<A, B, C, D>(
    val a: A,
    val b: B,
    val c: C,
    val d: D,
)
