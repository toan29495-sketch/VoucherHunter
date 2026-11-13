package vn.voucherhunter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FloatingClockService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var window: LinearLayout? = null
    private var clockText: TextView? = null
    private var countText: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            clockText?.text = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(now))
            val target = getSharedPreferences("voucher_settings", Context.MODE_PRIVATE).getLong("trigger_at", 0L)
            countText?.text = if (target > 0L) {
                val left = target - now
                if (left >= 0) "CÒN " + String.format(Locale.US, "%02d:%02d:%02d.%03d",
                    left / 3_600_000, (left / 60_000) % 60, (left / 1_000) % 60, left % 1_000)
                else "ĐÃ ĐẾN GIỜ • +" + (-left) + " ms"
            } else "CHƯA ĐẶT MỐC GIỜ"
            handler.postDelayed(this, 10L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, "voucher_clock").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Voucher Hunter đang chạy")
                .setContentText("Đồng hồ nổi đang hoạt động trên Shopee")
                .setOngoing(true).build()
        else Notification.Builder(this).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Voucher Hunter đang chạy").setOngoing(true).build()
        startForeground(41, notification)
        showClock()
        handler.post(tick)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel("voucher_clock", "Voucher Hunter", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun showClock() {
        if (window != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(20, 35, 52))
        }
        val grip = TextView(this).apply {
            text = "☰ VOUCHER HUNTER • GIỮ ĐỂ KÉO"
            setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.rgb(28, 105, 160))
        }
        val clock = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(2))
        }
        val count = TextView(this).apply {
            setTextColor(Color.YELLOW); textSize = 14f; gravity = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(8), dp(6))
        }
        clockText = clock; countText = count
        val hide = Button(this).apply {
            text = "ẨN ĐỒNG HỒ"; textSize = 10f
            setOnClickListener { stopSelf() }
        }
        box.addView(grip); box.addView(clock); box.addView(count); box.addView(hide)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(100) }
        grip.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.setTag(android.R.id.content, floatArrayOf(event.rawX, event.rawY, lp.x.toFloat(), lp.y.toFloat()))
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val origin = v.getTag(android.R.id.content) as? FloatArray
                    if (origin != null) {
                        lp.x = (origin[2] + event.rawX - origin[0]).toInt().coerceAtLeast(0)
                        lp.y = (origin[3] + event.rawY - origin[1]).toInt().coerceAtLeast(0)
                        try { wm.updateViewLayout(box, lp) } catch (_: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
        try { wm.addView(box, lp); window = box; params = lp }
        catch (_: Exception) { stopSelf() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        window?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) { } }
        window = null; clockText = null; countText = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
