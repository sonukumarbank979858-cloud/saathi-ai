package com.sonu.saathi

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Characters send a message now and then, even when the app is closed. */
class NudgeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = Store(ctx)
        if (!store.notifyOn) return Result.success()
        val hour = LocalTime.now().hour
        if (hour < 8 || hour >= 22) return Result.success() // quiet hours
        return try {
            val config = Firestore.config()
            if (config["notifyEnabled"] == false) return Result.success()
            val everyH = ((config["notifyHours"] as? Number)?.toLong() ?: 4L).coerceIn(1, 48)
            val last = store.lastNudge
            if (System.currentTimeMillis() - last < everyH * 3600_000L - 10 * 60_000L) return Result.success()
            val key = config["geminiKey"]?.toString().orEmpty()
            if (key.isBlank()) return Result.success()
            val model = config["model"]?.toString()?.ifBlank { null } ?: "gemini-3.5-flash"
            val chars = Firestore.characters()
            if (chars.isEmpty()) return Result.success()
            // Prefer characters the user has chatted with.
            val talked = chars.filter { it.id in store.chatIds() }
            val c = (talked.ifEmpty { chars }).random()
            val history = store.messages(c.id)
            val sys = Gemini.systemPrompt(c, store.custom(c.id), store.language, store.userName, store.profileText()) +
                "\n\nThe user has not opened the app for a while. Send ONE short message (max 20 words) on your own to start a chat, " +
                "in your personality and mood (missing them, a taunt, a question about their day, sharing something). Use what you remember from the chat."
            val prompt = history + ChatMessage(true, "(user is away; send your own message now)", System.currentTimeMillis())
            val text = Gemini.reply(key, model, sys, prompt).trim()
            if (text.isBlank() || text == "...") return Result.success()
            store.saveMessages(c.id, history + ChatMessage(false, text, System.currentTimeMillis()))
            store.lastNudge = System.currentTimeMillis()
            Notify.show(ctx, c, text)
            Result.success()
        } catch (e: Exception) { Result.retry() }
    }
}

object Notify {
    private const val CH = "chars"

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<NudgeWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("nudge", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun show(ctx: Context, c: Character, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "किरदारों के संदेश", NotificationManager.IMPORTANCE_HIGH))
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("charId", c.id)
        }
        val pi = PendingIntent.getActivity(ctx, c.id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(c.name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(c.id.hashCode(), n) } catch (_: SecurityException) {}
    }
}
