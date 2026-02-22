package com.kokoros.dummy

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

class CheckVoiceData : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ISO 639-2 3-letter codes for all supported languages
        val foundData = arrayListOf(
            "eng-USA", "eng-GBR", 
            "spa-ESP", 
            "fra-FRA", 
            "hin-IND", 
            "ita-ITA", 
            "jpn-JPN", 
            "por-BRA", 
            "zho-CHN"
        )
        val intent = Intent()
        intent.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, foundData)
        intent.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf<String>())
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, intent)
        finish()
    }
}