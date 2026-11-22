package com.sonu.saathi

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** In-app update from the GitHub "latest" release (build number in the release body: "Build #N"). */
object Updater {
    const val APK_URL = "https://github.com/sonukumarbank979858-cloud/saathi-ai/releases/download/latest/SaathiAI.apk"
    private const val API = "https://api.github.com/repos/sonukumarbank979858-cloud/saathi-ai/releases/tags/latest"

    /** Returns the newer build number, or null if up to date. */
    suspend fun check(): Int? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject(Net.get(API)).optString("body")
            val n = Regex("Build #(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return@withContext null
            if (n > BuildConfig.VERSION_CODE) n else null
        } catch (_: Exception) { null }
    }

    suspend fun download(ctx: Context, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        val f = File(dir, "SaathiAI.apk")
        var url = APK_URL
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20000; c.readTimeout = 60000
            val code = c.responseCode
            if (code in 300..399 && hops++ < 5) { url = c.getHeaderField("Location"); c.disconnect(); continue }
            if (code !in 200..299) throw Exception("HTTP $code")
            break
        }
        val total = c.contentLengthLong
        c.inputStream.use { inp ->
            f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) {
                    val r = inp.read(buf); if (r < 0) break
                    out.write(buf, 0, r); done += r
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        c.disconnect()
        f
    }

    fun canInstall(ctx: Context) = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun install(ctx: Context, f: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openInBrowser(ctx: Context) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
