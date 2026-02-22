package com.kokoros.dummy

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

class GetSampleText : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val lang = intent.getStringExtra("language")
        val sample = when (lang) {
            "spa" -> "Esta es una muestra del motor de texto a voz de Kokoro."
            "fra" -> "Ceci est un échantillon du moteur de synthèse vocale Kokoro."
            "hin" -> "यह कोकोरो टेक्स्ट टू स्पीच इंजन का एक नमूना है।"
            "ita" -> "Questo è un esempio del motore di sintesi vocale Kokoro."
            "jpn" -> "これは、Kokoroテキスト読み上げエンジンのサンプルです。"
            "por" -> "Esta é uma amostra do motor de texto para fala Kokoro."
            "zho" -> "这是 Kokoro 文本转语音引擎的示例。"
            else -> "This is a sample of the Kokoro Text to Speech engine in English."
        }
        
        val resultIntent = Intent()
        resultIntent.putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sample)
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}