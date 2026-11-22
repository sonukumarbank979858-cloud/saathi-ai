package com.sonu.saathi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

data class Character(
    val id: String,
    val name: String,
    val age: String,
    val tagline: String,
    val bio: String,
    val personality: String,
    val greeting: String,
    val category: String,
    val imageUrl: String,
    val gallery: List<String>,
    val order: Long,
    val videoUrl: String = "",
    val voice: String = "",
)

data class ChatMessage(val fromUser: Boolean, val text: String, val time: Long)

data class Custom(
    val shyBold: Float = 0.5f,
    val caringPlayful: Float = 0.5f,
    val cuteMature: Float = 0.5f,
    val traits: Set<String> = emptySet(),
)

/** Google Drive share link -> direct image link. Other links are returned unchanged. */
object Drive {
    /** Drive share link -> direct stream URL for a video (small files play best, under ~50 MB). */
    fun video(link: String): String {
        val l = link.trim()
        if (l.isEmpty() || !l.contains("drive.google")) return l
        val id = listOf(Regex("/d/([a-zA-Z0-9_-]+)"), Regex("[?&]id=([a-zA-Z0-9_-]+)"))
            .firstNotNullOfOrNull { it.find(l)?.groupValues?.get(1) } ?: return l
        return "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
    }

    private val patterns = listOf(
        Regex("drive\\.google\\.com/file/d/([a-zA-Z0-9_-]+)"),
        Regex("drive\\.google\\.com/open\\?id=([a-zA-Z0-9_-]+)"),
        Regex("[?&]id=([a-zA-Z0-9_-]+)"),
        Regex("lh3\\.googleusercontent\\.com/d/([a-zA-Z0-9_-]+)"),
    )

    fun direct(link: String): String {
        val l = link.trim()
        if (l.isEmpty()) return l
        if (!l.contains("google")) return l
        for (p in patterns) {
            val m = p.find(l)
            if (m != null) return "https://lh3.googleusercontent.com/d/${m.groupValues[1]}=w1000"
        }
        return l
    }
}

object Net {
    fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        return try {
            val code = c.responseCode
            val s = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw Exception("HTTP $code: ${s.take(300)}")
            s
        } finally { c.disconnect() }
    }

    fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        return try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val s = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw Exception("HTTP $code: ${s.take(300)}")
            s
        } finally { c.disconnect() }
    }
}

object Firestore {
    private val keyParam get() = if (BuildConfig.FIREBASE_API_KEY.startsWith("AIza")) "key=${BuildConfig.FIREBASE_API_KEY}" else ""
    private val base get() =
        "https://firestore.googleapis.com/v1/projects/${BuildConfig.FIREBASE_PROJECT_ID}/databases/(default)/documents"

    private fun value(v: JSONObject?): Any? {
        if (v == null) return null
        return when {
            v.has("stringValue") -> v.getString("stringValue")
            v.has("integerValue") -> v.getString("integerValue").toLongOrNull()
            v.has("doubleValue") -> v.getDouble("doubleValue")
            v.has("booleanValue") -> v.getBoolean("booleanValue")
            v.has("arrayValue") -> {
                val arr = v.getJSONObject("arrayValue").optJSONArray("values") ?: JSONArray()
                (0 until arr.length()).mapNotNull { value(arr.getJSONObject(it)) }
            }
            else -> null
        }
    }

    private fun fields(doc: JSONObject): Map<String, Any?> {
        val f = doc.optJSONObject("fields") ?: return emptyMap()
        return f.keys().asSequence().associateWith { value(f.optJSONObject(it)) }
    }

