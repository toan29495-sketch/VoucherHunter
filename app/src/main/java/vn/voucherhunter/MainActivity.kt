package vn.voucherhunter

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
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
    val match = Regex("^(\\d{1,2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,3}))?)?$").matchEntire(time.trim()) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    val second = match.groupValues[3].ifBlank { "0" }.toIntOrNull() ?: return null
    val fraction = match.groupValues[4]
    val milli = fraction.padEnd(3, '0').ifBlank { "0" }.toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59 || second !in 0..59 || milli !in 0..999) return null
    val cal = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, second); set(Calendar.MILLISECOND, milli)
    }
    if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
    return cal.timeInMillis
}

private fun durationText(ms: Long): String {
    val value = ms.coerceAtLeast(0)
    val hours = value / 3_600_000
    val minutes = (value / 60_000) % 60
    val seconds = (value / 1_000) % 60
    val millis = value % 1_000
    return "%02d:%02d:%02d.%03d".format(Locale.US, hours, minutes, seconds, millis)
}

private fun alertVibration(context: Context) {
    try {
        val v = if (Build.VERSION.SDK_INT >= 31)
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        }
        v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (_: Exception) { }
}

private fun parseVoucherText(raw: String): JSONObject {
    val text = raw.replace("\\u00a0", " ").replace(",", ".")
    fun findAmount(pattern: Regex): String {
        val m = pattern.find(text) ?: return ""
        val digits = m.groupValues.last().replace(Regex("[^0-9]"), "")
        return digits
    }
    val discount = findAmount(Regex("(?i)(?:giảm|giảm giá|giảm đến|ưu đãi)[^0-9]{0,25}([0-9][0-9. ]{2,})"))
    val minimum = findAmount(Regex("(?i)(?:đơn tối thiểu|đơn từ|tối thiểu|minimum spend)[^0-9]{0,25}([0-9][0-9. ]{2,})"))
    val time = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)(?::([0-5]\\d))?\\b").find(text)?.value ?: ""
    val name = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }?.take(90) ?: "Voucher từ nội dung"
    return JSONObject()
        .put("name", name)
        .put("amount", discount)
        .put("minimum", minimum)
        .put("time", time)
        .put("rawText", raw)
        .put("state", "Đã phân tích — cần xác minh trên Shopee")
}

