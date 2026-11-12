package us.leaf3stones.hy2droid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import us.leaf3stones.hy2droid.R
import us.leaf3stones.hy2droid.data.HysteriaUri
import us.leaf3stones.hy2droid.data.Profile
import java.util.UUID

/** Add/edit form for a single profile. [initial] is null when creating a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    initial: Profile?,
    onSave: (Profile) -> Unit,
    onBack: () -> Unit,
) {
    val profileId = remember { initial?.id ?: UUID.randomUUID().toString() }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var server by remember { mutableStateOf(initial?.server.orEmpty()) }
    var port by remember { mutableStateOf((initial?.port ?: 443).toString()) }
    var auth by remember { mutableStateOf(initial?.auth.orEmpty()) }
    var sni by remember { mutableStateOf(initial?.sni.orEmpty()) }
    var obfs by remember { mutableStateOf(initial?.obfsPassword.orEmpty()) }
    var brutal by remember { mutableStateOf(initial?.brutal ?: false) }
    var tx by remember { mutableStateOf(initial?.txMbps?.takeIf { it > 0 }?.toString().orEmpty()) }
    var rx by remember { mutableStateOf(initial?.rxMbps?.takeIf { it > 0 }?.toString().orEmpty()) }
    var insecure by remember { mutableStateOf(initial?.insecure ?: false) }
    var fastOpen by remember { mutableStateOf(initial?.fastOpen ?: false) }
    var importLink by remember { mutableStateOf("") }
    var importFailed by remember { mutableStateOf(false) }

    // A port outside 1–65535 would only surface as a core error after Connect.
    val portValue = port.toIntOrNull()
    val portValid = portValue != null && portValue in 1..65535
    val valid = server.isNotBlank() && auth.isNotBlank() && portValid

    // The auth and obfs fields are visible here: keep them out of screenshots.
    SecureWindow()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (initial == null) R.string.edit_title_add else R.string.edit_title_edit))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A placeholder (not a label) is used here on purpose: a floating
                // label reserves 8dp above the outline, which would shift this box
                // down relative to the Import button next to it.
                OutlinedTextField(
                    value = importLink,
                    onValueChange = { importLink = it },
                    placeholder = { Text(stringResource(R.string.field_import_link)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        val parsed = HysteriaUri.parse(importLink)
                        importFailed = importLink.isNotBlank() && parsed == null
                        if (parsed != null) {
                            name = parsed.name
                            server = parsed.server
                            port = parsed.port.toString()
                            auth = parsed.auth
                            sni = parsed.sni
                            obfs = parsed.obfsPassword
                            insecure = parsed.insecure
                        }
                    },
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .height(56.dp),
                ) {
                    Text(stringResource(R.string.action_import))
                }
            }
            if (importFailed) {
                Text(
                    text = stringResource(R.string.import_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.field_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text(stringResource(R.string.field_server)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text(stringResource(R.string.field_port)) },
                singleLine = true,
                isError = port.isNotEmpty() && !portValid,
                supportingText = {
                    if (port.isNotEmpty() && !portValid) {
                        Text(stringResource(R.string.field_port_invalid))
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = auth,
                onValueChange = { auth = it },
                label = { Text(stringResource(R.string.field_auth)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = sni,
                onValueChange = { sni = it },
                label = { Text(stringResource(R.string.field_sni)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = obfs,
                onValueChange = { obfs = it },
                label = { Text(stringResource(R.string.field_obfs)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow(
                label = stringResource(R.string.field_brutal),
                checked = brutal,
                onCheckedChange = { brutal = it },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = tx,
                    onValueChange = { tx = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.field_tx)) },
                    singleLine = true,
                    enabled = brutal,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = rx,
                    onValueChange = { rx = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.field_rx)) },
                    singleLine = true,
                    enabled = brutal,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = stringResource(R.string.bandwidth_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow(
                label = stringResource(R.string.field_insecure),
                checked = insecure,
                onCheckedChange = { insecure = it },
            )
            SwitchRow(
                label = stringResource(R.string.field_fast_open),
                checked = fastOpen,
                onCheckedChange = { fastOpen = it },
            )
            Button(
                onClick = {
                    onSave(
                        Profile(
                            id = profileId,
                            name = name.ifBlank { server },
                            server = server.trim(),
                            port = portValue ?: 443,
                            auth = auth,
                            sni = sni.trim(),
                            obfsPassword = obfs.trim(),
                            insecure = insecure,
                            brutal = brutal,
                            txMbps = tx.toIntOrNull() ?: 0,
                            rxMbps = rx.toIntOrNull() ?: 0,
                            fastOpen = fastOpen,
                        ),
                    )
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

/** A label + switch row used for boolean options. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
