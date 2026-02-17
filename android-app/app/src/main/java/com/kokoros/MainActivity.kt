package com.kokoros

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kokoros.ui.theme.KokorosTTSTheme
import kotlin.math.roundToInt

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import java.util.Locale

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
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("KokoroPrefs", Context.MODE_PRIVATE) }

    // State management
    var selectedVoice by remember { mutableStateOf(prefs.getString("voice_skin", "af_sky") ?: "af_sky") }
    var speedMultiplier by remember { mutableFloatStateOf(prefs.getFloat("speed_multiplier", 1.0f)) }
    var ortThreads by remember { mutableIntStateOf(prefs.getInt("ort_threads", 4)) }
    var xnnpackThreads by remember { mutableIntStateOf(prefs.getInt("xnnpack_threads", 1)) }
    var backendInfo by remember { mutableStateOf("Loading...") }
    var isSynthesizing by remember { mutableStateOf(false) }
    var isEngineReady by remember { mutableStateOf(false) }
    var initStatus by remember { mutableStateOf("Checking assets...") }

    val voices = listOf(
        "af_heart", "af_sky", "af_bella", "af_nicole", "af_sarah",
        "am_adam", "am_michael",
        "bf_emma", "bf_isabella",
        "bm_george", "bm_lewis"
    )

    val threadOptions = listOf(1, 2, 3, 4, 6, 8)

    // Startup Initialization
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                initStatus = "Preparing assets..."
                val filesDir = context.filesDir
                val modelDir = File(filesDir, "model")
                if (!modelDir.exists()) modelDir.mkdirs()
                
                val modelFile = File(modelDir, "model.onnx")
                val voicesFile = File(modelDir, "voices.bin")
                val espeakDir = File(filesDir, "espeak-ng-data")

                // 1. Copy files if missing
                if (!modelFile.exists()) copyAsset(context, "model/model.onnx", modelFile)
                if (!voicesFile.exists()) copyAsset(context, "model/voices.bin", voicesFile)
                
                if (!File(espeakDir, "phondata").exists()) {
                    initStatus = "Extracting data..."
                    val zip = File(filesDir, "espeak-ng-data.zip")
                    copyAsset(context, "espeak-ng-data.zip", zip)
                    extractZipFile(zip, filesDir)
                    zip.delete()
                }

                // 2. Pre-initialize engine into memory
                initStatus = "Loading engine..."
                val success = KokoroJNI.initialize(
                    modelFile.absolutePath, 
                    voicesFile.absolutePath, 
                    filesDir.absolutePath, 
                    ortThreads,
                    xnnpackThreads
                )
                
                if (success) {
                    isEngineReady = true
                    initStatus = "Ready"
                    backendInfo = KokoroJNI.getBackendInfo()
                } else {
                    initStatus = "Engine Error"
                }
            } catch (e: Exception) {
                initStatus = "Init Failed"
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Init error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Helper to re-initialize engine
    fun updateThreads(newOrt: Int, newXnn: Int) {
        ortThreads = newOrt
        xnnpackThreads = newXnn
        prefs.edit()
            .putInt("ort_threads", newOrt)
            .putInt("xnnpack_threads", newXnn)
            .apply()
        
        scope.launch {
            withContext(Dispatchers.IO) {
                isEngineReady = false
                initStatus = "Updating threads..."
                KokoroJNI.shutdown()
                
                val filesDir = context.filesDir
                val modelDir = File(filesDir, "model")
                val modelFile = File(modelDir, "model.onnx")
                val voicesFile = File(modelDir, "voices.bin")
                
                val success = KokoroJNI.initialize(
                    modelFile.absolutePath, 
                    voicesFile.absolutePath, 
                    filesDir.absolutePath, 
                    newOrt,
                    newXnn
                )
                
                if (success) {
                    isEngineReady = true
                    initStatus = "Ready"
                    backendInfo = KokoroJNI.getBackendInfo()
                } else {
                    initStatus = "Engine Error"
                }
            }
        }
    }

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

        Spacer(modifier = Modifier.height(24.dp))

        // --- Threading Selection ---
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "ORT Threads", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                
                var ortExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedTextField(
                        value = ortThreads.toString(),
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ortExpanded) },
                        modifier = Modifier.clickable { ortExpanded = !ortExpanded }
                    )
                    Box(modifier = Modifier.matchParentSize().clickable { ortExpanded = !ortExpanded })
                    DropdownMenu(expanded = ortExpanded, onDismissRequest = { ortExpanded = false }) {
                        threadOptions.forEach { count ->
                            DropdownMenuItem(
                                text = { Text(count.toString()) },
                                onClick = {
                                    if (count != ortThreads) updateThreads(count, xnnpackThreads)
                                    ortExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "XNNPACK Threads", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                
                var xnnExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedTextField(
                        value = xnnpackThreads.toString(),
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = xnnExpanded) },
                        modifier = Modifier.clickable { xnnExpanded = !xnnExpanded }
                    )
                    Box(modifier = Modifier.matchParentSize().clickable { xnnExpanded = !xnnExpanded })
                    DropdownMenu(expanded = xnnExpanded, onDismissRequest = { xnnExpanded = false }) {
                        threadOptions.forEach { count ->
                            DropdownMenuItem(
                                text = { Text(count.toString()) },
                                onClick = {
                                    if (count != xnnpackThreads) updateThreads(ortThreads, count)
                                    xnnExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = "Recommended: ORT 4, XNNPACK 1 for balanced power/speed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // --- Backend Info ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Active Backend: ", style = MaterialTheme.typography.titleSmall)
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = backendInfo,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // --- Speed Selection ---
        Text(text = "Default Speed Multiplier", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = String.format("%.2fx", speedMultiplier),
            style = MaterialTheme.typography.bodyLarge
        )
        
        Slider(
            value = speedMultiplier,
            onValueChange = { newValue ->
                val snapped = (newValue * 20).roundToInt() / 20.0f
                speedMultiplier = snapped
            },
            onValueChangeFinished = {
                prefs.edit().putFloat("speed_multiplier", speedMultiplier).apply()
            },
            valueRange = 0.7f..1.0f,
            steps = 5,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Adjusts the base speaking rate relative to the system setting.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(48.dp))

        // --- Test Audio Button ---
        Button(
            onClick = {
                if (!isSynthesizing && isEngineReady) {
                    scope.launch {
                        isSynthesizing = true
                        try {
                            playSample(context, selectedVoice, speedMultiplier)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Playback failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        } finally {
                            isSynthesizing = false
                        }
                    }
                }
            },
            enabled = isEngineReady && !isSynthesizing,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSynthesizing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text("Synthesizing...")
            } else if (!isEngineReady) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(initStatus)
            } else {
                Text("Play Sample Audio")
            }
        }
    }
}

private fun copyAsset(context: Context, name: String, dest: File) {
    context.assets.open(name).use { input ->
        FileOutputStream(dest).use { output ->
            input.copyTo(output)
        }
    }
}

private fun extractZipFile(zipFile: File, destDir: File) {
    ZipFile(zipFile).use { zip ->
        zip.entries().asSequence().forEach { entry ->
            val outFile = File(destDir, entry.name)
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}

private fun playSample(context: Context, voice: String, speed: Float) {
    val text = "This is a sample of Kokoro TTS running natively on Android with optimized threading."
    val samples = KokoroJNI.synthesize(text, voice, speed) ?: return

    val sampleRate = 24000
    val audioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(samples.size * 4)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()

    audioTrack.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
    audioTrack.play()
}

@Composable
fun Demo() {}