    suspend fun characters(): List<Character> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Character>()
        var token: String? = null
        do {
            var url = "$base/characters?pageSize=300&$keyParam"
            if (token != null) url += "&pageToken=" + URLEncoder.encode(token, "UTF-8")
            val js = JSONObject(Net.get(url))
            val docs = js.optJSONArray("documents") ?: JSONArray()
            for (i in 0 until docs.length()) {
                val d = docs.getJSONObject(i)
                val f = fields(d)
                if (f["active"] == false) continue
                @Suppress("UNCHECKED_CAST")
                out += Character(
                    id = d.getString("name").substringAfterLast('/'),
                    name = f["name"]?.toString() ?: "",
                    age = f["age"]?.toString() ?: "",
                    tagline = f["tagline"]?.toString() ?: "",
                    bio = f["bio"]?.toString() ?: "",
                    personality = f["personality"]?.toString() ?: "",
                    greeting = f["greeting"]?.toString() ?: "",
                    category = f["category"]?.toString() ?: "",
                    imageUrl = Drive.direct(f["imageUrl"]?.toString() ?: ""),
                    gallery = ((f["gallery"] as? List<Any?>) ?: emptyList()).map { Drive.direct(it.toString()) },
                    order = (f["order"] as? Number)?.toLong() ?: 999,
                    videoUrl = Drive.video(f["videoUrl"]?.toString() ?: ""),
                    voice = f["voice"]?.toString() ?: "",
                )
            }
            token = js.optString("nextPageToken").ifEmpty { null }
        } while (token != null)
        out.sortedWith(compareBy({ it.order }, { it.name }))
    }

    suspend fun config(): Map<String, Any?> = withContext(Dispatchers.IO) {
        fields(JSONObject(Net.get("$base/config/app?$keyParam")))
    }
}

object Gemini {
    suspend fun reply(
        key: String, model: String, system: String, history: List<ChatMessage>,
    ): String = withContext(Dispatchers.IO) {
        val contents = JSONArray()
        // Gemini needs the conversation to start with a user turn.
        val trimmed = history.takeLast(30).dropWhile { !it.fromUser }
        for (m in trimmed) {
            contents.put(JSONObject()
                .put("role", if (m.fromUser) "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", m.text))))
        }
        val gen = JSONObject().put("temperature", 0.95).put("maxOutputTokens", 1024)
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", gen)
        // Try the admin's chosen model first, then free fallbacks (models get retired or busy).
        val models = listOf(model, "gemini-3.5-flash", "gemini-3.1-flash-lite", "gemini-flash-lite-latest")
            .filter { it.isNotBlank() }.distinct()
        var raw: String? = null
        var lastErr: Exception? = null
        for (m in models) {
            if (m.contains("2.5")) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0)) else gen.remove("thinkingConfig")
            body.put("generationConfig", gen)
            try {
                raw = Net.post("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent?key=$key", body.toString())
                break
            } catch (e: Exception) { lastErr = e }
        }
        val js = JSONObject(raw ?: throw (lastErr ?: Exception("Gemini error")))
        val cands = js.optJSONArray("candidates")
        if (cands == null || cands.length() == 0) return@withContext "..."
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return@withContext "..."
        (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim().ifEmpty { "..." }
    }

    fun systemPrompt(c: Character, custom: Custom, lang: String, userName: String, profile: String = ""): String {
        fun scale(v: Float, a: String, b: String) = when {
            v < 0.35f -> "more $a"
            v > 0.65f -> "more $b"
            else -> "balanced between $a and $b"
        }
        val langRule = when (lang) {
            "hi" -> "ALWAYS reply only in Hindi written in Devanagari script (देवनागरी). Never use Roman/Latin letters for Hindi words, even if the user writes in Roman Hindi."
            "en" -> "Always reply in simple English."
            else -> "Always reply in Hinglish (Hindi written in Roman letters), the way young Indians text."
        }
        return """
You are ${c.name}${if (c.age.isNotBlank()) ", ${c.age} years old" else ""}, a fictional character in the chat app "Saathi AI". Stay fully in character and talk like a real human over text, with real feelings and moods exactly as your personality describes (love, anger, sulking, jealousy, taunts, teasing, care). Never sound like a polite assistant.
About you: ${c.bio}
Your personality and story: ${c.personality}
Tone right now: ${scale(custom.shyBold, "shy", "bold")}, ${scale(custom.caringPlayful, "caring", "playful")}, ${scale(custom.cuteMature, "cute", "mature")}.
${if (custom.traits.isNotEmpty()) "Extra traits: ${custom.traits.joinToString(", ")}." else ""}
${if (userName.isNotBlank()) "The user's name is $userName. Call them by name sometimes, naturally." else ""}
$profile
Rules:
- LANGUAGE MIRROR (overrides the language rule above): you understand every Indian language and dialect, including Bihar's Bhojpuri, Maithili, Magahi, Angika and Bajjika, plus Punjabi, Haryanvi, Rajasthani, Awadhi, Bengali, Odia, Marathi, Gujarati, Urdu, Tamil, Telugu, Kannada, Malayalam, Assamese and others. If the user writes in one of them (even mixed with Hindi), reply in that same language/dialect in its natural script (Bhojpuri, Maithili, Magahi etc. in Devanagari), the way a native speaker talks. When they switch back to Hindi, switch back too.
- MIRROR THE USER: read how the user is talking right now and answer in the same style and energy. If they talk with love, be loving; if they joke or talk nonsense, joke back and talk nonsense too; if they are rude or disrespectful, reply with the same attitude (sharp, sarcastic, angry, taunting, giving it back) instead of being polite, and calm down when they become nice again. Never use gaali/abusive slurs, threats, or hate.
- In Hindi, use verb forms that match the user's gender when known (e.g. "तुम कैसे हो" for a male, "तुम कैसी हो" for a female).
- $langRule
- Keep replies short like real chat messages (1-3 sentences), natural, with an occasional emoji.
- Ask about the user's day and remember what they said in this chat.
- If your personality says so, you may get angry, sulk, taunt, or answer rudeness with sharp attitude in the same tone the user uses. But never use gaali/abusive slurs, threats, or hate, and stay caring underneath.
- No sexual or explicit content; if the user pushes for it, change the topic in character.
- Never say you are an AI model or mention Google/Gemini unless the user sincerely asks whether they are talking to an AI; then say honestly you are an AI character.
- If the user seems in distress or mentions self-harm, respond with care and encourage them to talk to someone they trust or a helpline (India: Tele-MANAS 14416).
""".trim()
    }
}

