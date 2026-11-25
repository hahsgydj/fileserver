package com.codeforge.fileserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

class FileServerService : Service() {
    companion object {
        var isRunning = false
        private const val PREFS = "server_prefs"
        private const val TOKEN_KEY = "gh_token"
        
        fun getSavedToken(context: Context): String {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TOKEN_KEY, "") ?: ""
        }
        fun saveToken(context: Context, token: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TOKEN_KEY, token).apply()
        }
    }

    private var server: WebServer? = null
    private var ipSyncThread: Thread? = null
    private var lastIp: String = ""

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
        val token = intent?.getStringExtra("token") ?: getSavedToken(this)
        
        server?.stop()
        server = WebServer(port).also { it.start() }
        isRunning = true
        
        startIpSync(token)
        return START_STICKY
    }

    private fun startIpSync(token: String) {
        if (token.isEmpty()) return
        ipSyncThread?.interrupt()
        ipSyncThread = thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val ip = URL("https://api.ipify.org").readText()
                    if (ip != lastIp) {
                        lastIp = ip
                        updateGithub(token, ip)
                    }
                    Thread.sleep(60000 * 5) // Har 5 minute me check
                } catch (e: Exception) {
                    Thread.sleep(60000)
                }
            }
        }
    }

    private fun updateGithub(token: String, ip: String) {
        try {
            val apiUrl = URL("https://api.github.com/repos/hahsgydj/fileserver/contents/ip.txt")
            val conn = apiUrl.openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("Authorization", "token $token")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            
            var sha: String? = null
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                sha = Regex("\"sha\"\\s*:\\s*\"(.*?)\"").find(response)?.groupValues?.get(1)
            }

            val putUrl = URL("https://api.github.com/repos/hahsgydj/fileserver/contents/ip.txt")
            val putConn = putUrl.openConnection() as java.net.HttpURLConnection
            putConn.requestMethod = "PUT"
            putConn.setRequestProperty("Authorization", "token $token")
            putConn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            putConn.setRequestProperty("Content-Type", "application/json")
            putConn.doOutput = true

            val encodedIp = java.util.Base64.getEncoder().encodeToString(ip.toByteArray())
            val json = """
                {
                  "message": "Update IP to $ip",
                  "content": "$encodedIp",
                  ${if (sha != null) "\"sha\": \"$sha\"," else ""}
                  "branch": "main"
                }
            """.trimIndent()

            putConn.outputStream.write(json.toByteArray())
            putConn.responseCode
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        isRunning = false
        ipSyncThread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// Yahan wahi WebServer (NanoHTTPD) ka code rahega jo pehle tha
class WebServer(private val port: Int) : fi.iki.elonen.NanoHTTPD(port) {
    // ... [Pichle message wala WebServer ka poora code isi jagah paste karna hai, bina kisi change ke] ...
}
