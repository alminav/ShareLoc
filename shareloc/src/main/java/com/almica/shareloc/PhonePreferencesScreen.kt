package com.almica.shareloc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhonePreferencesScreen(
    onNavigateBack: () -> Unit,
    onNumberSelected: (String) -> Unit, // <-- Callback für die Auswahl einer Nummer
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferencesManager = remember { PhonePreferencesManager(context) }
    val phoneNumbers by preferencesManager.phoneNumbersFlow.collectAsState(initial = emptyList())
    var newPhoneNumber by remember { mutableStateOf("") }
    BackHandler { onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Telefonnummer wählen") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Zurück"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Bereich zum Hinzufügen einer Nummer
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = newPhoneNumber,
                    onValueChange = { newPhoneNumber = it },
                    label = { Text("Neue Telefonnummer") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        if (newPhoneNumber.isNotBlank()) {
                            val updatedList = phoneNumbers + newPhoneNumber.trim()
                            scope.launch {
                                preferencesManager.savePhoneNumbers(updatedList)
                                newPhoneNumber = ""
                            }
                        }
                    }
                ) {
                    Text("Hinzufügen")
                }
            }

            Text(text = "Gespeicherte Nummern (Tippen zum Auswählen):", style = MaterialTheme.typography.titleMedium)

            // Liste der Nummern
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (phoneNumbers.isEmpty()) {
                    item {
                        Text(
                            text = "Keine Nummern hinterlegt.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(phoneNumbers) { number ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNumberSelected(number) } // <-- Klick-Logik für Auswahl
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = number, style = MaterialTheme.typography.bodyLarge)

                                // Löschen-Button (verhindert das Auslösen des Card-Klicks)
                                IconButton(
                                    onClick = {
                                        val updatedList = phoneNumbers.filter { it != number }
                                        scope.launch { preferencesManager.savePhoneNumbers(updatedList) }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Nummer löschen",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
