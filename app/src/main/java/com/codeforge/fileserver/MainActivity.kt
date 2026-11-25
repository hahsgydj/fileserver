package com.codeforge.fileserver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var portInput: EditText
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        statusText = TextView(this).apply { text = "Server stopped"; textSize = 16f }
        portInput = EditText(this).apply { setText("8080"); hint = "Port" }
        startBtn = Button(this).apply { text = "Start Server" }
        stopBtn = Button(this).apply { text = "Stop Server"; isEnabled = false }

        root.addView(statusText); root.addView(portInput); root.addView(startBtn); root.addView(stopBtn)
        setContentView(root)

        startBtn.setOnClickListener { startServer() }
        stopBtn.setOnClickListener { stopServer() }
    }

    private fun checkAndRequestPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
                Toast.makeText(this, "Grant 'All files access' then return", Toast.LENGTH_LONG).show()
                return false
            }
        } else {
            val needed = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isNotEmpty()) { requestPermissions(needed.toTypedArray(), 100); return false }
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        return true
    }

    private fun startServer() {
        if (!checkAndRequestPermissions()) return
        val port = portInput.text.toString().toIntOrNull() ?: 8080
        val intent = Intent(this, FileServerService::class.java).apply { putExtra("port", port) }
        ContextCompat.startForegroundService(this, intent)
        val ip = getLocalIp()
        statusText.text = "Running: http://$ip:$port/\n(Open from any browser on same WiFi)"
        startBtn.isEnabled = false; stopBtn.isEnabled = true
    }

    private fun stopServer() {
        stopService(Intent(this, FileServerService::class.java))
        statusText.text = "Server stopped"
        startBtn.isEnabled = true; stopBtn.isEnabled = false
    }

    private fun getLocalIp(): String {
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) return addr.hostAddress ?: "127.0.0.1"
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }
}
