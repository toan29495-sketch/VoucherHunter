package vn.voucherhunter

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ShopeeVoucherAccessibilityService : AccessibilityService() {
    private var lastActionAt = 0L
    private val labels = listOf("lưu voucher", "thu thập", "nhận voucher", "lấy voucher", "collect", "claim")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() != "com.shopee.vn") return
        val root = rootInActiveWindow ?: return
        val prefs = getSharedPreferences("voucher_settings", Context.MODE_PRIVATE)
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectClickable(root, nodes)
        val target = nodes.firstOrNull { node ->
            val label = ((node.text?.toString() ?: "") + " " +
                (node.contentDescription?.toString() ?: "")).lowercase()
            labels.any { label.contains(it) } && node.isEnabled && node.isClickable
        } ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastActionAt < 1500) return
        lastActionAt = now
        prefs.edit().putString("last_detection", "Phát hiện nút voucher: " +
            (target.text?.toString() ?: target.contentDescription?.toString().orEmpty())).apply()
        if (prefs.getBoolean("auto_click", false)) {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            prefs.edit().putString("last_detection", "Đã gửi thao tác bấm; hãy xác minh kết quả trong Shopee").apply()
        }
    }

    private fun collectClickable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.isClickable) out.add(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectClickable(it, out) }
    }

    override fun onInterrupt() = Unit
}
