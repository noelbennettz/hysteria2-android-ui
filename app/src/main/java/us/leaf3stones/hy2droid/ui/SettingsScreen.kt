package us.leaf3stones.hy2droid.ui

import android.net.InetAddresses
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import us.leaf3stones.hy2droid.R
import us.leaf3stones.hy2droid.data.DiagnosticsExporter
import us.leaf3stones.hy2droid.data.LanRanges
import us.leaf3stones.hy2droid.data.SettingsStore
import us.leaf3stones.hy2droid.proxy.TunnelState

/** Returns true when [value] is a numeric IPv4 or IPv6 address. */
private fun isValidIp(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return false
    return try {
        InetAddresses.isNumericAddress(trimmed)
    } catch (_: Exception) {
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenSplitTunnel: () -> Unit,
    onOpenBlockRules: () -> Unit,
) {
    val dynamicColor by viewModel.dynamicColor.collectAsState()
    val showServerAddress by viewModel.showServerAddress.collectAsState()
    val showTraffic by viewModel.showTraffic.collectAsState()
    val dnsEnabled by viewModel.customDnsEnabled.collectAsState()
    val dns1 by viewModel.customDnsServer.collectAsState()
    val dns2 by viewModel.customDnsServer2.collectAsState()
    val localDns by viewModel.dnsCacheEnabled.collectAsState()
    val bypassLan by viewModel.bypassLan.collectAsState()
    val autoTestLatency by viewModel.autoTestLatency.collectAsState()
    val disablePmtud by viewModel.disablePathMtuDiscovery.collectAsState()
    val mtu by viewModel.mtu.collectAsState()
    val tcpBufferKb by viewModel.tcpBufferKb.collectAsState()
    val tunnelState by viewModel.tunnelState.collectAsState()
    val active = tunnelState is TunnelState.Connected || tunnelState is TunnelState.Connecting
    var mtuDialogOpen by remember { mutableStateOf(false) }
    var tcpBufferDialogOpen by remember { mutableStateOf(false) }

    // SAF: the user picks where the zip goes, so there is no storage permission
    // to ask for and no FileProvider to declare.
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportDiagnostics(uri) { ok ->
                Toast.makeText(
                    context,
                    if (ok) R.string.export_success else R.string.export_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    var dns1Text by remember { mutableStateOf("") }
    var dns2Text by remember { mutableStateOf("") }
    var dnsInitialized by remember { mutableStateOf(false) }
    LaunchedEffect(dns1, dns2) {
        if (!dnsInitialized) {
            dns1Text = dns1
            dns2Text = dns2
            dnsInitialized = true
        }
    }

    // Option A: only turn the custom-DNS switch off when leaving with an
    // invalid / unsaved configuration; a valid saved config persists.
    val enabledState = rememberUpdatedState(dnsEnabled)
    val dns1State = rememberUpdatedState(dns1Text)
    val dns2State = rememberUpdatedState(dns2Text)
    DisposableEffect(Unit) {
        onDispose {
            if (enabledState.value) {
                val ok1 = isValidIp(dns1State.value)
                val ok2 = dns2State.value.isBlank() || isValidIp(dns2State.value)
                if (!(ok1 && ok2)) {
                    viewModel.setCustomDnsEnabled(false)
                }
            }
        }
    }

    val dns1Valid = isValidIp(dns1Text)
    val dns2Valid = dns2Text.isBlank() || isValidIp(dns2Text)
    val dnsSavable = dns1Valid && dns2Valid

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.settings_section_appearance))
            SwitchRow(
                icon = Icons.Filled.Palette,
                title = stringResource(R.string.settings_dynamic_color),
                subtitle = null,
                checked = dynamicColor,
                onCheckedChange = viewModel::setDynamicColor,
            )
            HorizontalDivider()
            SwitchRow(
                icon = Icons.Filled.Public,
                title = stringResource(R.string.settings_show_address),
                subtitle = stringResource(R.string.settings_show_address_summary),
                checked = showServerAddress,
                onCheckedChange = viewModel::setShowServerAddress,
            )
            HorizontalDivider()
            SwitchRow(
                icon = Icons.Filled.Speed,
                title = stringResource(R.string.settings_show_traffic),
                subtitle = stringResource(R.string.settings_show_traffic_summary),
                checked = showTraffic,
                onCheckedChange = viewModel::setShowTraffic,
            )
            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_section_network))
            // Everything below is read when the interface is created, so while a
            // tunnel is up the switches look applied but are not.
            if (active) {
                Text(
                    text = stringResource(R.string.block_applies_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
            ClickRow(
                icon = Icons.Filled.Apps,
                title = stringResource(R.string.split_tunnel_title),
                subtitle = stringResource(R.string.split_tunnel_summary),
                onClick = onOpenSplitTunnel,
            )
            HorizontalDivider()
            ClickRow(
                icon = Icons.Filled.Block,
                title = stringResource(R.string.settings_blocked_traffic),
                subtitle = stringResource(R.string.settings_blocked_traffic_summary),
                onClick = onOpenBlockRules,
            )
            HorizontalDivider()
            // `excludeRoute()` (a true bypass) only exists from Android 13; on older
            // releases the row stays visible but disabled, so the feature is
            // discoverable instead of silently missing.
            val lanSupported = Build.VERSION.SDK_INT >= LanRanges.MIN_API
            SwitchRow(
                icon = Icons.Filled.Lan,
                title = stringResource(R.string.settings_bypass_lan),
                subtitle = if (lanSupported) {
                    stringResource(R.string.settings_bypass_lan_summary)
                } else {
                    stringResource(R.string.settings_requires_android_13)
                },
                checked = lanSupported && bypassLan,
                enabled = lanSupported,
                onCheckedChange = viewModel::setBypassLan,
            )
            HorizontalDivider()
            ClickRow(
                icon = Icons.Filled.Straighten,
                title = stringResource(R.string.settings_mtu),
                subtitle = stringResource(R.string.settings_mtu_summary, mtu),
                onClick = { mtuDialogOpen = true },
            )
            HorizontalDivider()
            ClickRow(
                icon = Icons.Filled.SwapVert,
                title = stringResource(R.string.settings_tcp_buffer),
                subtitle = stringResource(R.string.settings_tcp_buffer_summary, tcpBufferKb),
                onClick = { tcpBufferDialogOpen = true },
            )
            HorizontalDivider()
            SwitchRow(
                icon = Icons.Filled.SettingsEthernet,
                title = stringResource(R.string.settings_disable_pmtud),
                subtitle = stringResource(R.string.settings_disable_pmtud_summary),
                checked = disablePmtud,
                onCheckedChange = viewModel::setDisablePathMtuDiscovery,
            )
            HorizontalDivider()
            SwitchRow(
                icon = Icons.Filled.Dns,
                title = stringResource(R.string.settings_dns_cache),
                subtitle = stringResource(R.string.settings_dns_cache_summary),
                checked = localDns,
                // A custom resolver wins over the mode switch, so the switch is
                // shown disabled rather than pretending it still applies.
                enabled = !dnsEnabled,
                onCheckedChange = viewModel::setDnsCacheEnabled,
            )
            HorizontalDivider()
            SwitchRow(
                icon = Icons.Filled.Dns,
                title = stringResource(R.string.settings_custom_dns),
                subtitle = stringResource(
                    R.string.settings_custom_dns_summary,
                    SettingsStore.DEFAULT_DNS_PRIMARY,
                    SettingsStore.DEFAULT_DNS_SECONDARY,
                ),
                checked = dnsEnabled,
                onCheckedChange = viewModel::setCustomDnsEnabled,
            )
            if (dnsEnabled) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    DnsServerField(
                        value = dns1Text,
                        onValueChange = { dns1Text = it.trim() },
                        labelRes = R.string.settings_dns_primary,
                        valid = dns1Valid,
                    )
                    Spacer(Modifier.height(8.dp))
                    DnsServerField(
                        value = dns2Text,
                        onValueChange = { dns2Text = it.trim() },
                        labelRes = R.string.settings_dns_secondary,
                        valid = dns2Valid,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            viewModel.setCustomDnsServer(dns1Text)
                            viewModel.setCustomDnsServer2(dns2Text)
                            viewModel.setCustomDnsEnabled(true)
                        },
                        enabled = dnsSavable,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            HorizontalDivider()
            SwitchRow(
                icon = Icons.Filled.Timer,
                title = stringResource(R.string.settings_auto_test_latency),
                subtitle = stringResource(R.string.settings_auto_test_latency_summary),
                checked = autoTestLatency,
                onCheckedChange = viewModel::setAutoTestLatency,
            )
            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_section_diagnostics))
            ClickRow(
                icon = Icons.Filled.BugReport,
                title = stringResource(R.string.settings_export_logs),
                subtitle = stringResource(R.string.settings_export_logs_summary),
                onClick = {
                    try {
                        exportLauncher.launch(DiagnosticsExporter.suggestedFileName())
                    } catch (_: Exception) {
                        // No document provider on this build: tell the user instead
                        // of letting ActivityNotFoundException kill the app.
                        Toast.makeText(context, R.string.export_failed, Toast.LENGTH_LONG).show()
                    }
                },
            )
            HorizontalDivider()
        }
    }

    if (tcpBufferDialogOpen) {
        AlertDialog(
            onDismissRequest = { tcpBufferDialogOpen = false },
            title = { Text(stringResource(R.string.settings_tcp_buffer)) },
            text = {
                Column {
                    SettingsStore.TCP_BUFFER_CHOICES_KB.forEach { kb ->
                        Text(
                            text = stringResource(R.string.settings_tcp_buffer_option, kb),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (kb == tcpBufferKb) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setTcpBufferKb(kb)
                                    tcpBufferDialogOpen = false
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { tcpBufferDialogOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (mtuDialogOpen) {
        var mtuText by remember { mutableStateOf(mtu.toString()) }
        val parsed = mtuText.trim().toIntOrNull()
        val mtuValid = parsed != null && parsed in SettingsStore.MIN_MTU..SettingsStore.MAX_MTU
        AlertDialog(
            onDismissRequest = { mtuDialogOpen = false },
            title = { Text(stringResource(R.string.settings_mtu)) },
            text = {
                OutlinedTextField(
                    value = mtuText,
                    onValueChange = { mtuText = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.settings_mtu)) },
                    singleLine = true,
                    isError = !mtuValid,
                    supportingText = { Text(stringResource(R.string.settings_mtu_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        parsed?.let(viewModel::setMtu)
                        mtuDialogOpen = false
                    },
                    enabled = mtuValid,
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { mtuDialogOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** One DNS server input; the primary and the secondary differ only in the label. */
@Composable
private fun DnsServerField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes labelRes: Int,
    valid: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        isError = !valid,
        supportingText = {
            if (!valid) Text(stringResource(R.string.settings_dns_invalid))
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun ClickRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
