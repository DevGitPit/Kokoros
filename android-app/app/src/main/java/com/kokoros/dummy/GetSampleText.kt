package com.kokoros.dummy

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

class GetSampleText : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent()
        intent.putExtra("sampleText", "This is a sample of the Kokoro Text to Speech engine.")
        setResult(Activity.RESULT_OK, intent)
        finish()
    }
}