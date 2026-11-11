package vn.voucherhunter

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { VoucherHunterScreen() } }
    }
}

private fun nextOccurrence(time: String, now: Long): Long? {
    val parts = time.split(":").mapNotNull { it.toIntOrNull() }
    if (parts.size != 3 || parts[0] !in 0..23 || parts[1] !in 0..59 || parts[2] !in 0..59) return null
    val cal = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, parts[0])
        set(Calendar.MINUTE, parts[1])
        set(Calendar.SECOND, parts[2])
        set(Calendar.MILLISECOND, 0)
    }
    if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
    return cal.timeInMillis
}

private fun durationText(ms: Long): String {
    val sec = (ms.coerceAtLeast(0) + 999) / 1000
    return "%02d:%02d:%02d".format(Locale.US, sec / 3600, (sec / 60) % 60, sec % 60)
}

private fun alertVibration(context: Context) {
    try {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        }
        v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (_: Exception) { }
}

@Composable
fun VoucherHunterScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("voucher_settings", Context.MODE_PRIVATE) }
    var amount by remember { mutableStateOf(prefs.getString("amount", "11000000") ?: "11000000") }
    var minimum by remember { mutableStateOf(prefs.getString("minimum", "5000000") ?: "5000000") }
    var targetTime by remember { mutableStateOf(prefs.getString("time", "00:00:00") ?: "00:00:00") }
    var voucherLink by remember { mutableStateOf(prefs.getString("link", "") ?: "") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var armed by remember { mutableStateOf(false) }
    var targetAt by remember { mutableLongStateOf(0L) }
    var fired60 by remember { mutableStateOf(false) }
    var fired10 by remember { mutableStateOf(false) }
    var fired0 by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Chưa kích hoạt đếm ngược") }
    var log by remember { mutableStateOf("Chưa có sự kiện") }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(100)
        }
    }

    LaunchedEffect(now, armed, targetAt) {
        if (armed && targetAt > 0) {
            val remaining = targetAt - now
            if (!fired60 && remaining <= 60000 && remaining > 0) {
                fired60 = true
                alertVibration(context)
                status = "Còn dưới 60 giây"
                log = "Đã rung cảnh báo mốc 60 giây"
            }
            if (!fired10 && remaining <= 10000 && remaining > 0) {
                fired10 = true
                alertVibration(context)
                status = "Còn dưới 10 giây — chuẩn bị nhận voucher"
                log = "Đã rung cảnh báo mốc 10 giây"
            }
            if (!fired0 && remaining <= 0) {
                fired0 = true
                alertVibration(context)
                status = "ĐẾN GIỜ! Hãy nhận voucher trong Shopee"
                log = "Đã rung tại thời điểm mục tiêu"
                armed = false
            }
        }
    }

    fun openShopee() {
        try {
            val link = voucherLink.trim()
            val intent = if (link.startsWith("https://") && (Uri.parse(link).host == "shopee.vn" ||
                    Uri.parse(link).host?.endsWith(".shopee.vn") == true ||
                    Uri.parse(link).host == "vn.shp.ee")) {
                Intent(Intent.ACTION_VIEW, Uri.parse(link))
            } else {
                context.packageManager.getLaunchIntentForPackage("com.shopee.vn")
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shopee.vn/"))
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            status = "Không mở được Shopee. Kiểm tra đường dẫn hoặc ứng dụng."
        }
    }

    val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale("vi", "VN")).format(Date(now))
    val countdown = if (armed) durationText(targetAt - now) else "--:--:--"

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("VOUCHER HUNTER 1.1", fontSize = 23.sp, fontWeight = FontWeight.Black)
            Text("Trợ lý canh thời điểm săn voucher", style = MaterialTheme.typography.bodyMedium)
            Card(shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("GIỜ ĐIỆN THOẠI")
                    Text(clock, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("ĐẾM NGƯỢC", style = MaterialTheme.typography.labelLarge)
                    Text(countdown, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                    Text("Giữ ứng dụng mở để nhận cảnh báo", style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(amount, { amount = it.filter(Char::isDigit) },
                label = { Text("Mức giảm mục tiêu (đ)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(minimum, { minimum = it.filter(Char::isDigit) },
                label = { Text("Đơn tối thiểu (đ)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(targetTime, { targetTime = it },
                label = { Text("Giờ mở voucher (HH:mm:ss)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(voucherLink, { voucherLink = it },
                label = { Text("Link trang voucher Shopee (nếu có)") },
                modifier = Modifier.fillMaxWidth(), minLines = 2)
            Button(onClick = {
                val t = nextOccurrence(targetTime, System.currentTimeMillis())
                if (t == null) {
                    status = "Giờ không hợp lệ. Nhập HH:mm:ss, ví dụ 00:00:00"
                } else {
                    prefs.edit().putString("amount", amount).putString("minimum", minimum)
                        .putString("time", targetTime).putString("link", voucherLink).apply()
                    targetAt = t
                    armed = true
                    fired60 = false
                    fired10 = false
                    fired0 = false
                    status = "Đang canh giờ. Cảnh báo tại 60s, 10s và 0s."
                    log = "Đã lưu cấu hình và bật đếm ngược"
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("LƯU VÀ BẬT CANH GIỜ") }
            OutlinedButton(onClick = {
                armed = false
                status = "Đã dừng canh giờ"
            }, modifier = Modifier.fillMaxWidth()) { Text("Dừng canh giờ") }
            OutlinedButton(onClick = { openShopee() }, modifier = Modifier.fillMaxWidth()) {
                Text("MỞ SHOPEE / TRANG VOUCHER")
            }
            OutlinedButton(onClick = {
                alertVibration(context)
                status = "Đã thử rung"
            }, modifier = Modifier.fillMaxWidth()) { Text("Thử rung cảnh báo") }
            Text("TRẠNG THÁI", fontWeight = FontWeight.Bold)
            Text(status)
            Text("NHẬT KÝ", fontWeight = FontWeight.Bold)
            Text(log)
            Text("Cảnh báo chỉ chạy khi ứng dụng đang mở; không tự nhận voucher, không tự áp mã hay thanh toán. Đồng hồ dùng giờ điện thoại, không phải giờ máy chủ Shopee.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
