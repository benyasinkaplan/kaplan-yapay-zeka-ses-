package com.example.fotooku

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private enum class State { IDLE, PLAYING, PAUSED }

    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    private lateinit var etText: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var seekSpeed: SeekBar
    private lateinit var imagePreview: ImageView
    private lateinit var btnPlay: Button

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private var chunks: List<String> = emptyList()
    @Volatile private var currentIndex = 0
    private var state = State.IDLE

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) runOcr(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etText = findViewById(R.id.etText)
        tvStatus = findViewById(R.id.tvStatus)
        tvSpeed = findViewById(R.id.tvSpeed)
        seekSpeed = findViewById(R.id.seekSpeed)
        imagePreview = findViewById(R.id.imagePreview)
        btnPlay = findViewById(R.id.btnPlay)

        tts = TextToSpeech(this, this)

        findViewById<Button>(R.id.btnPick).setOnClickListener { pickImage.launch("image/*") }
        btnPlay.setOnClickListener { onPlayClicked() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopSpeaking() }
        findViewById<Button>(R.id.btnVoice).setOnClickListener { openVoiceSettings() }

        // Duraklatılmışken metin düzenlenirse baştan okunsun
        etText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (state == State.PAUSED) {
                    state = State.IDLE
                    currentIndex = 0
                    updateButtons()
                }
            }
        })

        updateSpeedLabel()
        updateButtons()
        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSpeedLabel()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                // Okurken hız değişirse kaldığı cümleden yeni hızla devam et
                if (state == State.PLAYING) startSpeaking(currentIndex, keepChunks = true)
            }
        })
    }

    // Kaydırıcı 0..18 -> 0.2x .. 2.0x
    private fun currentSpeed(): Float = 0.2f + seekSpeed.progress * 0.1f

    private fun updateSpeedLabel() {
        tvSpeed.text = String.format(Locale.US, "Okuma hızı: %.1fx", currentSpeed())
    }

    private fun updateButtons() {
        btnPlay.text = when (state) {
            State.IDLE -> "▶ Oku"
            State.PLAYING -> "⏸ Duraklat"
            State.PAUSED -> "▶ Devam"
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            tvStatus.text = "Ses motoru başlatılamadı"
            return
        }
        val result = tts.setLanguage(Locale("tr", "TR"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Toast.makeText(
                this,
                "Türkçe ses bulunamadı. 'Ses ayarları' düğmesinden Türkçe sesi indirin.",
                Toast.LENGTH_LONG
            ).show()
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                utteranceId?.removePrefix("c")?.toIntOrNull()?.let { currentIndex = it }
            }
            override fun onDone(utteranceId: String?) {
                val idx = utteranceId?.removePrefix("c")?.toIntOrNull() ?: return
                if (idx == chunks.lastIndex) runOnUiThread {
                    state = State.IDLE
                    currentIndex = 0
                    updateButtons()
                    tvStatus.text = "Okuma bitti"
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })
        ttsReady = true
    }

    private fun onPlayClicked() {
        when (state) {
            State.IDLE -> startSpeaking(0)
            State.PLAYING -> pauseSpeaking()
            State.PAUSED -> startSpeaking(currentIndex, keepChunks = true)
        }
    }

    private fun runOcr(uri: Uri) {
        stopSpeaking()
        imagePreview.setImageURI(uri)
        tvStatus.text = "Yazı okunuyor..."
        try {
            val image = InputImage.fromFilePath(this, uri)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val text = result.text.trim()
                    if (text.isEmpty()) {
                        tvStatus.text = "Fotoğrafta yazı bulunamadı"
                    } else {
                        etText.setText(text)
                        tvStatus.text = "Yazı bulundu (${text.length} karakter). Okumak için ▶ düğmesine basın."
                    }
                }
                .addOnFailureListener { e ->
                    tvStatus.text = "Hata: ${e.message}"
                }
        } catch (e: Exception) {
            tvStatus.text = "Fotoğraf açılamadı: ${e.message}"
        }
    }

    private fun startSpeaking(fromIndex: Int, keepChunks: Boolean = false) {
        if (!ttsReady) {
            Toast.makeText(this, "Ses motoru hazır değil", Toast.LENGTH_SHORT).show()
            return
        }
        if (!keepChunks) {
            val text = etText.text.toString()
            if (text.isBlank()) {
                Toast.makeText(this, "Önce bir fotoğraf seçin", Toast.LENGTH_SHORT).show()
                return
            }
            chunks = splitIntoChunks(text)
        }
        if (chunks.isEmpty()) return

        val start = fromIndex.coerceIn(0, chunks.lastIndex)
        tts.stop()
        tts.setSpeechRate(currentSpeed())
        state = State.PLAYING
        updateButtons()
        tvStatus.text = "Okunuyor..."
        for (i in start until chunks.size) {
            tts.speak(chunks[i], TextToSpeech.QUEUE_ADD, null, "c$i")
        }
    }

    // Android TTS gerçek duraklatma sunmaz: durdurup kaldığımız cümleyi hatırlıyoruz,
    // devam edince o cümleden yeniden başlıyoruz.
    private fun pauseSpeaking() {
        if (ttsReady) tts.stop()
        state = State.PAUSED
        updateButtons()
        tvStatus.text = "Duraklatıldı"
    }

    private fun stopSpeaking() {
        if (ttsReady) tts.stop()
        state = State.IDLE
        currentIndex = 0
        updateButtons()
        tvStatus.text = "Durduruldu"
    }

    private fun openVoiceSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /**
     * Fotoğraftan gelen yazı satır satır bölünmüş olur; satır sonlarını boşluğa çevirip
     * cümlelere böler, TTS'in sınırını aşmamak için ~300 karakterlik parçalar yapar.
     */
    private fun splitIntoChunks(raw: String): List<String> {
        val cleaned = raw
            .replace(Regex("-\\n"), "")
            .replace(Regex("(?<!\\n)\\n(?!\\n)"), " ")
            .replace(Regex("[ \\t]+"), " ")
            .trim()

        val sentences = cleaned.split(Regex("(?<=[.!?…])\\s+|\\n{2,}"))
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        val limit = 300

        fun flush() {
            if (sb.isNotBlank()) result.add(sb.toString().trim())
            sb.clear()
        }

        for (s in sentences) {
            if (s.isBlank()) continue
            if (s.length > 1000) {
                flush()
                s.chunked(1000).forEach { result.add(it) }
                continue
            }
            if (sb.length + s.length + 1 > limit) flush()
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(s)
        }
        flush()
        return result
    }

    override fun onDestroy() {
        if (ttsReady) {
            tts.stop()
            tts.shutdown()
        }
        recognizer.close()
        super.onDestroy()
    }
}
