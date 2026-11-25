package com.codeforge.fileserver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.content.Context

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var portInput: EditText
    private lateinit var githubTokenInput: EditText
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var batteryBtn: Button

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        statusText = TextView(this).apply {
            text = "Server stopped"
            textSize = 16f
        }
        portInput = EditText(this).apply { setText("8080"); hint = "Port" }
        
        githubTokenInput = EditText(this).apply { 
            hint = "GitHub Token (ghp_...)" 
            // FIX: Use applicationContext to avoid Type Mismatch
            setText(FileServerService.getSavedToken(applicationContext))
        }

        batteryBtn = Button(this).apply { text = "Ignore Battery Optimization (Do it once)" }
        startBtn = Button(this).apply { text = "Start Server" }
        stopBtn = Button(this).apply { text = "Stop Server"; isEnabled = false }

        root.addView(statusText)
        root.addView(portInput)
        root.addView(githubTokenInput)
        root.addView(batteryBtn)
        root.addView(startBtn)
        root.addView(stopBtn)
        setContentView(root)

        batteryBtn.setOnClickListener { askBatteryOptimization() }
        startBtn.setOnClickListener { startServer() }
        stopBtn.setOnClickListener { stopServer() }

        if (FileServerService.isRunning) {
            startBtn.isEnabled = false
            stopBtn.isEnabled = true
        }
    }

    private fun askBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } else {
            Toast.makeText(this, "Already optimized!", Toast.LENGTH_SHORT).show()
        }
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
        val token = githubTokenInput.text.toString().trim()
        
        FileServerService.saveToken(applicationContext, token)

        val intent = Intent(this, FileServerService::class.java).apply {
            putExtra("port", port)
            putExtra("token", token)
        }
        ContextCompat.startForegroundService(this, intent)

        statusText.text = "Running on port $port\n(Check GitHub ip.txt for IP)"
        startBtn.isEnabled = false
        stopBtn.isEnabled = true
    }

    private fun stopServer() {
        stopService(Intent(this, FileServerService::class.java))
        statusText.text = "Server stopped"
        startBtn.isEnabled = true
        stopBtn.isEnabled = false
    }
}
