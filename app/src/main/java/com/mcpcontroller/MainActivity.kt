package com.mcpcontroller

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mcpcontroller.media.ScreenCaptureService
import com.mcpcontroller.server.MCPServerService
import com.mcpcontroller.tunnel.NgrokTunnel
import com.mcpcontroller.utils.NetworkUtils
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvServerUrl: TextView
    private lateinit var tvPort: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvPermAccessibility: TextView
    private lateinit var tvPermScreenCapture: TextView
    private lateinit var tvPermStorage: TextView
    private lateinit var statusDot: android.view.View
    private lateinit var btnStartServer: Button
    private lateinit var btnStopServer: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnScreenCapture: Button
    private lateinit var btnClearLog: Button
    private lateinit var etNgrokToken: EditText
    private lateinit var btnSaveToken: Button
    private lateinit var btnStartNgrok: Button
    private lateinit var tvNgrokUrl: TextView

    private lateinit var prefs: SharedPreferences

    private val mediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            ScreenCaptureService.projectionResultCode = result.resultCode
            ScreenCaptureService.projectionData = result.data
            updatePermissions()
            addLog("✅ Screen capture permission granted")
        } else {
            addLog("❌ Screen capture permission denied")
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        updatePermissions()
        if (permissions.values.all { it }) addLog("✅ Storage permissions granted")
    }

    companion object {
        var instance: MainActivity? = null
        fun addExternalLog(message: String) {
            instance?.runOnUiThread { instance?.addLog(message) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        instance = this
        prefs = getSharedPreferences("mcp_prefs", Context.MODE_PRIVATE)

        initViews()
        setupClickListeners()
        updatePermissions()
        updateServerStatus(false)

        // Saved token load karo
        val savedToken = prefs.getString("ngrok_token", "") ?: ""
        if (savedToken.isNotEmpty()) {
            etNgrokToken.setText(savedToken)
            addLog("🔑 Ngrok token loaded")
        }

        addLog("🚀 MCP Controller started")
        addLog("📡 IP: ${NetworkUtils.getLocalIpAddress()}")
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvServerUrl = findViewById(R.id.tvServerUrl)
        tvPort = findViewById(R.id.tvPort)
        tvLog = findViewById(R.id.tvLog)
        tvPermAccessibility = findViewById(R.id.tvPermAccessibility)
        tvPermScreenCapture = findViewById(R.id.tvPermScreenCapture)
        tvPermStorage = findViewById(R.id.tvPermStorage)
        statusDot = findViewById(R.id.statusDot)
        btnStartServer = findViewById(R.id.btnStartServer)
        btnStopServer = findViewById(R.id.btnStopServer)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnScreenCapture = findViewById(R.id.btnScreenCapture)
        btnClearLog = findViewById(R.id.btnClearLog)
        etNgrokToken = findViewById(R.id.etNgrokToken)
        btnSaveToken = findViewById(R.id.btnSaveToken)
        btnStartNgrok = findViewById(R.id.btnStartNgrok)
        tvNgrokUrl = findViewById(R.id.tvNgrokUrl)
    }

    private fun setupClickListeners() {
        btnStartServer.setOnClickListener { startMCPServer() }
        btnStopServer.setOnClickListener { stopMCPServer() }

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            addLog("📱 Opened Accessibility Settings")
            Toast.makeText(this, "Enable 'MCP Controller' in Accessibility Services", Toast.LENGTH_LONG).show()
        }

        btnScreenCapture.setOnClickListener {
            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }

        btnClearLog.setOnClickListener {
            tvLog.text = "[Log cleared]\n"
        }

        // Token save karo SharedPreferences mein
        btnSaveToken.setOnClickListener {
            val token = etNgrokToken.text.toString().trim()
            if (token.isEmpty()) {
                Toast.makeText(this, "Token enter karo", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("ngrok_token", token).apply()
            addLog("✅ Ngrok token saved")
            Toast.makeText(this, "Token saved!", Toast.LENGTH_SHORT).show()
        }

        // Ngrok tunnel start karo
        btnStartNgrok.setOnClickListener {
            val token = prefs.getString("ngrok_token", "") ?: ""
            addLog("🌐 Ngrok tunnel starting...")
            tvNgrokUrl.text = "Connecting..."
            btnStartNgrok.isEnabled = false

            NgrokTunnel.start(this@MainActivity, token, 8080,
                { status -> runOnUiThread { addLog("🌐 $status") } },
                { url -> runOnUiThread {
                    tvNgrokUrl.text = "MCP URL: $url/mcp"
                    addLog("✅ Ngrok URL: $url/mcp")
                    addLog("📋 Claude mein add karo: $url/mcp")
                    btnStartNgrok.isEnabled = true
                }},
                { err -> runOnUiThread { addLog("❌ $err"); btnStartNgrok.isEnabled = true } }
            )
        }
    }

    private fun startMCPServer() {
        val intent = Intent(this, MCPServerService::class.java)
        ContextCompat.startForegroundService(this, intent)
        updateServerStatus(true)
        addLog("▶ MCP Server starting on port 8080...")
        val ip = NetworkUtils.getLocalIpAddress()
        tvServerUrl.text = "URL: http://$ip:8080"
        tvPort.text = "Port: 8080  |  MCP: http://$ip:8080/mcp"
        btnStartServer.isEnabled = false
        btnStopServer.isEnabled = true
    }

    private fun stopMCPServer() {
        stopService(Intent(this, MCPServerService::class.java))
        updateServerStatus(false)
        addLog("⏹ MCP Server stopped")
        btnStartServer.isEnabled = true
        btnStopServer.isEnabled = false
    }

    private fun updateServerStatus(running: Boolean) {
        if (running) {
            tvStatus.text = "Server Running ✅"
            tvStatus.setTextColor(0xFF4CAF50.toInt())
            statusDot.setBackgroundResource(R.drawable.status_dot_green)
        } else {
            tvStatus.text = "Server Stopped"
            tvStatus.setTextColor(0xFFFFFFFF.toInt())
            statusDot.setBackgroundResource(R.drawable.status_dot_red)
            tvServerUrl.text = "URL: Not started"
            tvPort.text = "Port: 8080"
        }
    }

    private fun updatePermissions() {
        val accessibilityEnabled = isAccessibilityEnabled()
        tvPermAccessibility.text = if (accessibilityEnabled) "✅ Accessibility Service" else "❌ Accessibility Service"
        tvPermAccessibility.setTextColor(if (accessibilityEnabled) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())

        val screenCaptureGranted = ScreenCaptureService.projectionData != null
        tvPermScreenCapture.text = if (screenCaptureGranted) "✅ Screen Capture" else "❌ Screen Capture"
        tvPermScreenCapture.setTextColor(if (screenCaptureGranted) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())

        val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) true
        else ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        tvPermStorage.text = if (storageGranted) "✅ Storage" else "❌ Storage"
        tvPermStorage.setTextColor(if (storageGranted) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())

        if (!storageGranted) {
            storagePermissionLauncher.launch(arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ))
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.id.contains("com.mcpcontroller") }
    }

    fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val current = tvLog.text.toString()
        val lines = current.lines()
        val limited = if (lines.size > 50) lines.takeLast(50).joinToString("\n") else current
        tvLog.text = "$limited[$time] $message\n"
    }

    override fun onResume() {
        super.onResume()
        updatePermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