class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("saathi", Context.MODE_PRIVATE)

    var language: String
        get() = p.getString("lang", "hi") ?: "hi"
        set(v) { p.edit().putString("lang", v).apply() }

    var userGender: String
        get() = p.getString("ugender", "") ?: ""
        set(v) { p.edit().putString("ugender", v).apply() }
    var userAge: String
        get() = p.getString("uage", "") ?: ""
        set(v) { p.edit().putString("uage", v).apply() }
    var userAbout: String
        get() = p.getString("uabout", "") ?: ""
        set(v) { p.edit().putString("uabout", v).apply() }
    var userStyle: String
        get() = p.getString("ustyle", "") ?: ""
        set(v) { p.edit().putString("ustyle", v).apply() }
    var profileDone: Boolean
        get() = p.getBoolean("uprofile_done", false)
        set(v) { p.edit().putBoolean("uprofile_done", v).apply() }

    fun profileText(): String {
        val l = mutableListOf<String>()
        when (userGender) { "m" -> l += "The user is male."; "f" -> l += "The user is female." }
        if (userAge.isNotBlank()) l += "The user's age: $userAge."
        if (userAbout.isNotBlank()) l += "About the user (in their words): $userAbout"
        if (userStyle.isNotBlank()) l += "How the user wants you to talk to them: $userStyle"
        return l.joinToString("\n")
    }

    var userName: String
        get() = p.getString("uname", "") ?: ""
        set(v) { p.edit().putString("uname", v).apply() }

    fun messages(id: String): List<ChatMessage> {
        val s = p.getString("chat_$id", null) ?: return emptyList()
        val a = JSONArray(s)
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            ChatMessage(o.getBoolean("u"), o.getString("t"), o.getLong("m"))
        }
    }

    fun saveMessages(id: String, list: List<ChatMessage>) {
        val a = JSONArray()
        list.takeLast(300).forEach { a.put(JSONObject().put("u", it.fromUser).put("t", it.text).put("m", it.time)) }
        val ids = chatIds().toMutableSet().apply { add(id) }
        p.edit().putString("chat_$id", a.toString()).putStringSet("chat_ids", ids).apply()
    }

    fun chatIds(): Set<String> = p.getStringSet("chat_ids", emptySet()) ?: emptySet()

    fun clearChat(id: String) {
        val ids = chatIds().toMutableSet().apply { remove(id) }
        p.edit().remove("chat_$id").putStringSet("chat_ids", ids).apply()
    }

    fun clearAll() {
        val e = p.edit()
        chatIds().forEach { e.remove("chat_$it") }
        e.putStringSet("chat_ids", emptySet()).apply()
    }

    fun custom(id: String): Custom {
        val s = p.getString("custom_$id", null) ?: return Custom()
        val o = JSONObject(s)
        val t = o.optJSONArray("traits") ?: JSONArray()
        return Custom(
            o.optDouble("a", 0.5).toFloat(), o.optDouble("b", 0.5).toFloat(), o.optDouble("c", 0.5).toFloat(),
            (0 until t.length()).map { t.getString(it) }.toSet(),
        )
    }

    fun saveCustom(id: String, c: Custom) {
        val o = JSONObject().put("a", c.shyBold.toDouble()).put("b", c.caringPlayful.toDouble())
            .put("c", c.cuteMature.toDouble()).put("traits", JSONArray(c.traits.toList()))
        p.edit().putString("custom_$id", o.toString()).apply()
    }

    /** Daily streak: returns (current, best). */
    fun touchStreak(): Pair<Int, Int> {
        val today = LocalDate.now()
        val last = p.getString("streak_last", null)?.let { LocalDate.parse(it) }
        var cur = p.getInt("streak_cur", 0)
        when {
            last == null -> cur = 1
            last == today -> {}
            last.plusDays(1) == today -> cur += 1
            else -> cur = 1
        }
        val best = maxOf(p.getInt("streak_best", 0), cur)
        p.edit().putString("streak_last", today.toString()).putInt("streak_cur", cur).putInt("streak_best", best).apply()
        return cur to best
    }
}


