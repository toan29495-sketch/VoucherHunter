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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.contract.ActivityResultContracts
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
    val parts = time.split(":").mapNotNull { it.toIntOrNull() }
    if (parts.size !in 2..3 || parts[0] !in 0..23 || parts[1] !in 0..59 ||
        (parts.size == 3 && parts[2] !in 0..59)) return null
    val p = if (parts.size == 2) parts + 0 else parts
    val cal = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, p[0]); set(Calendar.MINUTE, p[1])
        set(Calendar.SECOND, p[2]); set(Calendar.MILLISECOND, 0)
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
    var targetTime by remember { mutableStateOf(prefs.getString("time", "00:00") ?: "00:00") }
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
        while (true) { now = System.currentTimeMillis(); delay(250) }
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

    val clock = SimpleDateFormat("HH:mm:ss", Locale("vi", "VN")).format(Date(now))
    val countdown = if (armed) durationText(targetAt - now) else "--:--:--"

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("VOUCHER HUNTER 1.2", fontSize = 23.sp, fontWeight = FontWeight.Black)
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
            OutlinedTextField(targetTime, { targetTime = it }, label = { Text("Giờ mở voucher (HH:mm hoặc HH:mm:ss)") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                val item = JSONObject().put("name", voucherName).put("amount", amount)
                    .put("minimum", minimum).put("time", targetTime).put("rawText", rawText)
                    .put("state", "Đã lưu — chờ kiểm tra")
                persist(savedVouchers + item)
                status = "Đã lưu mục tiêu voucher"; log = "Đã thêm: $voucherName"
            }, modifier = Modifier.fillMaxWidth()) { Text("LƯU MỤC TIÊU VOUCHER") }

            Text("3. TỰ ĐỘNG CANH GIỜ", fontWeight = FontWeight.Bold)
            Button(onClick = {
                val t = nextOccurrence(targetTime, System.currentTimeMillis())
                if (t == null) status = "Giờ không hợp lệ. Ví dụ 09:00 hoặc 09:00:00"
                else {
                    prefs.edit().putString("amount", amount).putString("minimum", minimum).putString("time", targetTime).apply()
                    targetAt = t; armed = true; fired60 = false; fired10 = false; fired0 = false
                    status = "Đã bật canh giờ và cảnh báo rung"; log = "Bắt đầu canh giờ"
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("BẬT CANH GIỜ") }
            OutlinedButton(onClick = { armed = false; status = "Đã dừng canh giờ" }, modifier = Modifier.fillMaxWidth()) { Text("DỪNG CANH GIỜ") }
            OutlinedButton(onClick = { alertVibration(context); openShopee() }, modifier = Modifier.fillMaxWidth()) { Text("ĐẾN SHOPEE ĐỂ KIỂM TRA VOUCHER") }

            Text("4. DANH SÁCH MỤC TIÊU", fontWeight = FontWeight.Bold)
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
            Text("OCR xử lý trên thiết bị. App tự nhận diện các trường phổ biến nhưng bạn cần rà soát vì bố cục voucher có thể khác nhau. Hiện app chưa tự bấm nhận/áp voucher trong Shopee; nút Shopee chỉ mở ứng dụng. Cảnh báo đếm ngược hiện hoạt động khi Voucher Hunter đang mở.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
