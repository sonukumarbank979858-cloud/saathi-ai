package com.sonu.saathi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private enum class CallState { Connecting, Listening, Thinking, Speaking, Muted, Error }

private fun speakable(s: String): String =
    s.replace(Regex("[\\p{So}\\p{Cn}\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]"), "")
        .replace(Regex("\\*+"), "").trim()

@Composable
fun CallScreen(
    c: Character, store: Store, t: T, lang: String, config: Map<String, Any?>,
    video: Boolean, onEnd: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val hi = lang == "hi"
    val key = config["geminiKey"]?.toString().orEmpty()
    val model = config["model"]?.toString()?.ifBlank { null } ?: "gemini-3.5-flash"

    var state by remember { mutableStateOf(CallState.Connecting) }
    var caption by remember { mutableStateOf("") }
    var heard by remember { mutableStateOf("") }
    var muted by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var ended by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(store.messages(c.id)) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) { state = CallState.Error; caption = if (hi) "कॉल के लिए माइक की अनुमति चाहिए।" else "Microphone permission is needed for calls." }
    }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    LaunchedEffect(Unit) { while (true) { delay(1000); if (state != CallState.Connecting && state != CallState.Error) seconds++ } }

    val locale = when (lang) { "en" -> Locale("en", "IN"); "hi" -> Locale("hi", "IN"); else -> Locale("en", "IN") }
    val recLang = if (lang == "en") "en-IN" else "hi-IN"

    // ---- Text to speech ----
    var ttsReady by remember { mutableStateOf(false) }
    val listenAgain = remember { mutableStateOf<() -> Unit>({}) }
    val tts = remember {
        var ref: TextToSpeech? = null
        ref = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = ref?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) ref?.setLanguage(Locale("hi", "IN"))
                ref?.setSpeechRate(1.0f); ref?.setPitch(1.05f)
                ttsReady = true
            }
        }
        ref!!
    }
    DisposableEffect(Unit) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { android.os.Handler(android.os.Looper.getMainLooper()).post { listenAgain.value() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { android.os.Handler(android.os.Looper.getMainLooper()).post { listenAgain.value() } }
        })
        onDispose { tts.stop(); tts.shutdown() }
    }

    fun speak(text: String) {
        caption = text
        state = CallState.Speaking
        val s = speakable(text)
        if (s.isEmpty()) { listenAgain.value(); return }
        tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
    }

    // ---- Speech recognition ----
    val recognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(ctx)) SpeechRecognizer.createSpeechRecognizer(ctx) else null
    }
    DisposableEffect(Unit) { onDispose { recognizer?.destroy() } }

    fun ask(userText: String) {
        state = CallState.Thinking
        val now = history + ChatMessage(true, userText, System.currentTimeMillis())
        history = now; store.saveMessages(c.id, now)
        scope.launch {
            val sys = Gemini.systemPrompt(c, store.custom(c.id), lang, store.userName) +
                "\n\nTHIS IS A LIVE VOICE CALL" + (if (video) " (video call)" else "") +
                ": reply like natural spoken talk in 1 to 3 short sentences. No emojis, no lists, no stage directions, no asterisks."
            val reply = if (key.isBlank()) t.noKey else try {
                Gemini.reply(key, model, sys, now)
            } catch (e: Exception) { if (hi) "माफ़ करना, आवाज़ साफ़ नहीं आई। फिर से बोलो?" else "Sorry, the line broke. Say that again?" }
            if (ended) return@launch
            val after = history + ChatMessage(false, reply, System.currentTimeMillis())
            history = after; store.saveMessages(c.id, after)
            speak(reply)
        }
    }

    fun listen() {
        if (ended) return
        if (muted) { state = CallState.Muted; return }
        val r = recognizer ?: run {
            state = CallState.Error
            caption = if (hi) "इस फ़ोन में आवाज़ पहचानने की सुविधा नहीं मिली (Google ऐप अपडेट करें)।" else "Speech recognition isn't available (update the Google app)."
            return
        }
        heard = ""
        state = CallState.Listening
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                heard = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: heard
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                if (text.isEmpty()) listen() else { heard = text; ask(text) }
            }
            override fun onError(error: Int) {
                if (ended) return
                // No speech / timeout: just keep listening quietly.
                scope.launch { delay(300); if (!ended && state == CallState.Listening) listen() }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        try { r.startListening(intent) } catch (e: Exception) { state = CallState.Error; caption = e.message ?: "Error" }
    }
    listenAgain.value = { if (!ended) listen() }

    // Start: character greets first, then listens.
    LaunchedEffect(granted, ttsReady) {
        if (granted && ttsReady && state == CallState.Connecting) {
            delay(1200)
            val hello = when (lang) {
                "hi" -> "हैलो${if (store.userName.isNotBlank()) " " + store.userName else ""}! कैसे हो? बताओ, क्या चल रहा है?"
                "en" -> "Hey! So nice of you to call. How are you?"
                else -> "Hello! Kaise ho? Batao, kya chal raha hai?"
            }
            speak(hello)
        }
    }

    fun end() {
        ended = true
        try { recognizer?.cancel() } catch (_: Exception) {}
        tts.stop()
        onEnd()
    }
    androidx.activity.compose.BackHandler { end() }

    // ---- UI ----
    val pulse = rememberInfiniteTransition(label = "p")
    val ring by pulse.animateFloat(1f, 1.18f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "r")
    val drift by pulse.animateFloat(1.0f, 1.08f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Reverse), label = "d")
    val talking = state == CallState.Speaking

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (video) {
            if (c.videoUrl.isNotBlank()) LoopVideo(c.videoUrl, Modifier.fillMaxSize())
            else AsyncImage(c.imageUrl, null, Modifier.fillMaxSize().scale(if (talking) drift * 1.02f else drift), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x88000000), Color.Transparent, Color(0xDD000000)))))
        } else {
            AsyncImage(c.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.35f)
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)))
        }

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            if (!video) {
                Box(contentAlignment = Alignment.Center) {
                    Box(Modifier.size(170.dp).scale(if (talking) ring else 1f).clip(CircleShape)
                        .border(3.dp, Brush.linearGradient(listOf(Color(0xFFFF4D8D), Color(0xFF9B5CFF))), CircleShape))
                    AsyncImage(c.imageUrl, null, Modifier.size(150.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                }
                Spacer(Modifier.height(16.dp))
            }
            Text(c.name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            val status = when (state) {
                CallState.Connecting -> if (hi) "कनेक्ट हो रहा है…" else "Connecting…"
                CallState.Listening -> if (hi) "सुन रहे हैं…" else "Listening…"
                CallState.Thinking -> if (hi) "सोच रहे हैं…" else "Thinking…"
                CallState.Speaking -> if (hi) "बोल रहे हैं" else "Speaking"
                CallState.Muted -> if (hi) "माइक बंद है" else "Muted"
                CallState.Error -> if (hi) "समस्या" else "Problem"
            }
            Text("$status  •  %d:%02d".format(seconds / 60, seconds % 60), color = Color(0xFFDDDDDD), fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            if (heard.isNotBlank() && state != CallState.Speaking)
                Text("“$heard”", color = Color(0xFFBBBBBB), fontSize = 15.sp, textAlign = TextAlign.Center)
            if (caption.isNotBlank())
                Text(caption, color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 12.dp))
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(if (muted) Color.White else Color(0x55FFFFFF))
                    .clickable {
                        muted = !muted
                        if (muted) { try { recognizer?.cancel() } catch (_: Exception) {}; if (state == CallState.Listening) state = CallState.Muted }
                        else if (state == CallState.Muted) listen()
                    }, contentAlignment = Alignment.Center) {
                    Icon(if (muted) Icons.Filled.MicOff else Icons.Filled.Mic, null, tint = if (muted) Color.Black else Color.White)
                }
                Box(Modifier.size(76.dp).clip(CircleShape).background(Color(0xFFE53935)).clickable { end() },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.CallEnd, null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun LoopVideo(url: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = {
        PlayerView(it).apply {
            this.player = player
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }, modifier = modifier)
}
