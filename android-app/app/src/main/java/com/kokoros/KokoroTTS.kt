package com.kokoros

import android.content.Context
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class KokoroTTS : TextToSpeechService() {

    private val TAG = "KokoroTTS"
    private val scope = CoroutineScope(Dispatchers.Default)

    private var ttsInitialized = false
    private val initLatch = CountDownLatch(1)
    private var modelPath: String? = null
    private var voicesPath: String? = null

    private val sampleRate = 24000 // Fixed sample rate from Rust engine

    @Volatile
    private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")

        scope.launch {
            // Copy assets on a background thread
            val assetsCopied = copyAssetsToInternalStorage(applicationContext)
            if (assetsCopied) {
                // Initialize TTS engine after assets are ready
                val filesDir = applicationContext.filesDir
                val modelDir = File(filesDir, "model")
                modelPath = File(modelDir, MODEL_ONNX).absolutePath
                voicesPath = File(modelDir, VOICES_BIN).absolutePath
                val espeakParentPath = filesDir.absolutePath // espeak-ng expects path to parent of 'espeak-ng-data'

                val prefs = applicationContext.getSharedPreferences("KokoroPrefs", Context.MODE_PRIVATE)
                val ortThreads = prefs.getInt("ort_threads", 4)

                ttsInitialized = KokoroJNI.initialize(modelPath!!, voicesPath!!, espeakParentPath, ortThreads)
                if (ttsInitialized) {
                    Log.i(TAG, "Kokoro TTS engine initialized successfully.")
                } else {
                    Log.e(TAG, "Failed to initialize Kokoro TTS engine.")
                }
            } else {
                Log.e(TAG, "Failed to copy assets.")
            }
            initLatch.countDown()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service onDestroy")
        KokoroJNI.shutdown()
        scope.cancel() // Cancel all coroutines
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val language = lang?.lowercase(Locale.ROOT) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        
        val supportedPrefixes = listOf(
            "en", "eng", "usa", // English
            "es", "spa",        // Spanish
            "fr", "fra", "fre", // French
            "hi", "hin",        // Hindi
            "it", "ita",        // Italian
            "ja", "jpn",        // Japanese
            "pt", "por",        // Portuguese
            "zh", "zho", "chi"  // Chinese
        )

        val isSupported = supportedPrefixes.any { language.startsWith(it) }
        if (!isSupported) return TextToSpeech.LANG_NOT_SUPPORTED

        return when {
            variant != null && variant.isNotEmpty() -> TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            country != null && country.isNotEmpty() -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onGetLanguage(): Array<String> {
        // Return a default or the currently active language if requested
        return arrayOf("eng", "USA", "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onStop() {
        Log.i(TAG, "onStop called.")
        stopRequested = true
    }

        override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
            val language = lang?.lowercase(Locale.ROOT) ?: "en"
            return when {
                language.startsWith("es") -> "es-es-ef_dora"
                language.startsWith("fr") -> "fr-fr-ff_siwis"
                language.startsWith("hi") -> "hi-in-hf_alpha"
                language.startsWith("it") -> "it-it-if_sara"
                language.startsWith("ja") -> "ja-jp-jf_alpha"
                language.startsWith("pt") -> "pt-br-pf_dora"
                language.startsWith("zh") -> "zh-cn-zf_xiaoxiao"
                else -> "en-us-af_heart"
            }
        }
    
        override fun onGetVoices(): MutableList<Voice> {
            val voices = mutableListOf<Voice>()
            
            val voiceSpecs = mapOf(
                "en-us" to listOf(
                    "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica", "af_kore", 
                    "af_nicole", "af_nova", "af_river", "af_sarah", "af_sky",
                    "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael", 
                    "am_onyx", "am_puck", "am_santa"
                ),
                "en-gb" to listOf(
                    "bf_alice", "bf_emma", "bf_isabella", "bf_lily",
                    "bm_daniel", "bm_fable", "bm_george", "bm_lewis"
                ),
                "es-es" to listOf("ef_dora", "em_alex", "em_santa"),
                "fr-fr" to listOf("ff_siwis"),
                "hi-in" to listOf("hf_alpha", "hf_beta", "hm_omega", "hm_psi"),
                "it-it" to listOf("if_sara", "im_nicola"),
                "ja-jp" to listOf("jf_alpha", "jf_gongitsune", "jf_nezumi", "jf_tebukuro", "jm_kumo"),
                "pt-br" to listOf("pf_dora", "pm_alex", "pm_santa"),
                "zh-cn" to listOf("zf_xiaobei", "zf_xiaoni", "zf_xiaoxiao")
            )
    
            for ((langTag, names) in voiceSpecs) {
                val locale = Locale.forLanguageTag(langTag)
                for (name in names) {
                    // Prepend langTag to the name for better compatibility with external readers
                    val systemName = "$langTag-$name"
                    voices.add(Voice(
                        systemName,
                        locale,
                        Voice.QUALITY_VERY_HIGH,
                        Voice.LATENCY_NORMAL,
                        false,
                        emptySet()
                    ))
                }
            }
            return voices
        }
    
        override fun onSynthesizeText(req: SynthesisRequest?, callback: SynthesisCallback?) {
            if (req == null || callback == null) return
    
            val text = req.charSequenceText?.toString()
            if (text == null) {
                callback.error()
                return
            }
    
            // Wait for initialization if needed
            if (!ttsInitialized) {
                try {
                    if (!initLatch.await(5, TimeUnit.SECONDS)) {
                        Log.e(TAG, "Timed out waiting for TTS initialization.")
                        callback.error()
                        return
                    }
                } catch (e: InterruptedException) {
                    Log.e(TAG, "Interrupted waiting for TTS initialization.")
                    callback.error()
                    return
                }
            }
    
            if (!ttsInitialized) {
                 Log.e(TAG, "TTS failed to initialize.")
                 callback.error()
                 return
            }
    
            // Load Preferences
            val prefs = applicationContext.getSharedPreferences("KokoroPrefs", Context.MODE_PRIVATE)
            val prefVoice = prefs.getString("voice_skin", "af_heart") ?: "af_heart"
            val prefSpeedMult = prefs.getFloat("speed_multiplier", 1.0f)
    
            stopRequested = false
            
                    // Determine internal voice name and language code
                    val reqVoice = req.voiceName
                    var voiceName = if (reqVoice != null) {
                        // Strip language prefix if present (e.g., "en-us-af_heart" -> "af_heart")
                        if (reqVoice.contains("-")) {
                            reqVoice.substringAfterLast("-")
                        } else {
                            reqVoice
                        }
                    } else {
                        prefVoice
                    }
            
                                                    // Determine language code from voice name prefix
                                                    val langCode = when {
                                                        voiceName.startsWith("af") || voiceName.startsWith("am") -> "en-us"
                                                        voiceName.startsWith("bf") || voiceName.startsWith("bm") -> "en-us"
                                                        voiceName.startsWith("ef") || voiceName.startsWith("em") -> "es"
                                                        voiceName.startsWith("ff") -> "fr"
                                                        voiceName.startsWith("hf") || voiceName.startsWith("hm") -> "hi"
                                                        voiceName.startsWith("if") || voiceName.startsWith("im") -> "it"
                                                        voiceName.startsWith("jf") || voiceName.startsWith("jm") -> "ja"
                                                        voiceName.startsWith("pf") || voiceName.startsWith("pm") -> "pt-br"
                                                        voiceName.startsWith("zf") -> "cmn"
                                                        else -> "en-us"
                                                    }
                                            
                                                                            // Final safety check: if the voice doesn't look like a Kokoro voice, fallback to preference
                    if (!voiceName.contains("_")) {
                        voiceName = prefVoice
                    }
            
                    val speechRate = (req.speechRate.toFloat() / 100.0f) // Normalized rate
                    
                    // Debug: Log code points to see what is actually coming in
                    val debugPoints = text.take(50).codePoints().toArray().joinToString(" ") { "U+%04X".format(it) }
                    Log.i(TAG, "Input Text CodePoints: $debugPoints")
            
                    // Clean up text glitches
                    var cleanText = text
                        .replace("\u0393\u00C7\u00FF", "'") // ΓÇÿ -> '
                        .replace("\u0393\u00C7\u00D6", "'") // ΓÇÖ -> '
                        .replace("ΓÇÿ", "'")
                        .replace("ΓÇÖ", "'")
                        .replace("[\u2018\u2019\u201B]".toRegex(), "'") // Smart single quotes
                        .replace("[\u201C\u201D]".toRegex(), "\"") // Smart double quotes
            
                    Log.i(TAG, "Synthesizing text: \"$cleanText\" with voice: $voiceName, lang: $langCode, rate: $speechRate (PrefMult: $prefSpeedMult)")
            
                    // Use native 24kHz - no upsampling!
                    val playbackRate = 24000
                    callback.start(playbackRate, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)
            
                    // Use cleaned text directly to preserve full prosody
                    val sentences = listOf(cleanText) 
                    
                    Log.i(TAG, "=== SPEED ADJUSTMENT ===")
                    val baseSpeed = req.speechRate.toFloat() / 100.0f
                    // Apply preference multiplier (default 1.0f)
                    val adjustedSpeed = baseSpeed * prefSpeedMult
                    Log.i(TAG, "System rate: ${req.speechRate}, Adjusted speed for Kokoro: $adjustedSpeed")
            
                    var success = false
                    for (sentence in sentences) {
                        if (stopRequested) break
                        
                        val floatSamples = synchronized(this) {
                            if (stopRequested) return@synchronized null
                            KokoroJNI.synthesize(sentence, voiceName, langCode, adjustedSpeed)
                        }
            

            if (floatSamples != null) {
                success = true
                Log.i(TAG, "Generated ${floatSamples.size} samples (24kHz). Duration: ${floatSamples.size / 24000.0f}s")
                
                val pcmData = floatToShortPcm(floatSamples)
                var offset = 0
                val totalSize = pcmData.size
                val chunkSize = 4096 

                while (offset < totalSize && !stopRequested) {
                    val shortsToWrite = (totalSize - offset).coerceAtMost(chunkSize)
                    callback.audioAvailable(shortToByteArray(pcmData, offset, shortsToWrite), 0, shortsToWrite * 2)
                    offset += shortsToWrite
                }
            }
        }

        if (success && !stopRequested) {
            callback.done()
        } else if (!stopRequested) {
            callback.error()
        } else {
            callback.done() // Was stopped
        }
    }

    private fun floatToShortPcm(floatArray: FloatArray): ShortArray {
        val shortArray = ShortArray(floatArray.size)
        for (i in floatArray.indices) {
            // Clip and scale to 16-bit range
            val s = (floatArray[i] * 32767.0f).coerceIn(-32768.0f, 32767.0f).toInt().toShort()
            shortArray[i] = s
        }
        return shortArray
    }

    private fun shortToByteArray(shortArray: ShortArray, offset: Int, length: Int): ByteArray {
        val byteArray = ByteArray(length * 2)
        val buffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until length) {
            buffer.putShort(shortArray[offset + i])
        }
        return byteArray
    }

    private suspend fun copyAssetsToInternalStorage(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val filesDir = context.filesDir
                val modelDir = File(filesDir, "model")
                if (!modelDir.exists()) modelDir.mkdirs()

                val modelFile = File(modelDir, MODEL_ONNX)
                val voicesFile = File(modelDir, VOICES_BIN)
                
                if (!modelFile.exists() || !voicesFile.exists()) {
                    Log.e(TAG, "Model or voices missing. Please open the app and download them.")
                    return@withContext false
                }
                
                val espeakDataDir = File(filesDir, "espeak-ng-data")
                val phondataFile = File(espeakDataDir, "phondata")
                
                // Check if extracted data exists and is correctly structured
                if (!phondataFile.exists()) {
                    Log.i(TAG, "espeak-ng-data missing or incomplete, extracting...")
                    if (espeakDataDir.exists()) {
                        espeakDataDir.deleteRecursively()
                    }
                    
                    val assetManager = context.assets
                    // Extract espeak-ng-data.zip into filesDir
                    val zipFile = File(filesDir, "espeak-ng-data.zip")
                    copyAssetFile(assetManager, "espeak-ng-data.zip", zipFile)
                    
                    extractZip(zipFile, filesDir)
                    zipFile.delete() // Clean up zip
                    Log.i(TAG, "Extracted espeak-ng-data successfully.")
                }

                Log.i(TAG, "Assets ready at: ${filesDir.absolutePath}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing assets: ${e.message}", e)
                false
            }
        }
    }

    private fun copyAssetFile(assetManager: android.content.res.AssetManager, assetName: String, destFile: File) {
        // Only used for espeak-ng-data.zip now
        assetManager.open(assetName).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun extractZip(zipFile: File, destDir: File) {
        java.util.zip.ZipFile(zipFile).use { zip ->
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

    companion object {
        private const val MODEL_ONNX = "model.onnx"
        private const val VOICES_BIN = "voices.bin"
    }
}