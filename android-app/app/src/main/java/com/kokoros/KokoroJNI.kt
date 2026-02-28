package com.kokoros

import android.util.Log

// JNI wrapper for the Rust Kokoros TTS engine
object KokoroJNI {
    private const val TAG = "KokoroJNI"

    private var enginePtr: Long = 0

    // Load native libraries
    init {
        try {
            System.loadLibrary("c++_shared")
            System.loadLibrary("onnxruntime")
            System.loadLibrary("kokoros_android")
            Log.i(TAG, "Native libraries loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native libraries: ${e.message}")
        }
    }

    /**
     * Initializes the native Kokoros TTS engine.
     * @param modelPath Absolute path to the ONNX model file.
     * @param voicesPath Absolute path to the voices.bin file.
     * @param intraThreads Number of threads for ONNX Runtime.
     * @return A pointer (long) to the native engine instance, or 0 if initialization fails.
     */
    private external fun init(modelPath: String, voicesPath: String, espeakDataPath: String, intraThreads: Int): Long

    /**
     * Synthesizes text to raw PCM audio samples.
     * @param enginePtr The pointer to the native engine instance.
     * @param text The text to synthesize.
     * @param voice The voice style (e.g., "af_sky").
     * @param speed The speech speed multiplier (e.g., 1.0).
     * @return A float array containing the raw PCM samples, or null if synthesis fails.
     */
    private external fun speak_raw(enginePtr: Long, text: String, voice: String, language: String, speed: Float): FloatArray?

    /**
     * Closes and releases the native Kokoros TTS engine instance.
     * @param enginePtr The pointer to the native engine instance.
     */
    private external fun close(enginePtr: Long)

    /**
     * Gets the current execution provider (e.g., "CPU", "CUDA").
     * @param enginePtr The pointer to the native engine instance.
     * @return A string describing the backend.
     */
    private external fun get_backend_info(enginePtr: Long): String?

    // --- High-level Kotlin API ---

    fun initialize(modelPath: String, voicesPath: String, espeakDataPath: String, intraThreads: Int): Boolean {
        if (enginePtr == 0L) {
            enginePtr = init(modelPath, voicesPath, espeakDataPath, intraThreads)
        }
        return enginePtr != 0L
    }

    fun synthesize(text: String, voice: String, language: String, speed: Float): FloatArray? {
        if (enginePtr == 0L) {
            Log.e(TAG, "Engine not initialized. Call initialize() first.")
            return null
        }
        return speak_raw(enginePtr, text, voice, language, speed)
    }

    fun getBackendInfo(): String {
        if (enginePtr == 0L) return "Unknown"
        return get_backend_info(enginePtr) ?: "Unknown"
    }

    fun shutdown() {
        if (enginePtr != 0L) {
            close(enginePtr)
            enginePtr = 0L
            Log.i(TAG, "Native engine shut down.")
        }
    }
}
