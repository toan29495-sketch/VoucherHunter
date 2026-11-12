package vn.voucherhunter

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class ShopeeVoucherAccessibilityService : AccessibilityService() {
    private var panel: LinearLayout? = null
    private var panelStatus: TextView? = null
    private var lastAttemptAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        inspectShopee()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg == "com.shopee.vn") {
            // Keep the accessibility overlay attached while Shopee changes activities/screens.
            // Some HONOR/Android transitions emit a brief system-window event between Shopee pages;
            // hiding the panel on that event made the panel disappear unexpectedly.
            showPanel()
            inspectShopee()
        }
        // Do not auto-hide on non-Shopee window events. The user can hide it explicitly
        // with the panel's HIDE button; the service lifecycle removes it when disabled.
    }

    private fun inspectShopee() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != "com.shopee.vn") return
        showPanel()
        val prefs = getSharedPreferences("voucher_settings", Context.MODE_PRIVATE)
        val pending = prefs.getBoolean("code_pending", false)
        if (!pending) {
            panelStatus?.text = prefs.getString("automation_status", "Đang theo dõi Shopee")
            verifyResponse(root, prefs)
            return
        }
        if (!prefs.getBoolean("auto_click", false)) {
            panelStatus?.text = "ĐÃ ĐẾN GIỜ\\nBật TỰ ĐỘNG để nhập mã"
            return
        }
        val triggerAt = prefs.getLong("trigger_at", 0L)
        val remaining = triggerAt - System.currentTimeMillis()
        if (remaining > 0) {
            panelStatus?.text = "ĐÃ SẴN SÀNG\nCòn %.3f giây".format(remaining / 1000.0)
            return
        }
        if (SystemClock.elapsedRealtime() - lastAttemptAt < 250) return
        val code = prefs.getString("voucher_code", "").orEmpty()
        if (code.isBlank()) {
            setStatus(prefs, "Thiếu mã voucher — đã dừng")
            return
        }

        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, nodes)
        val edit = nodes.firstOrNull { node ->
            node.isEditable && node.isEnabled &&
                (node.hintText?.toString()?.contains("nhập mã", true) == true ||
                 node.text?.toString()?.contains("nhập mã", true) == true ||
                 node.className?.toString()?.contains("EditText", true) == true)
        }
        val filled = prefs.getBoolean("code_filled", false)
        if (!filled) {
            if (edit == null) {
                panelStatus?.text = "ĐẾN GIỜ NHƯNG CHƯA THẤY Ô NHẬP MÃ"
                return
            }
            lastAttemptAt = SystemClock.elapsedRealtime()
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code)
            }
            val ok = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) {
                prefs.edit().putBoolean("code_filled", true)
                    .putString("automation_status", "Đã điền mã — chờ nút Áp dụng").apply()
                panelStatus?.text = "ĐÃ ĐIỀN MÃ\nĐang tìm nút Áp dụng"
            } else {
                panelStatus?.text = "Không điền được mã — kiểm tra giao diện"
            }
            return
        }

        val apply = nodes.firstOrNull { node ->
            val label = (node.text?.toString().orEmpty() + " " +
                node.contentDescription?.toString().orEmpty()).trim()
            label.equals("Áp dụng", true) && node.isEnabled
        }?.let { clickableAncestor(it) }
        if (apply != null) {
            lastAttemptAt = SystemClock.elapsedRealtime()
            val clicked = apply.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                prefs.edit().putBoolean("code_pending", false)
                    .putString("automation_status", "Đã gửi mã và nhấn Áp dụng — đang chờ Shopee xác nhận")
                    .putLong("submitted_at", System.currentTimeMillis()).apply()
                panelStatus?.text = "ĐÃ NHẤN ÁP DỤNG\nĐang chờ Shopee xác nhận"
            } else {
                panelStatus?.text = "Tìm thấy Áp dụng nhưng chưa bấm được"
            }
        } else {
            panelStatus?.text = "ĐÃ ĐIỀN MÃ\nChưa thấy nút Áp dụng"
        }
    }

    private fun verifyResponse(root: AccessibilityNodeInfo, prefs: android.content.SharedPreferences) {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, nodes)
        val all = nodes.joinToString(" ") { it.text?.toString().orEmpty() }.lowercase()
        when {
            listOf("áp dụng thành công", "đã áp dụng mã", "mã giảm giá đã được áp dụng").any { all.contains(it) } ->
                setStatus(prefs, "Shopee xác nhận mã đã áp dụng")
            listOf("mã không hợp lệ", "mã đã hết hạn", "không đủ điều kiện", "không thể áp dụng").any { all.contains(it) } ->
                setStatus(prefs, "Shopee từ chối mã — kiểm tra điều kiện")
        }
    }

    private fun setStatus(prefs: android.content.SharedPreferences, value: String) {
        prefs.edit().putString("automation_status", value).apply()
        panelStatus?.text = value
    }

    private fun showPanel() {
        if (panel != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences("voucher_settings", Context.MODE_PRIVATE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(25, 35, 48))
        }
        val heading = TextView(this).apply {
            text = "☰  GIỮ THANH NÀY ĐỂ KÉO BẢNG"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(52)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(35, 105, 160))
            isClickable = true
        }
        val status = TextView(this).apply {
            text = prefs.getString("automation_status", "Đang theo dõi Shopee")
            setTextColor(Color.WHITE); textSize = 11f; setPadding(0, dp(5), 0, dp(5))
        }
        panelStatus = status
        val toggle = Button(this).apply {
            textSize = 10f
            text = if (prefs.getBoolean("auto_click", false)) "TỰ ĐỘNG: BẬT" else "TỰ ĐỘNG: TẮT"
            setOnClickListener {
                val next = !prefs.getBoolean("auto_click", false)
                prefs.edit().putBoolean("auto_click", next).apply()
                text = if (next) "TỰ ĐỘNG: BẬT" else "TỰ ĐỘNG: TẮT"
                status.text = if (next) "Đã bật thao tác tự động" else "Đã tắt thao tác tự động"
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("panel_x", dp(12)).coerceAtLeast(0)
            y = prefs.getInt("panel_y", dp(100)).coerceAtLeast(0)
        }
        heading.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    view.setTag(android.R.id.content, floatArrayOf(
                        e.rawX, e.rawY, params.x.toFloat(), params.y.toFloat()
                    ))
                    heading.text = "↕  ĐANG GIỮ — KÉO ĐỂ DI CHUYỂN"
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val origin = view.getTag(android.R.id.content) as? FloatArray
                    if (origin != null) {
                        val display = resources.displayMetrics
                        val maxX = (display.widthPixels - box.width).coerceAtLeast(0)
                        val maxY = (display.heightPixels - box.height).coerceAtLeast(0)
                        params.x = (origin[2] + e.rawX - origin[0]).toInt().coerceIn(0, maxX)
                        params.y = (origin[3] + e.rawY - origin[1]).toInt().coerceIn(0, maxY)
                        heading.text = "ĐANG KÉO • X:" + params.x + " Y:" + params.y
                        try { wm.updateViewLayout(box, params) } catch (_: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit().putInt("panel_x", params.x).putInt("panel_y", params.y).apply()
                    heading.text = "☰  GIỮ THANH NÀY ĐỂ KÉO BẢNG"
                    panelStatus?.text = "Đã lưu vị trí bảng: X=" + params.x + ", Y=" + params.y
                    true
                }
                else -> true
            }
        }
        try { wm.addView(box, params); panel = box } catch (_: Exception) { panel = null; panelStatus = null }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun collectNodes(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        out.add(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectNodes(it, out) }
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
        panel = null; panelStatus = null
    }

    override fun onInterrupt() { panelStatus?.text = "Trợ năng bị gián đoạn" }
    override fun onDestroy() { hidePanel(); super.onDestroy() }
}
