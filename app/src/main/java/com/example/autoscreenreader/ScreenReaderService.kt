package com.example.autoscreenreader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.Toast
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import java.util.Locale

/**
 * Auto Screen Reader: বহুভাষিক অ্যাক্সেসিবিলিটি সার্ভিস
 * বাংলা, ইংরেজি, আরবি, হিন্দি ইত্যাদি যেকোনো ভাষার লেখা শনাক্ত করে নিজ ভাষায় পড়ে শোনাবে।
 */
class ScreenReaderService : AccessibilityService(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private lateinit var languageIdentifier: LanguageIdentifier

    companion object {
        private const val TAG = "ScreenReaderService"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "ScreenReaderService সংযুক্ত হয়েছে")
        Toast.makeText(this, "বহুভাষিক অটো স্ক্রিন রিডার সার্ভিস সক্রিয়", Toast.LENGTH_SHORT).show()

        // Google ML Kit অন-ডিভাইস ল্যাঙ্গুয়েজ আইডেন্টিফায়ার ইনিশিয়ালাইজেশন
        languageIdentifier = LanguageIdentification.getClient()

        // টেক্সট-টু-স্পিচ ইঞ্জিন ইনিশিয়ালাইজেশন
        tts = TextToSpeech(this, this)

        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info

        setupFloatingOverlay()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            // প্রারম্ভিক ডিফল্ট ভাষা বাংলা
            tts?.setLanguage(Locale("bn", "BD"))
            speakTextWithAutoLang("অটো স্ক্রিন রিডার প্রস্তুত আছে। যেকোনো ভাষায় লেখা শনাক্ত করতে সক্ষম।", TextToSpeech.QUEUE_FLUSH)
        } else {
            Log.e(TAG, "TTS ইনিশিয়ালাইজেশনে ত্রুটি")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                val node = event.source
                node?.let {
                    val textToRead = extractNodeText(it)
                    if (textToRead.isNotBlank()) {
                        speakTextWithAutoLang(textToRead, TextToSpeech.QUEUE_FLUSH)
                    }
                }
            }
        }
    }

    /**
     * স্বয়ংক্রিয় ভাষা শনাক্তকরণ এবং TTS ভাষা পরিবর্তন:
     * এটি বাংলা, ইংরেজি, আরবি, হিন্দি, ফরাসি ইত্যাদি যেকোনো ভাষা সঙ্গে সঙ্গে শনাক্ত করে
     */
    private fun speakTextWithAutoLang(text: String, queueMode: Int) {
        if (!isTtsReady || tts == null || text.isBlank()) return

        // দ্রুত অফলাইন ইউনিকোড স্ক্রিপ্ট চেক (Latency < 1ms)
        val fallbackLocale = detectScriptLocaleFast(text)

        // ML Kit এর মাধ্যমে ভাষা শনাক্তকরণ (AI Language Identification)
        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { languageCode ->
                val targetLocale = if (languageCode != "und") {
                    Locale.forLanguageTag(languageCode)
                } else {
                    fallbackLocale
                }

                // গতিশীলভাবে TTS এর ভাষা পরিবর্তন করা
                setTtsLocaleSafely(targetLocale)
                tts?.speak(text, queueMode, null, "UTTERANCE_${System.currentTimeMillis()}")
            }
            .addOnFailureListener {
                // ব্যর্থ হলে ইউনিকোড ফলব্যাক
                setTtsLocaleSafely(fallbackLocale)
                tts?.speak(text, queueMode, null, "UTTERANCE_${System.currentTimeMillis()}")
            }
    }

    private fun setTtsLocaleSafely(locale: Locale) {
        val check = tts?.isLanguageAvailable(locale)
        if (check != TextToSpeech.LANG_MISSING_DATA && check != TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.language = locale
        } else {
            // যদি ডিভাইসে কাঙ্ক্ষিত ভয়েস ফাইল না থাকে তবে ডিফল্ট ভাষা
            tts?.language = Locale.getDefault()
        }
    }

    /**
     * অফলাইন দ্রুত স্ক্রিপ্ট ডিটেকশন (বাংলা, আরবি, দেবনাগরী, ইংরেজি)
     */
    private fun detectScriptLocaleFast(text: String): Locale {
        val hasBangla = text.any { it in 'ঀ'..'৿' }
        if (hasBangla) return Locale("bn", "BD")

        val hasArabic = text.any { it in '؀'..'ۿ' }
        if (hasArabic) return Locale("ar")

        val hasDevanagari = text.any { it in 'ऀ'..'ॿ' }
        if (hasDevanagari) return Locale("hi", "IN")

        return Locale.ENGLISH
    }

    /**
     * পুরো স্ক্রিনের সকল টেক্সট ক্রমান্বয়ে রিড করা
     */
    fun readEntireScreen() {
        val rootNode = rootInActiveWindow ?: return
        val allTexts = mutableListOf<String>()
        collectAllTextsRecursive(rootNode, allTexts)

        if (allTexts.isNotEmpty()) {
            speakTextWithAutoLang(allTexts.first(), TextToSpeech.QUEUE_FLUSH)
            for (i in 1 until allTexts.size) {
                speakTextWithAutoLang(allTexts[i], TextToSpeech.QUEUE_ADD)
            }
        }
    }

    private fun collectAllTextsRecursive(node: AccessibilityNodeInfo, list: MutableList<String>) {
        val text = extractNodeText(node)
        if (text.isNotBlank() && !list.contains(text)) {
            list.add(text)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllTextsRecursive(child, list)
            child.recycle()
        }
    }

    private fun extractNodeText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        val contentDesc = node.contentDescription?.toString()
        val text = node.text?.toString()

        if (!contentDesc.isNullOrEmpty()) {
            sb.append(contentDesc)
        } else if (!text.isNullOrEmpty()) {
            sb.append(text)
        }

        if (node.isClickable && sb.isNotEmpty()) {
            if (node.className?.contains("Button") == true) {
                sb.append(if (sb.any { it in 'ঀ'..'৿' }) ", বোতাম" else ", Button")
            } else if (node.className?.contains("Switch") == true || node.isCheckable) {
                val state = if (node.isChecked) "চালু" else "বন্ধ"
                sb.append(", সুইচ $state")
            }
        }
        return sb.toString()
    }

    private fun setupFloatingOverlay() {
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 300
            }

            val button = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_lock_silent_mode_off)
                setBackgroundColor(0xCC0D9488.toInt())
                setPadding(24, 24, 24, 24)
                contentDescription = "অটো স্ক্রিন রিডার বোতাম"
                setOnClickListener {
                    readEntireScreen()
                }
            }
            floatingView = button
            windowManager?.addView(floatingView, params)
        } catch (e: Exception) {
            Log.e(TAG, "ফ্লোটিং উইন্ডো ত্রুটি: ${e.message}")
        }
    }

    override fun onInterrupt() {
        tts?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        if (floatingView != null && windowManager != null) {
            windowManager?.removeView(floatingView)
        }
    }
}