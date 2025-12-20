package com.kokoros.dummy

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

class CheckVoiceData : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Return "success" to indicate voice data is installed
        val foundData = arrayListOf("eng-USA", "eng-GBR", "eng-IND") // Example available voices
        val intent = Intent()
        intent.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, foundData)
        intent.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf())
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, intent)
        finish()
    }
}