package me.rerere.rikkahub.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.GestureDescription

/** User-enabled bridge; it performs no actions without an explicit request. */
class RikkaAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun clickText(text: String): Boolean = rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 300): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), null, null)
    }
}
