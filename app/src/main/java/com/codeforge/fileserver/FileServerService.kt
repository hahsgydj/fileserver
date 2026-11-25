package com.codeforge.fileserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileServerService : Service() {
    private var server: WebServer? = null

    override fun onCreate() {
        super.onCreate()
        val channelId = "cf_fileserver"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(channelId, "File Server", NotificationManager.IMPORTANCE_LOW))
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("CF File Server").setContentText("Serving phone storage over HTTP")
            .setSmallIcon(android.R.drawable.stat_sys_upload).build()
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra("port", 8080) ?: 8080
        server?.stop()
        server = WebServer(port).also { it.start() }
        return START_STICKY
    }

    override fun onDestroy() { server?.stop(); server = null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}

class WebServer(private val port: Int) : fi.iki.elonen.NanoHTTPD(port) {
    private val root: File = Environment.getExternalStorageDirectory()
    private val mimeMap = mapOf(
        "html" to "text/html", "txt" to "text/plain", "json" to "application/json",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        "gif" to "image/gif", "mp4" to "video/mp4", "mp3" to "audio/mpeg",
        "pdf" to "application/pdf", "zip" to "application/zip", "apk" to "application/vnd.android.package-archive"
    )

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val decoded = URLDecoder.decode(uri, "UTF-8").trimStart('/')
        val target = if (decoded.isEmpty()) root else File(root, decoded)
        if (!target.canonicalPath.startsWith(root.canonicalPath)) return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden")
        return try {
            when {
                session.method == Method.GET && uri.startsWith("/download") -> downloadDir(target)
                target.isDirectory -> serveDirectory(target, uri)
                target.isFile -> serveFile(target)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
        } catch (e: Exception) { newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}") }
    }

    private fun serveDirectory(dir: File, uriPath: String): Response {
        val entries = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        val html = StringBuilder()
        html.append("<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>${dir.name}</title></head><body style='font-family:sans-serif;padding:16px'>")
        html.append("<h2>${dir.name.ifEmpty { "/storage/emulated/0" }}</h2>")
        if (uriPath != "/") {
            val parent = uriPath.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
            html.append("<a href='$parent'>../</a><br>")
        }
        for (f in entries) {
            val path = uriPath.trimEnd('/') + "/" + f.name
            val icon = if (f.isDirectory) "📁" else "📄"
            val size = if (f.isDirectory) "" else " (${humanSize(f.length())})"
            html.append("<a href='${path.replace(" ", "%20")}' style='display:block;padding:6px 0'>$icon ${f.name}$size</a>")
        }
        html.append("<hr><form action='/download' method='get'><input type='hidden' name='path' value='${uriPath}'><button type='submit'>Download folder as ZIP</button></form></body></html>")
        return newFixedLengthResponse(Response.Status.OK, "text/html", html.toString())
    }

    private fun serveFile(file: File): Response {
        val ext = file.extension.lowercase()
        val mime = mimeMap[ext] ?: "application/octet-stream"
        val stream = FileInputStream(file)
        val response = newChunkedResponse(Response.Status.OK, mime, stream)
        response.addHeader("Content-Disposition", "inline; filename=\"${file.name}\"")
        return response
    }

    private fun downloadDir(dir: File): Response {
        val baos = java.io.ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val entryName = file.relativeTo(dir).path
                zos.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        val bytes = baos.toByteArray()
        val response = newFixedLengthResponse(Response.Status.OK, "application/zip", bytes.inputStream(), bytes.size.toLong())
        response.addHeader("Content-Disposition", "attachment; filename=\"${dir.name}.zip\"")
        return response
    }

    private fun humanSize(b: Long): String {
        if (b < 1024) return "$b B"
        val kb = b / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.1f GB".format(mb / 1024.0)
    }
}
