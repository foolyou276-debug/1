package com.mcpcontroller.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MCPAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MCPAccessibilityService? = null
        fun isRunning() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        android.util.Log.d("MCPService", "Accessibility Service Connected ✅")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // ── TAP ──────────────────────────────────────────────────────────────────
    // FIX BUG 6: duration increased 50→100ms for VM compatibility

    suspend fun tap(x: Float, y: Float): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(g: GestureDescription) { if (cont.isActive) cont.resume(false) }
        }, null)
    }

    // ── LONG PRESS ───────────────────────────────────────────────────────────

    suspend fun longPress(x: Float, y: Float): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 1000)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(g: GestureDescription) { if (cont.isActive) cont.resume(false) }
        }, null)
    }

    // ── SWIPE ────────────────────────────────────────────────────────────────

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 300): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(50))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(g: GestureDescription) { if (cont.isActive) cont.resume(false) }
            }, null)
        }

    // ── TYPE TEXT ────────────────────────────────────────────────────────────
    // FIX BUG 10: fallback search for any editable node if none is focused

    fun typeText(text: String): Boolean {
        val node = findFocusedEditText() ?: findAnyEditableNode() ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun appendText(text: String): Boolean {
        val node = findFocusedEditText() ?: findAnyEditableNode() ?: return false
        val current = node.text?.toString() ?: ""
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current + text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pasteText(text: String): Boolean {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("mcp_paste", text))
        val node = findFocusedEditText() ?: findAnyEditableNode() ?: return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    // ── SYSTEM KEYS ──────────────────────────────────────────────────────────

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun pressNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun pressQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    fun pressPowerDialog(): Boolean = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)

    // ── SCROLL ───────────────────────────────────────────────────────────────
    // FIX BUG 7: scroll directions corrected
    // scrollDown = finger moves UP (y decreases) = content moves down ✅
    // scrollUp   = finger moves DOWN (y increases) = content moves up ✅

    suspend fun scrollDown(x: Float, y: Float): Boolean = swipe(x, y, x, y - 600, 400)
    suspend fun scrollUp(x: Float, y: Float): Boolean   = swipe(x, y, x, y + 600, 400)
    suspend fun scrollLeft(x: Float, y: Float): Boolean  = swipe(x, y, x - 600, y, 400)
    suspend fun scrollRight(x: Float, y: Float): Boolean = swipe(x, y, x + 600, y, 400)

    // ── PINCH ────────────────────────────────────────────────────────────────

    suspend fun pinchIn(cx: Float, cy: Float): Boolean = suspendCancellableCoroutine { cont ->
        val path1 = Path().apply { moveTo(cx - 250, cy); lineTo(cx - 30, cy) }
        val path2 = Path().apply { moveTo(cx + 250, cy); lineTo(cx + 30, cy) }
        val s1 = GestureDescription.StrokeDescription(path1, 0, 400)
        val s2 = GestureDescription.StrokeDescription(path2, 0, 400)
        val gesture = GestureDescription.Builder().addStroke(s1).addStroke(s2).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(g: GestureDescription) { if (cont.isActive) cont.resume(false) }
        }, null)
    }

    suspend fun pinchOut(cx: Float, cy: Float): Boolean = suspendCancellableCoroutine { cont ->
        val path1 = Path().apply { moveTo(cx - 30, cy); lineTo(cx - 250, cy) }
        val path2 = Path().apply { moveTo(cx + 30, cy); lineTo(cx + 250, cy) }
        val s1 = GestureDescription.StrokeDescription(path1, 0, 400)
        val s2 = GestureDescription.StrokeDescription(path2, 0, 400)
        val gesture = GestureDescription.Builder().addStroke(s1).addStroke(s2).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(g: GestureDescription) { if (cont.isActive) cont.resume(false) }
        }, null)
    }

    // ── UI TREE HELPERS ──────────────────────────────────────────────────────

    fun getScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        return extractText(root)
    }

    fun findAndClickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByText(root, text) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun findAndClickById(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        if (nodes.isNullOrEmpty()) return false
        return nodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // FIX BUG 4: proper JSON escaping for all string values
    fun getUiHierarchy(): String {
        val root = rootInActiveWindow ?: return "{}"
        return buildHierarchyJson(root, 0)
    }

    // ── PRIVATE HELPERS ──────────────────────────────────────────────────────

    private fun findFocusedEditText(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findFocusedInput(root)
    }

    private fun findAnyEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findFirstEditable(root)
    }

    private fun findFocusedInput(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedInput(child)
            if (result != null) return result
        }
        return null
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstEditable(child)
            if (result != null) return result
        }
        return null
    }

    private fun extractText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        if (!node.text.isNullOrEmpty()) sb.append(node.text).append(" ")
        if (!node.contentDescription.isNullOrEmpty()) sb.append(node.contentDescription).append(" ")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(extractText(child))
        }
        return sb.toString().trim()
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        if (nodeText.contains(text, ignoreCase = true) || contentDesc.contains(text, ignoreCase = true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByText(child, text)
            if (result != null) return result
        }
        return null
    }

    // FIX BUG 4: escape JSON strings properly
    private fun escapeJson(s: CharSequence?): String {
        if (s == null) return ""
        return s.toString()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun buildHierarchyJson(node: AccessibilityNodeInfo, depth: Int): String {
        if (depth > 8) return "{\"truncated\":true}"
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"class\":\"${escapeJson(node.className)}\",")
        sb.append("\"text\":\"${escapeJson(node.text)}\",")
        sb.append("\"desc\":\"${escapeJson(node.contentDescription)}\",")
        sb.append("\"clickable\":${node.isClickable},")
        sb.append("\"editable\":${node.isEditable},")
        sb.append("\"scrollable\":${node.isScrollable},")
        sb.append("\"bounds\":{\"l\":${bounds.left},\"t\":${bounds.top},\"r\":${bounds.right},\"b\":${bounds.bottom}}")
        if (node.childCount > 0) {
            sb.append(",\"children\":[")
            var first = true
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (!first) sb.append(",")
                sb.append(buildHierarchyJson(child, depth + 1))
                first = false
            }
            sb.append("]")
        }
        sb.append("}")
        return sb.toString()
    }
}
