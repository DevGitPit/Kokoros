package com.kokoros

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeechService
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class KokoroTTS : TextToSpeechService() {

    private val TAG = "KokoroTTS"
    private val scope = CoroutineScope(Dispatchers.Default)

    private var ttsInitialized = false
    private var modelPath: String? = null
    private var voicesPath: String? = null

    private val sampleRate = 24000 // Fixed sample rate from Rust engine

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

                val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4) // Limit threads
                ttsInitialized = KokoroJNI.initialize(modelPath!!, voicesPath!!, threads)
                if (ttsInitialized) {
                    Log.i(TAG, "Kokoro TTS engine initialized successfully.")
                } else {
                    Log.e(TAG, "Failed to initialize Kokoro TTS engine.")
                }
            } else {
                Log.e(TAG, "Failed to copy assets.")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service onDestroy")
        KokoroJNI.shutdown()
        scope.cancel() // Cancel all coroutines
    }

    override fun onIs
        (lang: String?, country: String?, variant: String?): Int {
        // We will support English ("en") as default for now
        if (lang != null && lang.equals("en", ignoreCase = true)) {
            return TextToSpeech.
                // Indicate that the engine is ready for language (and region if provided)
                LANG_COUNTRY_VAR_AVAILABLE
        }
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetDefaultEngine(): String {
        return "com.kokoros" // Package name as default engine ID
    }

    override fun onGetSampleRate(): String {
        return sampleRate.toString()
    }

    override fun onGetFeaturesForLanguage(lang: String?, country: String?, variant: String?): Array<String> {
        // You can expose custom features here if needed.
        // For now, return empty array.
        return emptyArray()
    }

    override fun onGetVoices(): MutableList<Voice> {
        val voices = mutableListOf<Voice>()
        // TODO: Dynamically retrieve available voices from Rust engine
        // For now, add a placeholder voice for English
        val defaultVoice = Voice(
            "kokoros-en-default", // Name
            Locale("en", "US"), // Locale
            Voice.QUALITY_NORMAL, // Quality
            Voice.LATENCY_NORMAL, // Latency
            false, // Not local (comes from the engine)
            emptySet() // Features
        )
        voices.add(defaultVoice)
        return voices
    }

    override fun onSynthesizeText(
        text: String?,
        bundle: Bundle?,
        callback: SynthesisCallback?
    ) {
        if (!ttsInitialized || text == null || callback == null) {
            Log.e(TAG, "TTS not initialized, text is null, or callback is null.")
            callback?.error()
            return
        }

        // Get voice and speed from bundle or use defaults
        val voiceName = bundle?.getString(TextToSpeech.Engine.KEY_PARAM_VOICE, "af_sky") ?: "af_sky"
        val speechRate = bundle?.getFloat(TextToSpeech.Engine.KEY_PARAM_RATE, 1.0f) ?: 1.0f

        callback.start(sampleRate, android.speech.tts.AudioFormat.ENCODING_PCM_FLOAT, 1) // Float PCM, Mono

        val floatSamples = KokoroJNI.synthesize(text, voiceName, speechRate)

        if (floatSamples != null) {
            val byteBuffer = floatArrayToByteBuffer(floatSamples)
            callback.audioAvailable(byteBuffer, 0, byteBuffer.remaining())
            callback.done()
        } else {
            Log.e(TAG, "Synthesis failed for text: $text")
            callback.error()
        }
    }

    override fun onStop() {
        // Stop any ongoing synthesis if necessary
        Log.i(TAG, "onStop called.")
        // Our current Rust implementation is synchronous, so no explicit stop needed in Rust.
        // If it were streaming, we'd send a stop signal here.
    }

    private fun floatArrayToByteBuffer(floatArray: FloatArray): ByteBuffer {
        val byteBuffer = ByteBuffer.allocate(floatArray.size * 4) // 4 bytes per float
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN) // PCM is typically little-endian
        for (f in floatArray) {
            byteBuffer.putFloat(f)
        }
        byteBuffer.flip() // Prepare for reading
        return byteBuffer
    }

    private suspend fun copyAssetsToInternalStorage(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val filesDir = context.filesDir
                val assetManager = context.assets

                // Copy ONNX model
                copyAssetFile(assetManager, MODEL_ONNX_FP16, File(filesDir, MODEL_ONNX_FP16))
                // Copy Voices data
                copyAssetFile(assetManager, VOICES_BIN, File(filesDir, VOICES_BIN))

                Log.i(TAG, "Assets copied to: ${filesDir.absolutePath}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error copying assets: ${e.message}", e)
                false
            }
        }
    }

    private fun copyAssetFile(assetManager: android.content.res.AssetManager, assetName: String, destFile: File) {
        if (destFile.exists()) {
            Log.d(TAG, "Asset $assetName already exists, skipping copy.")
            return
        }

        assetManager.open(assetName).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    companion object {
        private const val MODEL_ONNX_FP16 = "kokoro-v1.0.fp16.onnx"
        private const val VOICES_BIN = "voices-v1.0.bin"
    }
}
