package vn.voucherhunter

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class ShopeeVoucherAccessibilityService : AccessibilityService() {
    private var lastActionAt = 0L
    private var panel: LinearLayout? = null
    private var panelStatus: TextView? = null
    private val labels = listOf("lưu voucher", "thu thập", "nhận voucher", "lấy voucher", "collect", "claim")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != "com.shopee.vn") { hidePanel(); return }
        showPanel()
        val root = rootInActiveWindow ?: return
        val prefs = getSharedPreferences("voucher_settings", Context.MODE_PRIVATE)
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectClickable(root, nodes)
        val target = nodes.firstOrNull { node ->
            val label = ((node.text?.toString() ?: "") + " " +
                (node.contentDescription?.toString() ?: "")).lowercase()
            labels.any { label.contains(it) } && node.isEnabled && node.isClickable
        }
        if (target == null) {
            panelStatus?.text = "Đang theo dõi Shopee…\nChưa thấy nút thu thập"
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastActionAt < 1000) return
        lastActionAt = now
        val label = target.text?.toString() ?: target.contentDescription?.toString() ?: "Nút voucher"
        prefs.edit().putString("last_detection", "Phát hiện: $label").apply()
        panelStatus?.text = "ĐÃ THẤY NÚT\n$label"
        if (prefs.getBoolean("auto_click", false)) {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            panelStatus?.text = "ĐÃ GỬI LỆNH BẤM\nKiểm tra kết quả Shopee"
            prefs.edit().putString("last_detection", "Đã gửi thao tác: $label; chờ xác minh").apply()
        }
    }

    private fun showPanel() {
        if (panel != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 14)
            setBackgroundColor(Color.rgb(25, 35, 48))
        }
        val heading = TextView(this).apply {
            text = "VOUCHER HUNTER • LIVE"
            setTextColor(Color.WHITE); textSize = 13f; setTypeface(null, Typeface.BOLD)
        }
        val status = TextView(this).apply {
            text = "Đang theo dõi Shopee…"
            setTextColor(Color.WHITE); textSize = 12f; setPadding(0, 8, 0, 8)
        }
        panelStatus = status
        val prefs = getSharedPreferences("voucher_settings", Context.MODE_PRIVATE)
        val toggle = Button(this).apply {
            text = if (prefs.getBoolean("auto_click", false)) "TỰ BẤM: BẬT" else "TỰ BẤM: TẮT"
            setOnClickListener {
                val next = !prefs.getBoolean("auto_click", false)
                prefs.edit().putBoolean("auto_click", next).apply()
                text = if (next) "TỰ BẤM: BẬT" else "TỰ BẤM: TẮT"
                status.text = if (next) "Đã bật hỗ trợ tự bấm\nHãy xác minh kết quả" else "Chế độ phát hiện, không tự bấm"
            }
        }
        val close = Button(this).apply { text = "ẨN BẢNG"; setOnClickListener { hidePanel() } }
        box.addView(heading); box.addView(status); box.addView(toggle); box.addView(close)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 12; y = 110 }
        try { wm.addView(box, params); panel = box } catch (_: Exception) { panel = null }
    }

    private fun hidePanel() {
        val old = panel ?: return
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(old) } catch (_: Exception) { }
        panel = null; panelStatus = null
    }

    private fun collectClickable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.isClickable) out.add(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectClickable(it, out) }
    }

    override fun onInterrupt() { hidePanel() }
    override fun onDestroy() { hidePanel(); super.onDestroy() }
}
