package com.legion.orion.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Providers", style = MaterialTheme.typography.headlineMedium)
            Text(
                "API keys are encrypted with the Android Keystore and never stored in plaintext.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(ui.rows, key = { it.provider.id }) { row ->
            ProviderCard(
                label = row.provider.label,
                hasKey = row.hasKey,
                needsBaseUrl = row.needsBaseUrl,
                baseUrl = row.provider.baseUrl.orEmpty(),
                onSaveKey = { vm.saveKey(row.provider, it) },
                onSaveBaseUrl = { vm.saveBaseUrl(row.provider, it) },
            )
        }
    }
}

@Composable
private fun ProviderCard(
    label: String,
    hasKey: Boolean,
    needsBaseUrl: Boolean,
    baseUrl: String,
    onSaveKey: (String) -> Unit,
    onSaveBaseUrl: (String) -> Unit,
) {
    var key by rememberSaveable(label) { mutableStateOf("") }
    var url by rememberSaveable(label) { mutableStateOf(baseUrl) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                if (hasKey) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Key set",
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            if (needsBaseUrl) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Base URL (e.g. http://192.168.1.10:1234/v1)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { onSaveBaseUrl(url) }, enabled = url.isNotBlank()) { Text("Save URL") }
            }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(if (hasKey) "Replace API key" else "API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { onSaveKey(key); key = "" }, enabled = key.isNotBlank()) {
                Text("Save key")
            }
        }
    }
}
