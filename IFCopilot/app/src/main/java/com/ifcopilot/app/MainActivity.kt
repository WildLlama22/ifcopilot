package com.ifcopilot.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Kick off the monitor service immediately so it's ready once connected.
        val intent = Intent(this, FlightMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)

        setContent {
            MaterialTheme {
                Surface {
                    CopilotScreen()
                }
            }
        }
    }
}

@Composable
fun CopilotScreen() {
    val scope = rememberCoroutineScope()
    var hostInput by remember { mutableStateOf("") }
    var portInput by remember { mutableStateOf("10112") }
    var connected by remember { mutableStateOf(false) }
    var discovering by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Not connected") }
    var selectedProfile by remember { mutableStateOf(AircraftProfiles.GENERIC) }
    var togaEngaged by remember { mutableStateOf(false) }

    var altAgl by remember { mutableStateOf(0.0) }
    var ias by remember { mutableStateOf(0.0) }
    var gearDown by remember { mutableStateOf(true) }
    var flapState by remember { mutableStateOf(0) }

    var vSpeedCallouts by remember { mutableStateOf(true) }
    var gearWarning by remember { mutableStateOf(true) }
    var retardCallout by remember { mutableStateOf(true) }
    var windshearWarning by remember { mutableStateOf(true) }
    var configConfirmations by remember { mutableStateOf(true) }

    // Poll shared service state for the UI readouts every 500ms
    LaunchedEffect(Unit) {
        while (true) {
            connected = FlightMonitorService.connected
            altAgl = FlightMonitorService.lastAltitudeAgl
            ias = FlightMonitorService.lastIas
            gearDown = FlightMonitorService.lastGearDown
            flapState = FlightMonitorService.lastFlapState
            delay(500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("IF Copilot", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "Unofficial companion app for Infinite Flight, using the Connect API.",
            fontSize = 13.sp
        )

        // --- Connection card ---
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Connection", fontWeight = FontWeight.Bold)
                Text(statusText, fontSize = 13.sp)

                Button(
                    onClick = {
                        discovering = true
                        scope.launch {
                            val host = FlightMonitorService.client.discover(8000)
                            discovering = false
                            if (host != null) {
                                hostInput = host.address
                                portInput = host.port.toString()
                                statusText = "Found ${host.deviceName.ifBlank { "device" }} — ${host.address}:${host.port}"
                            } else {
                                statusText = "No broadcast found. Check IF Connect is enabled and you're on the same WiFi, or enter the IP manually below."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !discovering
                ) {
                    Text(if (discovering) "Searching..." else "Auto-discover")
                }

                OutlinedTextField(
                    value = hostInput, onValueChange = { hostInput = it },
                    label = { Text("Host / IP") }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = portInput, onValueChange = { portInput = it },
                    label = { Text("Port") }, modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        scope.launch {
                            try {
                                statusText = "Connecting..."
                                FlightMonitorService.client.connect(hostInput, portInput.toIntOrNull() ?: 10112)
                                statusText = "Connected. Resolving aircraft..."
                                val skipped = FlightMonitorService.client.lastSkippedUnknownTypeCount
                                val nameEntry = FlightMonitorService.client.resolve("aircraft/0/name")
                                val name = nameEntry?.let { FlightMonitorService.client.getState(it)?.asString() } ?: ""
                                selectedProfile = AircraftProfiles.matchByName(name)
                                FlightMonitorService.currentProfile = selectedProfile
                                val skippedNote = if (skipped > 0) " ($skipped manifest entries had an unrecognized type and were skipped)" else ""
                                statusText = "Connected — detected: ${name.ifBlank { "unknown" }} → using ${selectedProfile.displayName} profile$skippedNote"
                            } catch (e: Exception) {
                                statusText = "Connection failed: ${e.message}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Connect")
                }
            }
        }

        // --- Aircraft profile ---
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Aircraft profile", fontWeight = FontWeight.Bold)
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selectedProfile.displayName)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        AircraftProfiles.ALL.forEach { profile ->
                            DropdownMenuItem(
                                text = { Text(profile.displayName) },
                                onClick = {
                                    selectedProfile = profile
                                    FlightMonitorService.currentProfile = profile
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                Text(
                    "Reference weight ${selectedProfile.referenceWeightKg} kg (fixed — not read from the sim). " +
                        "V1 ${selectedProfile.v1} kt · VR ${selectedProfile.vr} kt · V2 ${selectedProfile.v2} kt. " +
                        "Approximate values for immersion, not real performance data.",
                    fontSize = 12.sp
                )
            }
        }

        // --- TOGA button ---
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("TOGA", fontWeight = FontWeight.Bold)
                Text("Ramps throttle smoothly to 95% over 3 seconds.", fontSize = 12.sp)
                Button(
                    onClick = {
                        togaEngaged = true
                        FlightMonitorService.toga?.engage(
                            scope = CoroutineScope(Dispatchers.IO),
                            targetFraction = 0.95f,
                            durationMs = 3000L,
                            onDone = { togaEngaged = false }
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    enabled = connected
                ) {
                    Text(if (togaEngaged) "TOGA ENGAGED..." else "TOGA", fontSize = 20.sp)
                }
            }
        }

        // --- Live status ---
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Live status", fontWeight = FontWeight.Bold)
                Text("Altitude AGL: ${"%.0f".format(altAgl)} ft")
                Text("Indicated airspeed: ${"%.0f".format(ias)} kt")
                Text("Gear: ${if (gearDown) "DOWN" else "UP"}")
                Text("Flap state index: $flapState")
            }
        }

        // --- Feature toggles ---
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Features", fontWeight = FontWeight.Bold)
                ToggleRow("V-speed callouts (80/100, V1, Rotate, Positive rate)", vSpeedCallouts) {
                    vSpeedCallouts = it
                    FlightMonitorService.enabledFeatures.vSpeedCallouts = it
                }
                ToggleRow("Gear warning below 500ft AGL", gearWarning) {
                    gearWarning = it
                    FlightMonitorService.enabledFeatures.gearWarning = it
                }
                ToggleRow("Retard callout (Airbus)", retardCallout) {
                    retardCallout = it
                    FlightMonitorService.enabledFeatures.retardCallout = it
                }
                ToggleRow("Windshear warning", windshearWarning) {
                    windshearWarning = it
                    FlightMonitorService.enabledFeatures.windshearWarning = it
                }
                ToggleRow("Gear up/down & flaps confirmations", configConfirmations) {
                    configConfirmations = it
                    FlightMonitorService.enabledFeatures.configConfirmations = it
                }
            }
        }
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
