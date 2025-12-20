package com.kokoros

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kokoros.ui.theme.KokorosTTSTheme
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KokorosTTSTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("KokoroPrefs", Context.MODE_PRIVATE) }

    // Load initial values
    var selectedVoice by remember { mutableStateOf(prefs.getString("voice_skin", "af_sky") ?: "af_sky") }
    var speedMultiplier by remember { mutableFloatStateOf(prefs.getFloat("speed_multiplier", 1.0f)) }

    val voices = listOf(
        "af_heart", "af_sky", "af_bella", "af_nicole", "af_sarah",
        "am_adam", "am_michael",
        "bf_emma", "bf_isabella",
        "bm_george", "bm_lewis"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "Kokoro TTS Settings",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // --- Voice Selection ---
        Text(text = "Voice Selection", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        
        var expanded by remember { mutableStateOf(false) }
        
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = selectedVoice,
                onValueChange = {},
                readOnly = true,
                label = { Text("Voice") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            )
            // Invisible box to catch clicks over the text field
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { expanded = !expanded }
            )
            
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = { Text(voice) },
                        onClick = {
                            selectedVoice = voice
                            expanded = false
                            prefs.edit().putString("voice_skin", voice).apply()
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // --- Speed Selection ---
        Text(text = "Default Speed Multiplier", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        
        // Slider from 0.7 to 1.0 step 0.05
        // Value range: 0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 1.0
        // Steps = (1.0 - 0.7) / 0.05 = 6 steps
        
        Text(
            text = String.format("%.2fx", speedMultiplier),
            style = MaterialTheme.typography.bodyLarge
        )
        
        Slider(
            value = speedMultiplier,
            onValueChange = { newValue ->
                // Snap to nearest 0.05
                val snapped = (newValue * 20).roundToInt() / 20.0f
                speedMultiplier = snapped
            },
            onValueChangeFinished = {
                prefs.edit().putFloat("speed_multiplier", speedMultiplier).apply()
            },
            valueRange = 0.7f..1.0f,
            steps = 5, // (6 intervals - 1)
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Adjusts the base speaking rate relative to the system setting.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class) // For ExposedDropdownMenuDefaults
@Composable
fun Demo() {} // Placeholder to keep imports valid if unused

