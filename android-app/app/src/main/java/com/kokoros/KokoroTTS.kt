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
                modelPath = File(filesDir, MODEL_ONNX_FP16).absolutePath
                voicesPath = File(filesDir, VOICES_BIN).absolutePath
                val espeakParentPath = filesDir.absolutePath // espeak-ng expects path to parent of 'espeak-ng-data'

                val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
                val threadCount = if (threads >= 5) 5 else threads
                ttsInitialized = KokoroJNI.initialize(modelPath!!, voicesPath!!, espeakParentPath, threadCount)
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
        if (lang != null && (lang.equals("en", ignoreCase = true) || lang.equals("eng", ignoreCase = true))) {
            return TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        }
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf("eng", "USA", "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onStop() {
        Log.i(TAG, "onStop called.")
        stopRequested = true
    }

    override fun onGetVoices(): MutableList<Voice> {
        val voices = mutableListOf<Voice>()
        val voiceNames = listOf(
            "af_heart", "af_sky", "af_bella", "af_nicole", "af_sarah",
            "am_adam", "am_michael",
            "bf_emma", "bf_isabella",
            "bm_george", "bm_lewis"
        )
        
        for (name in voiceNames) {
            voices.add(Voice(
                name,
                Locale("en", "US"),
                Voice.QUALITY_VERY_HIGH,
                Voice.LATENCY_NORMAL,
                false,
                emptySet()
            ))
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
        val prefVoice = prefs.getString("voice_skin", "af_sky") ?: "af_sky"
        val prefSpeedMult = prefs.getFloat("speed_multiplier", 1.0f)

        stopRequested = false
        
        // Determine voice: Use request voice if valid, otherwise preference
        val validVoices = listOf(
            "af_heart", "af_sky", "af_bella", "af_nicole", "af_sarah",
            "am_adam", "am_michael", "bf_emma", "bf_isabella",
            "bm_george", "bm_lewis"
        )
        val reqVoice = req.voiceName
        val voiceName = if (reqVoice != null && validVoices.contains(reqVoice)) {
            reqVoice
        } else {
            prefVoice
        }

        val speechRate = (req.speechRate.toFloat() / 100.0f) // Normalized rate
        
        // Debug: Log code points to see what is actually coming in
        val debugPoints = text.take(50).codePoints().toArray().joinToString(" ") { "U+%04X".format(it) }
        Log.i(TAG, "Input Text CodePoints: $debugPoints")

        // Clean up text glitches
        // Replace corrupted CP437 sequences and standard smart quotes
        var cleanText = text
            .replace("\u0393\u00C7\u00FF", "'") // ΓÇÿ -> '
            .replace("\u0393\u00C7\u00D6", "'") // ΓÇÖ -> '
            .replace("ΓÇÿ", "'")
            .replace("ΓÇÖ", "'")
            .replace("[\u2018\u2019\u201B]".toRegex(), "'") // Smart single quotes
            .replace("[\u201C\u201D]".toRegex(), "\"") // Smart double quotes

        Log.i(TAG, "Synthesizing text: \"$cleanText\" with voice: $voiceName, rate: $speechRate (PrefMult: $prefSpeedMult)")

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
                KokoroJNI.synthesize(sentence, voiceName, adjustedSpeed)
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
                val assetManager = context.assets
                copyAssetFile(assetManager, MODEL_ONNX_FP16, File(filesDir, MODEL_ONNX_FP16))
                copyAssetFile(assetManager, VOICES_BIN, File(filesDir, VOICES_BIN))
                
                val espeakDataDir = File(filesDir, "espeak-ng-data")
                val phondataFile = File(espeakDataDir, "phondata")
                
                // Check if extracted data exists and is correctly structured
                if (!phondataFile.exists()) {
                    Log.i(TAG, "espeak-ng-data missing or incomplete, extracting...")
                    if (espeakDataDir.exists()) {
                        espeakDataDir.deleteRecursively()
                    }
                    
                    // Extract espeak-ng-data.zip into filesDir
                    // Zip contains 'espeak-ng-data/' directory already
                    val zipFile = File(filesDir, "espeak-ng-data.zip")
                    copyAssetFile(assetManager, "espeak-ng-data.zip", zipFile)
                    
                    extractZip(zipFile, filesDir)
                    zipFile.delete() // Clean up zip
                    Log.i(TAG, "Extracted espeak-ng-data successfully.")
                } else {
                     Log.i(TAG, "espeak-ng-data already present.")
                }

                Log.i(TAG, "Assets ready at: ${filesDir.absolutePath}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error copying assets: ${e.message}", e)
                false
            }
        }
    }

    private fun copyAssetFile(assetManager: android.content.res.AssetManager, assetName: String, destFile: File) {
        if (destFile.exists()) return
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
        private const val MODEL_ONNX_FP16 = "kokoro-v1.0.fp16.onnx"
        private const val VOICES_BIN = "voices-v1.0.bin"
    }
}