@Composable
fun VoucherHunterScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("voucher_settings", Context.MODE_PRIVATE) }
    var amount by remember { mutableStateOf(prefs.getString("amount", "11000000") ?: "11000000") }
    var minimum by remember { mutableStateOf(prefs.getString("minimum", "5000000") ?: "5000000") }
    var targetTime by remember { mutableStateOf(prefs.getString("time", "00:00:00.000") ?: "00:00:00.000") }
    
    var voucherName by remember { mutableStateOf("Voucher SPayLater") }
    var rawText by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var armed by remember { mutableStateOf(false) }
    var targetAt by remember { mutableLongStateOf(0L) }
    var fired60 by remember { mutableStateOf(false) }
    var fired10 by remember { mutableStateOf(false) }
    var fired0 by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Sẵn sàng nhận ảnh hoặc văn bản voucher") }
    var log by remember { mutableStateOf("Chưa có sự kiện") }
    var busy by remember { mutableStateOf(false) }
    var autoClick by remember { mutableStateOf(prefs.getBoolean("auto_click", false)) }
    var savedVouchers by remember {
        mutableStateOf(run {
            val arr = JSONArray(prefs.getString("voucher_list", "[]") ?: "[]")
            (0 until arr.length()).map { arr.getJSONObject(it) }
        })
    }

    fun persist(items: List<JSONObject>) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        prefs.edit().putString("voucher_list", arr.toString()).apply()
        savedVouchers = items
    }

    fun analyze(raw: String, source: String) {
        val parsed = parseVoucherText(raw)
        if (parsed.optString("amount").isNotBlank()) amount = parsed.optString("amount")
        if (parsed.optString("minimum").isNotBlank()) minimum = parsed.optString("minimum")
        if (parsed.optString("time").isNotBlank()) targetTime = parsed.optString("time")
        voucherName = parsed.optString("name").ifBlank { "Voucher từ $source" }
        rawText = raw
        status = "Đã đọc nội dung từ $source. Hãy rà soát các trường nhận diện."
        log = "Phân tích $source lúc " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            busy = true
            status = "Đang đọc chữ trong ảnh…"
            try {
                val image = InputImage.fromFilePath(context, uri)
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
                    .addOnSuccessListener { result ->
                        analyze(result.text, "ảnh")
                        busy = false
                        if (result.text.isBlank()) status = "Không nhận diện được chữ. Hãy thử ảnh rõ và đủ sáng hơn."
                    }
                    .addOnFailureListener { e ->
                        busy = false
                        status = "Không đọc được ảnh: ${e.localizedMessage ?: "lỗi OCR"}"
                    }
            } catch (e: Exception) {
                busy = false
                status = "Không mở được ảnh: ${e.localizedMessage ?: "lỗi"}"
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(10) }
    }

    LaunchedEffect(now, armed, targetAt) {
        if (armed && targetAt > 0) {
            val remaining = targetAt - now
            if (!fired60 && remaining <= 60000 && remaining > 0) {
                fired60 = true; alertVibration(context); status = "Còn dưới 60 giây"; log = "Cảnh báo 60 giây"
            }
            if (!fired10 && remaining <= 10000 && remaining > 0) {
                fired10 = true; alertVibration(context); status = "Còn dưới 10 giây — chuẩn bị"; log = "Cảnh báo 10 giây"
            }
            if (!fired0 && remaining <= 0) {
                fired0 = true; alertVibration(context); status = "ĐẾN GIỜ SĂN VOUCHER"; log = "Đã đến giờ mục tiêu"; armed = false
            }
        }
    }

    fun openShopee() {
        try {
            val launch = context.packageManager.getLaunchIntentForPackage("com.shopee.vn")
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                status = "Đã mở Shopee. Tìm voucher theo tên và điều kiện đã phân tích."
            } else status = "Chưa tìm thấy ứng dụng Shopee trên thiết bị."
        } catch (_: Exception) { status = "Không mở được Shopee." }
    }

    val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale("vi", "VN")).format(Date(now))
    val countdown = if (armed) durationText(targetAt - now) else if (fired0 && targetAt > 0) "00:00:00.000" else "--:--:--.---"

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("VOUCHER HUNTER 1.4", fontSize = 23.sp, fontWeight = FontWeight.Black)
            Text("Tự đọc voucher từ ảnh / văn bản · quản lý mục tiêu · canh giờ")
            Card(shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("GIỜ ĐIỆN THOẠI")
                    Text(clock, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("ĐẾM NGƯỢC", style = MaterialTheme.typography.labelLarge)
                    Text(countdown, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                }
            }

            Text("1. ĐẦU VÀO VOUCHER", fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "ĐANG ĐỌC ẢNH…" else "CHỌN ẢNH / ẢNH CHỤP MÀN HÌNH")
            }
            OutlinedTextField(rawText, { rawText = it }, label = { Text("Dán nội dung / mô tả voucher") },
                modifier = Modifier.fillMaxWidth(), minLines = 4)
            Button(onClick = { if (rawText.isBlank()) status = "Hãy chọn ảnh hoặc dán nội dung trước."
                else analyze(rawText, "văn bản") }, modifier = Modifier.fillMaxWidth()) { Text("TỰ PHÂN TÍCH VĂN BẢN") }

            Text("2. THÔNG TIN ĐÃ NHẬN DIỆN (có thể sửa)", fontWeight = FontWeight.Bold)
            OutlinedTextField(voucherName, { voucherName = it }, label = { Text("Tên / mô tả voucher") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("Mức giảm (đ)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(minimum, { minimum = it.filter(Char::isDigit) }, label = { Text("Đơn tối thiểu (đ)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(targetTime, { targetTime = it }, label = { Text("Giờ mở voucher (HH:mm:ss.SSS)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = {
                val item = JSONObject().put("name", voucherName).put("amount", amount)
                    .put("minimum", minimum).put("time", targetTime).put("rawText", rawText)
                    .put("state", "Đã lưu — chờ kiểm tra")
                persist(savedVouchers + item)
                status = "Đã lưu mục tiêu voucher"; log = "Đã thêm: $voucherName"
            }, modifier = Modifier.fillMaxWidth()) { Text("LƯU MỤC TIÊU VOUCHER") }

            Text("3. CANH GIỜ CHÍNH XÁC", fontWeight = FontWeight.Bold)
            Text("Nhập giờ đến mili giây. Khi đồng hồ chạm mốc, app rung và giữ thông báo để bạn tự nhấn trong Shopee.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                val t = nextOccurrence(targetTime, System.currentTimeMillis())
                if (t == null) status = "Giờ không hợp lệ. Ví dụ 09:30:15.250"
                else {
                    prefs.edit().putString("amount", amount).putString("minimum", minimum)
                        .putString("time", targetTime).putLong("trigger_at", t)
                        .putBoolean("code_pending", false).apply()
                    targetAt = t; armed = true; fired60 = false; fired10 = false; fired0 = false
                    status = "Đã bật đồng hồ mili giây. Hãy mở Shopee và tự nhấn đúng thời điểm."
                    log = "Đang canh mốc " + targetTime
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("BẮT ĐẦU CANH GIỜ") }
            OutlinedButton(onClick = {
                armed = false
                status = "Đã dừng đồng hồ"
                log = "Đã hủy mốc canh giờ"
            }, modifier = Modifier.fillMaxWidth()) { Text("DỪNG CANH GIỜ") }
            OutlinedButton(onClick = { openShopee() }, modifier = Modifier.fillMaxWidth()) {
                Text("MỞ SHOPEE ĐỂ TỰ NHẤN")
            }

            Text("5. DANH SÁCH MỤC TIÊU", fontWeight = FontWeight.Bold)
            if (savedVouchers.isEmpty()) Text("Chưa có mục tiêu nào.")
            savedVouchers.forEachIndexed { index, item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.optString("name"), fontWeight = FontWeight.Bold)
                        Text("Giảm: ${item.optString("amount").ifBlank { "Chưa nhận diện" }}đ · Đơn tối thiểu: ${item.optString("minimum").ifBlank { "Chưa nhận diện" }}đ")
                        Text("Giờ: ${item.optString("time").ifBlank { "Chưa rõ" }} · ${item.optString("state")}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                voucherName = item.optString("name"); amount = item.optString("amount")
                                minimum = item.optString("minimum"); targetTime = item.optString("time")
                                rawText = item.optString("rawText"); status = "Đã nạp lại mục tiêu"
                            }) { Text("Nạp") }
                            TextButton(onClick = {
                                persist(savedVouchers.filterIndexed { i, _ -> i != index })
                                status = "Đã xóa mục tiêu"
                            }) { Text("Xóa") }
                        }
                    }
                }
            }
            Text("TRẠNG THÁI", fontWeight = FontWeight.Bold)
            Text(status)
            Text("NHẬT KÝ", fontWeight = FontWeight.Bold)
            Text(log)
            Text("Đồng hồ hiển thị và cập nhật mỗi 10ms khi ứng dụng đang mở. Mốc canh giờ được lưu đến mili giây; Android có thể trễ vài mili giây do lịch chạy hệ thống, vì vậy không thể bảo đảm thao tác đúng tuyệt đối từng 1ms. Bạn tự nhấn trong Shopee.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
