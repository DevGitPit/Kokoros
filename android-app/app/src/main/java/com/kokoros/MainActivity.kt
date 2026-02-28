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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("KokoroPrefs", Context.MODE_PRIVATE) }

    // Defined voices data
    val languageGroups = listOf(
        "US English" to listOf(
            "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica", "af_kore", 
            "af_nicole", "af_nova", "af_river", "af_sarah", "af_sky",
            "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael", 
            "am_onyx", "am_puck", "am_santa"
        ),
        "UK English" to listOf(
            "bf_alice", "bf_emma", "bf_isabella", "bf_lily",
            "bm_daniel", "bm_fable", "bm_george", "bm_lewis"
        ),
        "Spanish" to listOf("ef_dora", "em_alex", "em_santa"),
        "French" to listOf("ff_siwis"),
        "Hindi" to listOf("hf_alpha", "hf_beta", "hm_omega", "hm_psi"),
        "Italian" to listOf("if_sara", "im_nicola"),
        "Japanese" to listOf("jf_alpha", "jf_gongitsune", "jf_nezumi", "jf_tebukuro", "jm_kumo"),
        "Brazilian Portuguese" to listOf("pf_dora", "pm_alex", "pm_santa"),
        "Mandarin Chinese" to listOf("zf_xiaobei", "zf_xiaoni", "zf_xiaoxiao")
    )

    // State management
    var selectedVoice by remember { mutableStateOf(prefs.getString("voice_skin", "af_heart") ?: "af_heart") }
    
    // Determine initial language from selected voice
    val initialLanguage = remember {
        languageGroups.find { it.second.contains(selectedVoice) }?.first ?: "US English"
    }
    var selectedLanguage by remember { mutableStateOf(initialLanguage) }
    
    var speedMultiplier by remember { mutableFloatStateOf(prefs.getFloat("speed_multiplier", 1.0f)) }
    
    // Auto-detect optimal threads on first launch
    val detectedPowerCores = remember { CpuCoreHelper.getPowerCoreCount() }
    var ortThreads by remember { 
        mutableIntStateOf(prefs.getInt("ort_threads", detectedPowerCores)) 
    }
    
    var xnnpackThreads by remember { mutableIntStateOf(prefs.getInt("xnnpack_threads", 1)) }
    var isSynthesizing by remember { mutableStateOf(false) }
    var isEngineReady by remember { mutableStateOf(false) }
    var initStatus by remember { mutableStateOf("Checking assets...") }

    val threadOptions = listOf(0, 1, 2, 3, 4, 5, 6, 8)

    // Filtered voices based on selected language
    val filteredVoices = remember(selectedLanguage) {
        languageGroups.find { it.first == selectedLanguage }?.second ?: emptyList()
    }

    // Startup Initialization
    LaunchedEffect(Unit) {
        // Show auto-detection toast on first launch
        val isFirstLaunch = !prefs.contains("ort_threads")
        if (isFirstLaunch) {
            Toast.makeText(context, "Auto-detected $detectedPowerCores power cores for optimal performance", Toast.LENGTH_LONG).show()
            prefs.edit().putInt("ort_threads", detectedPowerCores).apply()
        }

        withContext(Dispatchers.IO) {
            try {
                val filesDir = context.filesDir
                val modelDir = File(filesDir, "model")
                if (!modelDir.exists()) modelDir.mkdirs()
                
                val modelFile = File(modelDir, "model.onnx")
                val voicesFile = File(modelDir, "voices.bin")
                val espeakDir = File(filesDir, "espeak-ng-data")

                // 1. Download files if missing from stable GitHub release
                val modelUrl = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.onnx"
                val voicesUrl = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin"

                if (!modelFile.exists()) {
                    downloadFile(modelUrl, modelFile) { p ->
                        initStatus = "Downloading Model: $p%"
                    }
                }
                if (!voicesFile.exists()) {
                    downloadFile(voicesUrl, voicesFile) { p ->
                        initStatus = "Downloading Voices: $p%"
                    }
                }
                
                if (!File(espeakDir, "phondata").exists()) {
                    initStatus = "Preparing data..."
                    val zip = File(filesDir, "espeak-ng-data.zip")
                    if (!zip.exists()) {
                         copyAsset(context, "espeak-ng-data.zip", zip)
                    }
                    extractZipFile(zip, filesDir)
                    zip.delete()
                }

                // 2. Pre-initialize engine into memory
                initStatus = "Loading engine..."
                Log.i("KokoroUI", "Initializing JNI with: \nModel: ${modelFile.absolutePath} (${modelFile.length()} bytes)\nVoices: ${voicesFile.absolutePath} (${voicesFile.length()} bytes)\nData: ${filesDir.absolutePath}")
                
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
                initStatus = "Updating engine..."
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

        // --- Language Selection (Radio Buttons) ---
        Text(text = "Language Selection", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            languageGroups.forEach { (lang, _) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { 
                        selectedLanguage = lang
                        val newVoices = languageGroups.find { it.first == lang }?.second ?: emptyList()
                        if (!newVoices.contains(selectedVoice)) {
                            val firstVoice = newVoices.firstOrNull() ?: "af_heart"
                            selectedVoice = firstVoice
                            prefs.edit().putString("voice_skin", firstVoice).apply()
                        }
                    }
                ) {
                    RadioButton(
                        selected = (selectedLanguage == lang),
                        onClick = { 
                            selectedLanguage = lang
                            val newVoices = languageGroups.find { it.first == lang }?.second ?: emptyList()
                            if (!newVoices.contains(selectedVoice)) {
                                val firstVoice = newVoices.firstOrNull() ?: "af_heart"
                                selectedVoice = firstVoice
                                prefs.edit().putString("voice_skin", firstVoice).apply()
                            }
                        }
                    )
                    Text(text = lang, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- Voice Selection (Filtered Dropdown) ---
        Text(text = "Voice Selection ($selectedLanguage)", style = MaterialTheme.typography.titleMedium)
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
                filteredVoices.forEach { voice ->
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
                        value = if (ortThreads == 0) "0 (Auto)" else ortThreads.toString(),
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ortExpanded) },
                        modifier = Modifier.clickable { ortExpanded = !ortExpanded }
                    )
                    Box(modifier = Modifier.matchParentSize().clickable { ortExpanded = !ortExpanded })
                    DropdownMenu(expanded = ortExpanded, onDismissRequest = { ortExpanded = false }) {
                        threadOptions.forEach { count ->
                            DropdownMenuItem(
                                text = { Text(if (count == 0) "0 (Auto)" else count.toString()) },
                                onClick = {
                                    if (count != ortThreads) updateThreads(count, xnnpackThreads)
                                    ortExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = "0 uses all cores. Recommended: $detectedPowerCores power cores for this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // --- Test Audio / Progress Button ---
        Button(
            onClick = {
                if (!isSynthesizing && isEngineReady) {
                    scope.launch {
                        isSynthesizing = true
                        try {
                            playSample(context, selectedVoice, selectedLanguage, 1.0f)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Playback failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        } finally {
                            isSynthesizing = false
                        }
                    }
                }
            },
            enabled = isEngineReady && !isSynthesizing,
            modifier = Modifier.fillMaxWidth().height(56.dp)
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

private suspend fun playSample(context: Context, voice: String, language: String, speed: Float) {
    val text = when (language) {
        "Spanish" -> "Esta es una muestra del motor de texto a voz de Kokoro en español."
        "French" -> "Ceci est un échantillon du moteur de synthèse vocale Kokoro en français."
        "Hindi" -> "यह हिंदी में कोकोरो टेक्स्ट टू स्पीच इंजन का एक नमूना है।"
        "Italian" -> "Questo è un esempio del motore di sintesi vocale Kokoro in italiano."
        // Use Kana only for Japanese as eSpeak-ng struggles with Kanji Kanji Kanji
        "Japanese" -> "これは、ココログルー、テキストよみあげエンジンのサンプルです。" 
        "Brazilian Portuguese" -> "Esta é uma amostra do motor de texto para fala Kokoro em português."
        "Mandarin Chinese" -> "这是 Kokoro 文本转语音引擎的中文示例。"
        "UK English" -> "This is a sample of the Kokoro Text to Speech engine in British English."
        else -> "This is a sample of the Kokoro Text to Speech engine in American English."
    }

    val langCode = when (language) {
        "Spanish" -> "es"
        "French" -> "fr"
        "Hindi" -> "hi"
        "Italian" -> "it"
        "Japanese" -> "ja"
        "Brazilian Portuguese" -> "pt-br"
        "Mandarin Chinese" -> "cmn"
        "UK English" -> "en-us"
        else -> "en-us"
    }

    val samples = withContext(Dispatchers.IO) {
        KokoroJNI.synthesize(text, voice, langCode, speed)
    } ?: return

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
    
    // Wait for the audio to finish playing
    val durationMs = (samples.size.toFloat() / sampleRate * 1000).toLong()
    kotlinx.coroutines.delay(durationMs)
    
    audioTrack.release()
}

@Composable
fun Demo() {}

private suspend fun downloadFile(urlStr: String, dest: File, onProgress: (Int) -> Unit) {
    withContext(Dispatchers.IO) {
        val url = java.net.URL(urlStr)
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connect()
        
        if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK) {
            throw Exception("Server returned HTTP ${connection.responseCode}")
        }
        
        val fileLength = connection.contentLength
        val input = connection.inputStream
        val output = java.io.FileOutputStream(dest)
        
        val data = ByteArray(4096)
        var total: Long = 0
        var count: Int
        var lastProgress = -1
        
        while (input.read(data).also { count = it } != -1) {
            total += count
            if (fileLength > 0) {
                val progress = (total * 100 / fileLength).toInt()
                if (progress != lastProgress) {
                    onProgress(progress)
                    lastProgress = progress
                }
            }
            output.write(data, 0, count)
        }
        
        output.flush()
        output.close()
        input.close()
    }
}
