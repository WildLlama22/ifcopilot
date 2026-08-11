package com.ifcopilot.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import java.util.Locale
import java.util.PriorityQueue

enum class CalloutPriority(val rank: Int) {
    WARNING(0),   // windshear, gear warning - can interrupt
    CALLOUT(1),   // 80kt, V1, rotate, positive rate, retard
    CONFIRM(2)    // gear up, flaps N
}

data class Callout(val text: String, val priority: CalloutPriority, val id: String)

/**
 * Wraps Android's built-in TextToSpeech. Warnings interrupt whatever is
 * currently speaking; callouts/confirmations queue in priority order.
 * Each callout id is debounced so the same event doesn't repeat rapidly.
 */
class CalloutEngine(context: Context, private val scope: CoroutineScope) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val queue = PriorityQueue<Callout>(compareBy { it.priority.rank })
    private val lastSpokenAt = mutableMapOf<String, Long>()
    private var speaking = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ready = true
            }
        }
    }

    fun speak(text: String, priority: CalloutPriority, id: String, minGapMs: Long = 4000) {
        val now = System.currentTimeMillis()
        val last = lastSpokenAt[id] ?: 0L
        if (now - last < minGapMs) return
        lastSpokenAt[id] = now

        val callout = Callout(text, priority, id)
        if (priority == CalloutPriority.WARNING) {
            queue.clear()
            tts?.stop()
            speaking = false
        }
        queue.add(callout)
        pump()
    }

    private fun pump() {
        if (speaking) return
        val next = queue.poll() ?: return
        speaking = true
        tts?.speak(next.text, TextToSpeech.QUEUE_FLUSH, null, next.id)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                speaking = false
                pump()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                speaking = false
                pump()
            }
        })
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
