package vn.voucherhunter

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
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
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { VoucherHunterScreen() } }
    }
}

@Composable
fun VoucherHunterScreen() {
    val context = LocalContext.current
    var targetAmount by remember { mutableStateOf("11000000") }
    var minimumOrder by remember { mutableStateOf("5000000") }
    var targetTime by remember { mutableStateOf("00:00:00") }
    var status by remember { mutableStateOf("Sẵn sàng cấu hình") }
    var running by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var log by remember { mutableStateOf("Chưa có hoạt động.") }

    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(50)
        }
    }
    val clock = remember(now) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale("vi", "VN")).format(Date(now))
    }
    fun openShopee() {
        try {
            // Ưu tiên mở ứng dụng Shopee đã cài trên máy bằng package Android chính thức.
            val shopeeLaunch = context.packageManager.getLaunchIntentForPackage("com.shopee.vn")
            if (shopeeLaunch != null) {
                shopeeLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(shopeeLaunch)
            } else {
                // Nếu chưa cài Shopee, mở trang web để người dùng tiếp tục.
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shopee.vn/")))
            }
        } catch (_: Exception) {
            status = "Không mở được Shopee. Hãy kiểm tra ứng dụng đã cài đặt chưa."
        }
    }
    fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(android.os.VibrationEffect.createOneShot(250, 180))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                @Suppress("DEPRECATION") v.vibrate(250)
            }
        } catch (_: Exception) {}
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("VOUCHER HUNTER", fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text("Trợ lý chuẩn bị săn voucher")
                }
                AssistChip(onClick = {}, label = { Text("v1.0") })
            }
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ĐỒNG HỒ THIẾT BỊ")
                    Text(clock, fontSize = 31.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { running = !running; status = if (running) "Đang cập nhật đồng hồ" else "Đã tạm dừng" }) {
                            Text(if (running) "Tạm dừng" else "Bắt đầu")
                        }
                        OutlinedButton(onClick = { now = System.currentTimeMillis() }) {
                            Icon(Icons.Default.Refresh, null); Text(" Làm mới")
                        }
                    }
                }
            }
            Text("VOUCHER MỤC TIÊU", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField("Shopee · SPayLater", {}, readOnly = true,
                label = { Text("Nền tảng / loại voucher") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(targetAmount, { targetAmount = it.filter(Char::isDigit) },
                label = { Text("Mức giảm tối đa (VNĐ)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(minimumOrder, { minimumOrder = it.filter(Char::isDigit) },
                label = { Text("Đơn hàng tối thiểu (VNĐ)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(targetTime, { targetTime = it },
                label = { Text("Giờ mở voucher (HH:mm:ss)") }, modifier = Modifier.fillMaxWidth())
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("TRẠNG THÁI", fontWeight = FontWeight.Bold)
                    Text(status)
                    Text("Ứng dụng hỗ trợ canh giờ và mở Shopee. Việc nhận voucher, áp mã và thanh toán cần thực hiện trong ứng dụng Shopee; không tự động vượt cơ chế bảo vệ của nền tảng.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            status = "Đã lưu mục tiêu trong phiên hiện tại"
                            log = "[$clock] Mục tiêu: giảm ${targetAmount}đ, đơn tối thiểu ${minimumOrder}đ, giờ ${targetTime}."
                        }) { Text("Lưu mục tiêu") }
                        OutlinedButton(onClick = { openShopee() }) {
                            Icon(Icons.Default.OpenInNew, null); Text(" Mở Shopee")
                        }
                    }
                    OutlinedButton(onClick = { vibrate(); status = "Đã thử rung cảnh báo"; log = "[$clock] Đã thử rung." },
                        modifier = Modifier.fillMaxWidth()) { Text("Thử cảnh báo rung") }
                }
            }
            Text("NHẬT KÝ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(log, style = MaterialTheme.typography.bodySmall)
            Text("Đồng hồ hiện dùng giờ hệ thống trên điện thoại, chưa đồng bộ/đối chiếu giờ máy chủ Shopee. Cấu hình hiện chưa được lưu bền vững sau khi đóng app.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
