package us.leaf3stones.hy2droid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import us.leaf3stones.hy2droid.data.BlockRule

/**
 * Settings → Blocked traffic: flows matching a rule are refused inside the
 * tunnel (TCP gets a reset, UDP is dropped) and never reach the core.
 *
 * The motivating case is QUIC: browsers open UDP/443 first and Hysteria 2
 * cannot accelerate UDP, so QUIC is markedly slower than TCP on poor networks.
 * Blocking it makes them fall back to TCP/TLS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockRulesScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val rules by viewModel.blockRules.collectAsState()

    var tcp by remember { mutableStateOf(false) }
    var udp by remember { mutableStateOf(true) }
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }

    val fromPort = fromText.trim()
    val toPort = toText.trim()
    val candidate = BlockRule.fromInput(
        tcp = tcp,
        udp = udp,
        portText = if (toPort.isEmpty()) fromPort else "$fromPort-$toPort",
    )
    val showInvalid = (fromPort.isNotEmpty() || toPort.isNotEmpty()) && candidate == null
    val quicBlocked = rules.any { it.matches(BlockRule.QUIC.portFrom, udp = true) }

    fun add(rule: BlockRule) {
        if (rules.none { it == rule }) viewModel.setBlockRules(rules + rule)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.block_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
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
            Text(
                text = stringResource(R.string.block_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            if (rules.isEmpty()) {
                Text(
                    text = stringResource(R.string.block_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                rules.forEach { rule ->
                    RuleRow(rule = rule, onDelete = { viewModel.setBlockRules(rules - rule) })
                }
            }

            HorizontalDivider()
            RuleSectionHeader(stringResource(R.string.block_add_rule))

            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = tcp && !udp,
                    onClick = { tcp = true; udp = false },
                    label = { Text(stringResource(R.string.block_proto_tcp)) },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = udp && !tcp,
                    onClick = { tcp = false; udp = true },
                    label = { Text(stringResource(R.string.block_proto_udp)) },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = tcp && udp,
                    onClick = { tcp = true; udp = true },
                    label = { Text(stringResource(R.string.block_proto_both)) },
                )
            }

            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = fromText,
                    onValueChange = { fromText = it.trim() },
                    label = { Text(stringResource(R.string.block_port_from)) },
                    singleLine = true,
                    isError = showInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = toText,
                    onValueChange = { toText = it.trim() },
                    label = { Text(stringResource(R.string.block_port_to)) },
                    singleLine = true,
                    isError = showInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            if (showInvalid) {
                Text(
                    text = stringResource(R.string.block_port_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Button(
                    onClick = {
                        candidate?.let { add(it) }
                        fromText = ""
                        toText = ""
                    },
                    enabled = candidate != null,
                ) {
                    Text(stringResource(R.string.block_action_add))
                }
                if (!quicBlocked) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { add(BlockRule.QUIC) }) {
                        Text(stringResource(R.string.block_preset_quic))
                    }
                }
            }

            Text(
                text = stringResource(R.string.block_applies_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun RuleRow(rule: BlockRule, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${protoLabel(rule)} ${rule.portSpec}",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.action_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun protoLabel(rule: BlockRule): String = when {
    rule.tcp && rule.udp -> stringResource(R.string.block_proto_both)
    rule.udp -> stringResource(R.string.block_proto_udp)
    else -> stringResource(R.string.block_proto_tcp)
}

@Composable
private fun RuleSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}
