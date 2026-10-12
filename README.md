# 🤖 Android MCP Controller

**Claude AI se apna Android VM 100% control karo — ek custom MCP server app ke through!**

---

## 📋 Features

| Feature | Method | Status |
|---------|--------|--------|
| Screenshot | MediaProjection | ✅ |
| Tap anywhere | AccessibilityService | ✅ |
| Swipe/Scroll | AccessibilityService | ✅ |
| Long Press | AccessibilityService | ✅ |
| Type text | AccessibilityService | ✅ |
| Press keys (back/home) | AccessibilityService | ✅ |
| Launch apps | PackageManager | ✅ |
| List apps | PackageManager | ✅ |
| Click by text | AccessibilityService | ✅ |
| Pinch in/out | AccessibilityService | ✅ |
| Get screen text | AccessibilityService | ✅ |
| UI hierarchy | AccessibilityService | ✅ |
| MCP Protocol | Ktor HTTP Server | ✅ |

---

## 🔧 Requirements

- Android 11+ (API 30+)
- Android Studio Flamingo or newer
- JDK 17

---

## 📦 Build karo

### Option 1: Android Studio
1. Android Studio mein project open karo
2. Gradle sync hone do
3. `Build → Build APK(s)` click karo
4. APK milega: `app/build/outputs/apk/debug/app-debug.apk`

### Option 2: Command Line
```bash
./gradlew assembleDebug
```

---

## 📱 Setup karo (Android VM mein)

### Step 1: APK Install
```bash
adb install app-debug.apk
```

### Step 2: App open karo
- "MCP Controller" app launch karo

### Step 3: Accessibility Service enable karo
1. "Enable Accessibility Service" button dabao
2. Settings khulega → Installed Services → MCP Controller → ON karo

### Step 4: Screen Capture grant karo
1. "Grant Screen Capture" button dabao
2. Popup aayega → "Start Now" dabao

### Step 5: Server start karo
1. "▶ Start MCP Server" dabao
2. Server URL dikhega: `http://[VM_IP]:8080`

---

## 🔌 Claude se Connect karo

### Claude.ai Custom Connector:
1. Claude.ai → Settings → Connectors
2. "Add Custom Connector" → MCP type
3. URL: `http://[VM_IP]:8080/mcp`

### Test karo:
```bash
# Health check
curl http://[VM_IP]:8080/health

# Screenshot lo
curl http://[VM_IP]:8080/screenshot

# Tap karo
curl -X POST http://[VM_IP]:8080/tap \
  -H "Content-Type: application/json" \
  -d '{"x": 540, "y": 960}'
```

---

## 🌐 MCP Tools (Claude use karega)

| Tool | Description |
|------|-------------|
| `take_screenshot` | Screen ka screenshot lo |
| `tap_screen` | Coordinates par tap karo |
| `swipe_screen` | Swipe gesture karo |
| `long_press` | Long press karo |
| `type_text` | Text type karo (input field mein) |
| `press_key` | back/home/recents/notifications |
| `scroll` | up/down/left/right scroll |
| `launch_app` | Package name se app launch karo |
| `get_screen_text` | Screen ka saara text lo |
| `list_apps` | Installed apps ki list |
| `click_by_text` | Text dhundo aur click karo |
| `pinch` | Pinch in/out gesture |
| `device_info` | Device ki info lo |

---

## 📡 REST API Endpoints

```
GET  /health          → Server status
GET  /screenshot      → Screenshot (base64 JPEG)
POST /tap             → {"x": 100, "y": 200}
POST /swipe           → {"x1":100,"y1":200,"x2":100,"y2":800}
POST /longpress       → {"x": 100, "y": 200}
POST /type            → {"text": "hello world"}
POST /keyevent        → {"key": "back"}
POST /scroll          → {"direction": "down", "x": 540, "y": 960}
POST /launch          → {"package": "com.whatsapp"}
GET  /apps            → List all installed apps
GET  /screentext      → Get all text on screen
GET  /uihierarchy     → Full UI tree (JSON)
POST /clicktext       → {"text": "Settings"}
POST /pinch           → {"direction": "in", "x": 540, "y": 960}
POST /mcp             → MCP JSON-RPC endpoint
```

---

## 🎮 Example: Claude se game khelwao

Claude ko bolo:
> "Android VM mein Subway Surfers khelo"

Claude karega:
1. `take_screenshot` → screen dekha
2. `launch_app` {"package": "subway.surfers"} → game launch
3. `take_screenshot` → game state dekha
4. `swipe_screen` → left/right swipe
5. Loop chalata rahega!

---

## 🔒 Security Note

- Yeh app sirf aapke VM/local network par kaam karta hai
- Kisi aur ke device par kaam nahi karta
- Production use mein auth token add karo (optional)

---

## 📂 Project Structure

```
AndroidMCPController/
├── app/src/main/
│   ├── java/com/mcpcontroller/
│   │   ├── MainActivity.kt           ← UI + permissions
│   │   ├── accessibility/
│   │   │   └── MCPAccessibilityService.kt  ← tap/swipe/type
│   │   ├── media/
│   │   │   └── ScreenCaptureService.kt     ← screenshots
│   │   ├── server/
│   │   │   └── MCPServerService.kt         ← HTTP + MCP server
│   │   └── utils/
│   │       ├── NetworkUtils.kt
│   │       └── BootReceiver.kt
│   ├── res/
│   │   ├── layout/activity_main.xml
│   │   └── xml/accessibility_service_config.xml
│   └── AndroidManifest.xml
└── README.md
```

---

## ⚡ Quick ngrok setup (public URL ke liye)

```bash
# ngrok install karo
# Phir:
ngrok http 8080

# Claude ko do yeh URL:
# https://xxxx.ngrok.io/mcp
```
