package com.mcpcontroller.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.mcpcontroller.MainActivity
import com.mcpcontroller.accessibility.MCPAccessibilityService
import com.mcpcontroller.media.ScreenCaptureService
import com.mcpcontroller.utils.NetworkUtils
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.gson.*
import kotlinx.coroutines.*

class MCPServerService : Service() {

    private val gson = Gson()
    private var server: NettyApplicationEngine? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val CHANNEL_ID = "MCPServer"
        private const val NOTIF_ID = 1
        private const val PORT = 8080
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        // Start screen capture service too
        if (ScreenCaptureService.projectionData != null) {
            val intent = Intent(this, ScreenCaptureService::class.java)
            ContextCompat.startForegroundService(this, intent)
        }

        startServer()
    }

    private fun startServer() {
        serviceScope.launch {
            try {
                server = embeddedServer(Netty, port = PORT) {
                    install(CORS) {
                        anyHost()
                        allowHeader(HttpHeaders.ContentType)
                        allowHeader(HttpHeaders.Authorization)
                        allowMethod(HttpMethod.Options)
                        allowMethod(HttpMethod.Post)
                        allowMethod(HttpMethod.Get)
                    }
                    install(ContentNegotiation) { gson() }

                    routing {
                        setupRoutes()
                    }
                }.also { it.start(wait = false) }

                MainActivity.addExternalLog("✅ MCP Server running on :$PORT")
            } catch (e: Exception) {
                MainActivity.addExternalLog("❌ Server error: ${e.message}")
            }
        }
    }

    private fun Routing.setupRoutes() {

        // ── HEALTH ──────────────────────────────────────────────────────────
        get("/health") {
            call.respond(mapOf(
                "status" to "ok",
                "server" to "MCP Android Controller",
                "version" to "1.0",
                "ip" to NetworkUtils.getLocalIpAddress(),
                "port" to PORT,
                "accessibility" to MCPAccessibilityService.isRunning(),
                "screenCapture" to (ScreenCaptureService.instance != null)
            ))
        }

        // ── SCREENSHOT ──────────────────────────────────────────────────────
        get("/screenshot") {
            MainActivity.addExternalLog("📷 Screenshot requested")
            val svc = ScreenCaptureService.instance
            if (svc == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Screen capture not ready"))
                return@get
            }
            val b64 = withContext(Dispatchers.IO) { svc.takeScreenshotBase64() }
            if (b64 == null) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Screenshot failed"))
            } else {
                val dims = svc.getScreenDimensions()
                call.respond(mapOf(
                    "success" to true,
                    "image" to b64,
                    "format" to "jpeg",
                    "width" to dims.first,
                    "height" to dims.second
                ))
            }
        }

        // ── TAP ─────────────────────────────────────────────────────────────
        post("/tap") {
            val body = call.receive<Map<String, Any>>()
            val x = (body["x"] as? Number)?.toFloat() ?: 0f
            val y = (body["y"] as? Number)?.toFloat() ?: 0f
            MainActivity.addExternalLog("👆 Tap ($x, $y)")
            val svc = MCPAccessibilityService.instance
            if (svc == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Accessibility service not running"))
                return@post
            }
            val result = svc.tap(x, y)
            call.respond(mapOf("success" to result, "x" to x, "y" to y))
        }

        // ── LONG PRESS ──────────────────────────────────────────────────────
        post("/longpress") {
            val body = call.receive<Map<String, Any>>()
            val x = (body["x"] as? Number)?.toFloat() ?: 0f
            val y = (body["y"] as? Number)?.toFloat() ?: 0f
            val svc = MCPAccessibilityService.instance
            if (svc == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Accessibility service not running"))
                return@post
            }
            val result = svc.longPress(x, y)
            call.respond(mapOf("success" to result))
        }

        // ── SWIPE ───────────────────────────────────────────────────────────
        post("/swipe") {
            val body = call.receive<Map<String, Any>>()
            val x1 = (body["x1"] as? Number)?.toFloat() ?: 0f
            val y1 = (body["y1"] as? Number)?.toFloat() ?: 0f
            val x2 = (body["x2"] as? Number)?.toFloat() ?: 0f
            val y2 = (body["y2"] as? Number)?.toFloat() ?: 0f
            val duration = (body["duration"] as? Number)?.toLong() ?: 300L
            MainActivity.addExternalLog("👉 Swipe ($x1,$y1)→($x2,$y2)")
            val svc = MCPAccessibilityService.instance
            if (svc == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Accessibility service not running"))
                return@post
            }
            val result = svc.swipe(x1, y1, x2, y2, duration)
            call.respond(mapOf("success" to result))
        }

        // ── TYPE TEXT ───────────────────────────────────────────────────────
        post("/type") {
            val body = call.receive<Map<String, Any>>()
            val text = body["text"] as? String ?: ""
            val append = body["append"] as? Boolean ?: false
            MainActivity.addExternalLog("⌨ Type: \"${text.take(30)}\"")
            val svc = MCPAccessibilityService.instance
            if (svc == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Accessibility service not running"))
                return@post
            }
            val result = if (append) svc.appendText(text) else svc.typeText(text)
            call.respond(mapOf("success" to result, "text" to text))
        }

        // ── KEY EVENT ───────────────────────────────────────────────────────
        post("/keyevent") {
            val body = call.receive<Map<String, Any>>()
            val key = body["key"] as? String ?: ""
            MainActivity.addExternalLog("🔑 Key: $key")
            val svc = MCPAccessibilityService.instance
            if (svc == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Accessibility service not running"))
                return@post
            }
            val result = when (key.lowercase()) {
                "back" -> svc.pressBack()
                "home" -> svc.pressHome()
                "recents", "recent" -> svc.pressRecents()
                "notifications" -> svc.pressNotifications()
                "quicksettings", "qs" -> svc.pressQuickSettings()
                "power" -> svc.pressPowerDialog()
                else -> false
            }
            call.respond(mapOf("success" to result, "key" to key))
        }

        // ── SCROLL ──────────────────────────────────────────────────────────
        post("/scroll") {
            val body = call.receive<Map<String, Any>>()
            val direction = body["direction"] as? String ?: "down"
            val x = (body["x"] as? Number)?.toFloat() ?: 540f
            val y = (body["y"] as? Number)?.toFloat() ?: 960f
            val svc = MCPAccessibilityService.instance
            if (svc == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Accessibility service not running"))
                return@post
            }
            val result = when (direction.lowercase()) {
                "down" -> svc.scrollDown(x, y)
                "up" -> svc.scrollUp(x, y)
                "left" -> svc.scrollLeft(x, y)
                "right" -> svc.scrollRight(x, y)
                else -> false
            }
            call.respond(mapOf("success" to result, "direction" to direction))
        }

        // ── LAUNCH APP ──────────────────────────────────────────────────────
        post("/launch") {
            val body = call.receive<Map<String, Any>>()
            val packageName = body["package"] as? String ?: ""
            MainActivity.addExternalLog("🚀 Launch: $packageName")
            try {
                val intent = applicationContext.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    applicationContext.startActivity(intent)
                    call.respond(mapOf("success" to true, "package" to packageName))
                } else {
                    call.respond(mapOf("success" to false, "error" to "App not found: $packageName"))
                }
            } catch (e: Exception) {
                call.respond(mapOf("success" to false, "error" to e.message))
            }
        }

        // ── LIST APPS ───────────────────────────────────────────────────────
        get("/apps") {
            val pm = applicationContext.packageManager
            val apps = pm.getInstalledApplications(0).map { app ->
                mapOf(
                    "package" to app.packageName,
                    "name" to pm.getApplicationLabel(app).toString()
                )
            }.sortedBy { it["name"] }
            call.respond(mapOf("apps" to apps, "count" to apps.size))
        }

        // ── GET SCREEN TEXT ─────────────────────────────────────────────────
        get("/screentext") {
            val svc = MCPAccessibilityService.instance
            if (svc == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Accessibility service not running"))
                return@get
            }
            val text = svc.getScreenText()
            call.respond(mapOf("text" to text))
        }

        // ── UI HIERARCHY ────────────────────────────────────────────────────
        get("/uihierarchy") {
            val svc = MCPAccessibilityService.instance
            if (svc == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Accessibility service not running"))
                return@get
            }
            val hierarchy = svc.getUiHierarchy()
            call.respond(HttpStatusCode.OK, hierarchy)
        }

        // ── CLICK BY TEXT ────────────────────────────────────────────────────
        post("/clicktext") {
            val body = call.receive<Map<String, Any>>()
            val text = body["text"] as? String ?: ""
            val svc = MCPAccessibilityService.instance
            if (svc == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Accessibility service not running"))
                return@post
            }
            val result = svc.findAndClickByText(text)
            call.respond(mapOf("success" to result, "text" to text))
        }

        // ── PINCH ───────────────────────────────────────────────────────────
        post("/pinch") {
            val body = call.receive<Map<String, Any>>()
            val direction = body["direction"] as? String ?: "in"
            val cx = (body["x"] as? Number)?.toFloat() ?: 540f
            val cy = (body["y"] as? Number)?.toFloat() ?: 960f
            val svc = MCPAccessibilityService.instance
            if (svc == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Accessibility service not running"))
                return@post
            }
            val result = if (direction == "out") svc.pinchOut(cx, cy) else svc.pinchIn(cx, cy)
            call.respond(mapOf("success" to result))
        }

        // ── OAUTH ──────────────────────
        get("/.well-known/oauth-protected-resource") {
            val base = call.request.host().let { "https://$it" }
            call.respond(mapOf("resource" to "$base/mcp", "authorization_servers" to listOf(base)))
        }

        get("/.well-known/oauth-authorization-server") {
            val base = call.request.host().let { "https://$it" }
            call.respond(mapOf("issuer" to base, "authorization_endpoint" to "$base/authorize", "token_endpoint" to "$base/token", "response_types_supported" to listOf("code")))
        }
        get("/authorize") {
            val redirectUri = call.request.queryParameters["redirect_uri"] ?: ""
            val state = call.request.queryParameters["state"] ?: ""
            val codeChallenge = call.request.queryParameters["code_challenge"] ?: ""
            call.respondRedirect("$redirectUri?code=mcp_code_${codeChallenge.take(8)}&state=$state")
        }
        post("/token") {
            call.respond(mapOf("access_token" to "mcp_token", "token_type" to "Bearer", "expires_in" to 86400))
        }

        // ── MCP PROTOCOL ENDPOINT ────────────────────────────────────────────
        get("/mcp") {
            call.respond(mapOf("jsonrpc" to "2.0", "result" to mapOf("protocolVersion" to "2024-11-05", "serverInfo" to mapOf("name" to "android-mcp-controller"))))
        }

        post("/mcp") {
            val body = call.receiveText()
            val request = gson.fromJson(body, Map::class.java)

            val method = request["method"] as? String ?: ""
            val id = request["id"]
            val params = request["params"] as? Map<*, *>

            MainActivity.addExternalLog("🔌 MCP: $method")

            when (method) {
                "initialize" -> {
                    call.respond(mcpResponse(id, mapOf(
                        "protocolVersion" to "2024-11-05",
                        "capabilities" to mapOf(
                            "tools" to mapOf("listChanged" to false)
                        ),
                        "serverInfo" to mapOf(
                            "name" to "android-mcp-controller",
                            "version" to "1.0.0"
                        )
                    )))
                }

                "tools/list" -> {
                    call.respond(mcpResponse(id, mapOf("tools" to getMCPTools())))
                }

                "tools/call" -> {
                    val toolName = params?.get("name") as? String ?: ""
                    val args = params?.get("arguments") as? Map<*, *> ?: emptyMap<String, Any>()
                    val result = handleToolCall(toolName, args)
                    call.respond(mcpResponse(id, result))
                }

                "notifications/initialized" -> {
                    call.respond(mapOf("jsonrpc" to "2.0"))
                }

                else -> {
                    call.respond(mcpError(id, -32601, "Method not found: $method"))
                }
            }
        }
    }

    private fun mcpResponse(id: Any?, result: Any): Map<String, Any?> {
        return mapOf("jsonrpc" to "2.0", "id" to id, "result" to result)
    }

    private fun mcpError(id: Any?, code: Int, message: String): Map<String, Any?> {
        return mapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to code, "message" to message))
    }

    private suspend fun handleToolCall(toolName: String, args: Map<*, *>): Map<String, Any> {
        val accessibility = MCPAccessibilityService.instance
        val screenCapture = ScreenCaptureService.instance

        return when (toolName) {
            "take_screenshot" -> {
                if (screenCapture == null) return errorContent("Screen capture not available")
                val b64 = withContext(Dispatchers.IO) { screenCapture.takeScreenshotBase64() }
                    ?: return errorContent("Screenshot failed")
                val dims = screenCapture.getScreenDimensions()
                mapOf("content" to listOf(
                    mapOf("type" to "image", "data" to b64, "mimeType" to "image/jpeg"),
                    mapOf("type" to "text", "text" to "Screenshot taken (${dims.first}x${dims.second})")
                ))
            }

            "tap_screen" -> {
                if (accessibility == null) return errorContent("Accessibility service not running")
                val x = (args["x"] as? Number)?.toFloat() ?: 0f
                val y = (args["y"] as? Number)?.toFloat() ?: 0f
                val ok = accessibility.tap(x, y)
                successContent("Tapped at ($x, $y): ${if (ok) "success" else "failed"}")
            }

            "swipe_screen" -> {
                if (accessibility == null) return errorContent("Accessibility service not running")
                val x1 = (args["x1"] as? Number)?.toFloat() ?: 0f
                val y1 = (args["y1"] as? Number)?.toFloat() ?: 0f
                val x2 = (args["x2"] as? Number)?.toFloat() ?: 0f
                val y2 = (args["y2"] as? Number)?.toFloat() ?: 0f
                val duration = (args["duration"] as? Number)?.toLong() ?: 300L
                val ok = accessibility.swipe(x1, y1, x2, y2, duration)
                successContent("Swiped ($x1,$y1)→($x2,$y2): ${if (ok) "success" else "failed"}")
            }

            "type_text" -> {
                if (accessibility == null) return errorContent("Accessibility service not running")
                val text = args["text"] as? String ?: ""
                val ok = accessibility.typeText(text)
                successContent("Typed \"$text\": ${if (ok) "success" else "failed - tap input field first"}")
            }

            "press_key" -> {
                if (accessibility == null) return errorContent("Accessibility service not running")
                val key = args["key"] as? String ?: ""
                val ok = when (key.lowercase()) {
                    "back" -> accessibility.pressBack()
                    "home" -> accessibility.pressHome()
                    "recents" -> accessibility.pressRecents()
                    "notifications" -> accessibility.pressNotifications()
                    "quicksettings" -> accessibility.pressQuickSettings()
                    else -> false
                }
                successContent("Key '$key': ${if (ok) "success" else "failed"}")
            }

            "scroll" -> {
                if (accessibility == null) return errorContent("Accessibility service not running")
                val dir = args["direction"] as? String ?: "down"
                val x = (args["x"] as? Number)?.toFloat() ?: 540f
                val y = (args["y"] as? Number)?.toFloat() ?: 960f
                val ok = when (dir) {
                    "up" -> accessibility.scrollUp(x, y)
                    "down" -> accessibility.scrollDown(x, y)
                    "left" -> accessibility.scrollLeft(x, y)
                    "right" -> accessibility.scrollRight(x, y)
                    else -> false
                }
                successContent("Scrolled $dir: ${if (ok) "success" else "failed"}")
            }

            "launch_app" -> {
                val pkg = args["package"] as? String ?: ""
                try {
                    val intent = applicationContext.packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        applicationContext.startActivity(intent)
                        successContent("Launched $pkg")
                    } else {
                        errorContent("App not found: $pkg")
                    }
                } catch (e: Exception) {
                    errorContent("Error: ${e.message}")
                }
            }

            "get_screen_text" -> {
                if (accessibility == null) return errorContent("Accessibility service not running")
                val text = accessibility.getScreenText()
                successContent("Screen text:\n$text")
            }

            "list_apps" -> {
                val pm = applicationContext.packageManager
                val apps = pm.getInstalledApplications(0).map { app ->
                    "${pm.getApplicationLabel(app)} → ${app.packageName}"
                }.sorted().joinToString("\n")
                successContent("Installed apps:\n$apps")
            }

            "click_by_text" -> {
                if (accessibility == null) return errorContent("Accessibility service not running")
                val text = args["text"] as? String ?: ""
                val ok = accessibility.findAndClickByText(text)
                successContent("Click '$text': ${if (ok) "found and clicked" else "not found"}")
            }

            "long_press" -> {
                if (accessibility == null) return errorContent("Accessibility service not running")
                val x = (args["x"] as? Number)?.toFloat() ?: 0f
                val y = (args["y"] as? Number)?.toFloat() ?: 0f
                val ok = accessibility.longPress(x, y)
                successContent("Long press ($x, $y): ${if (ok) "success" else "failed"}")
            }

            "pinch" -> {
                if (accessibility == null) return errorContent("Accessibility service not running")
                val dir = args["direction"] as? String ?: "in"
                val cx = (args["x"] as? Number)?.toFloat() ?: 540f
                val cy = (args["y"] as? Number)?.toFloat() ?: 960f
                val ok = if (dir == "out") accessibility.pinchOut(cx, cy) else accessibility.pinchIn(cx, cy)
                successContent("Pinch $dir at ($cx, $cy): ${if (ok) "success" else "failed"}")
            }

            "device_info" -> {
                val dims = screenCapture?.getScreenDimensions()
                successContent("""
Device Info:
- Model: ${Build.MODEL}
- Manufacturer: ${Build.MANUFACTURER}
- Android: ${Build.VERSION.RELEASE}
- SDK: ${Build.VERSION.SDK_INT}
- Screen: ${dims?.first ?: "?"}x${dims?.second ?: "?"}
- IP: ${NetworkUtils.getLocalIpAddress()}
- Accessibility: ${if (accessibility != null) "✅" else "❌"}
- ScreenCapture: ${if (screenCapture != null) "✅" else "❌"}
                """.trimIndent())
            }

            else -> errorContent("Unknown tool: $toolName")
        }
    }

    private fun successContent(text: String) = mapOf(
        "content" to listOf(mapOf("type" to "text", "text" to text))
    )

    private fun errorContent(text: String) = mapOf(
        "content" to listOf(mapOf("type" to "text", "text" to "ERROR: $text")),
        "isError" to true
    )

    private fun getMCPTools(): List<Map<String, Any>> = listOf(
        mcpTool("take_screenshot", "Take a screenshot of the Android screen", emptyMap()),
        mcpTool("tap_screen", "Tap at specific coordinates", mapOf(
            "x" to ("number" to "X coordinate"),
            "y" to ("number" to "Y coordinate")
        )),
        mcpTool("swipe_screen", "Swipe from one point to another", mapOf(
            "x1" to ("number" to "Start X"), "y1" to ("number" to "Start Y"),
            "x2" to ("number" to "End X"), "y2" to ("number" to "End Y"),
            "duration" to ("number" to "Duration ms (optional, default 300)")
        )),
        mcpTool("long_press", "Long press at coordinates", mapOf(
            "x" to ("number" to "X coordinate"),
            "y" to ("number" to "Y coordinate")
        )),
        mcpTool("type_text", "Type text into focused input field", mapOf(
            "text" to ("string" to "Text to type")
        )),
        mcpTool("press_key", "Press a system key (back/home/recents/notifications/quicksettings)", mapOf(
            "key" to ("string" to "Key name: back, home, recents, notifications, quicksettings")
        )),
        mcpTool("scroll", "Scroll in a direction", mapOf(
            "direction" to ("string" to "up/down/left/right"),
            "x" to ("number" to "Center X (optional)"),
            "y" to ("number" to "Center Y (optional)")
        )),
        mcpTool("launch_app", "Launch an installed app by package name", mapOf(
            "package" to ("string" to "App package name e.g. com.whatsapp")
        )),
        mcpTool("get_screen_text", "Get all visible text on screen", emptyMap()),
        mcpTool("list_apps", "List all installed apps", emptyMap()),
        mcpTool("click_by_text", "Find and click UI element by its text", mapOf(
            "text" to ("string" to "Text to find and click")
        )),
        mcpTool("pinch", "Pinch in or out gesture", mapOf(
            "direction" to ("string" to "in or out"),
            "x" to ("number" to "Center X (optional)"),
            "y" to ("number" to "Center Y (optional)")
        )),
        mcpTool("device_info", "Get device information and status", emptyMap())
    )

    private fun mcpTool(
        name: String,
        description: String,
        params: Map<String, Pair<String, String>>
    ): Map<String, Any> {
        val properties = params.mapValues { (_, v) ->
            mapOf("type" to v.first, "description" to v.second)
        }
        return mapOf(
            "name" to name,
            "description" to description,
            "inputSchema" to mapOf(
                "type" to "object",
                "properties" to properties,
                "required" to params.filter { it.key != "duration" && it.key != "x" && it.key != "y" }.keys.toList()
            )
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MCP Server",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MCP Controller Active")
            .setContentText("Claude AI can control this device via MCP")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        server?.stop(1000, 3000)
        serviceScope.cancel()
    }
}
