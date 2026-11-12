package vn.voucherhunter

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class ShopeeVoucherAccessibilityService : AccessibilityService() {
    private var panel: LinearLayout? = null
    private var panelStatus: TextView? = null
    private var lastActionAt = 0L
    private val labels = listOf("lưu voucher", "thu thập", "nhận voucher", "lấy voucher", "collect", "claim")

    override fun onServiceConnected() {
        super.onServiceConnected()
        updateFromActiveWindow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage == "com.shopee.vn") {
            showPanel()
            inspectShopee()
            return
        }
        // Shopee frequently emits focus/content events through system surfaces.
        // Do not remove/recreate the overlay for every non-Shopee event: that caused flicker.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val activePackage = rootInActiveWindow?.packageName?.toString()
            if (activePackage != null && activePackage != "com.shopee.vn") hidePanel()
        }
    }

    private fun updateFromActiveWindow() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() == "com.shopee.vn") {
            showPanel()
            inspectShopee()
        }
    }

    private fun inspectShopee() {
        val root = rootInActiveWindow ?: run {
            panelStatus?.text = "Shopee đang mở\nChờ nội dung hiển thị…"
            return
        }
        if (root.packageName?.toString() != "com.shopee.vn") return
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectTextNodes(root, candidates)
        val match = candidates.firstNotNullOfOrNull { textNode ->
            val label = ((textNode.text?.toString() ?: "") + " " +
                (textNode.contentDescription?.toString() ?: "")).lowercase()
            if (labels.none { label.contains(it) }) null
            else clickableAncestor(textNode)
        }
        if (match == null) {
            panelStatus?.text = "ĐANG THEO DÕI SHOPEE\nChưa thấy nút lấy/thu thập voucher"
            return
        }
        val now = SystemClock.elapsedRealtime()
        val label = match.text?.toString() ?: match.contentDescription?.toString() ?: "Nút voucher"
        panelStatus?.text = "ĐÃ NHẬN DIỆN\n$label"
        if (getSharedPreferences("voucher_settings", Context.MODE_PRIVATE).getBoolean("auto_click", false)
            && now - lastActionAt >= 1500) {
            lastActionAt = now
            val clicked = match.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            panelStatus?.text = if (clicked) "ĐÃ GỬI LỆNH BẤM\nKiểm tra trạng thái voucher"
                else "ĐÃ THẤY NÚT NHƯNG SHOPEE TỪ CHỐI BẤM"
            getSharedPreferences("voucher_settings", Context.MODE_PRIVATE).edit()
                .putString("last_detection", if (clicked) "Đã gửi thao tác: $label" else "Không bấm được: $label")
                .apply()
        }
    }

    private fun showPanel() {
        if (panel != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(25, 35, 48))
        }
        val heading = TextView(this).apply {
            text = "VOUCHER HUNTER • LIVE"
            setTextColor(Color.WHITE); textSize = 12f; setTypeface(null, Typeface.BOLD)
        }
        val status = TextView(this).apply {
            text = "Đang kết nối với Shopee…"
            setTextColor(Color.WHITE); textSize = 11f; setPadding(0, dp(5), 0, dp(5))
        }
        panelStatus = status
        val prefs = getSharedPreferences("voucher_settings", Context.MODE_PRIVATE)
        val toggle = Button(this).apply {
            textSize = 10f
            text = if (prefs.getBoolean("auto_click", false)) "TỰ BẤM: BẬT" else "TỰ BẤM: TẮT"
            setOnClickListener {
                val next = !prefs.getBoolean("auto_click", false)
                prefs.edit().putBoolean("auto_click", next).apply()
                text = if (next) "TỰ BẤM: BẬT" else "TỰ BẤM: TẮT"
                status.text = if (next) "Đã bật tự bấm\nChỉ dùng khi đã kiểm tra nhận diện"
                    else "Chế độ theo dõi, không tự bấm"
            }
        }
        val close = Button(this).apply {
            text = "ẨN BẢNG"; textSize = 10f
            setOnClickListener { hidePanel() }
        }
        box.addView(heading); box.addView(status); box.addView(toggle); box.addView(close)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8); y = dp(100)
        }
        try {
            wm.addView(box, params)
            panel = box
        } catch (_: Exception) {
            panel = null
            panelStatus = null
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun collectTextNodes(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) out.add(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectTextNodes(it, out) }
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            if (current == null) return null
            if (current!!.isClickable && current!!.isEnabled) return current
            current = current!!.parent
        }
        return null
    }

    private fun hidePanel() {
        val old = panel ?: return
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(old) } catch (_: Exception) { }
        panel = null
        panelStatus = null
    }

    override fun onInterrupt() {
        panelStatus?.text = "Trợ năng bị gián đoạn"
    }

    override fun onDestroy() {
        hidePanel()
        super.onDestroy()
    }
}