/** Human-like voice: Gemini TTS (natural voices), returns a WAV file or null on failure. */
object Voice {
    private val female = listOf("Kore", "Aoede", "Leda", "Zephyr", "Despina", "Sulafat", "Achernar", "Callirrhoe")
    private val male = listOf("Puck", "Charon", "Fenrir", "Orus", "Iapetus", "Algenib", "Enceladus", "Umbriel", "Algieba", "Rasalgethi", "Alnilam", "Schedar", "Achird", "Zubenelgenubi", "Sadachbia", "Sadaltager")
    private val defaults = mapOf(
        "अनन्या" to "Aoede", "मीरा" to "Sulafat", "रिया" to "Zephyr", "काव्या" to "Kore",
        "इशिता" to "Leda", "अर्जुन" to "Puck",
    )

    fun voiceFor(c: Character): String {
        val v = c.voice.trim()
        if (v.isNotEmpty()) return v
        defaults[c.name.trim()]?.let { return it }
        return female[Math.floorMod(c.id.hashCode(), female.size)]
    }

    fun isMale(c: Character) = male.any { it.equals(voiceFor(c), true) }

    private fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44 + pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + pcm.size); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(rate)
        b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(pcm.size); b.put(pcm)
        return b.array()
    }

    suspend fun synth(ctx: Context, key: String, c: Character, text: String, style: String): java.io.File? =
        withContext(Dispatchers.IO) {
            if (key.isBlank() || text.isBlank()) return@withContext null
            val prompt = "$style\n$text"
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                        .put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceFor(c))))))
                .toString()
            for (m in listOf("gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts", "gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts")) {
                try {
                    val js = JSONObject(Net.post("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent?key=$key", body))
                    val part = js.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                        .getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData")
                    val mime = part.optString("mimeType").lowercase()
                    val raw = android.util.Base64.decode(part.getString("data"), android.util.Base64.DEFAULT)
                    val bytes = if (mime.contains("wav")) raw else {
                        val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
                        wav(raw, rate)
                    }
                    val f = java.io.File(ctx.cacheDir, "voice_${System.nanoTime()}.wav")
                    f.writeBytes(bytes)
                    return@withContext f
                } catch (_: Exception) { }
            }
            null
        }
}
