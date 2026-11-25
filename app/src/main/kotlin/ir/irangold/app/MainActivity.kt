package ir.irangold.app

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import ir.irangold.core.ConfigException
import ir.irangold.core.Phase

private val Gold1 = Color(0xFFE5B83A)
private val Bg = Color(0xFF0B0B0F)
private val Card = Color(0xFF16161D)
private val Ok = Color(0xFF3DDC84)
private val Bad = Color(0xFFFF5C5C)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold1, background = Bg, surface = Card)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.fillMaxSize().background(Bg)) { App() }
                }
            }
        }
    }
}

@Composable
private fun App() {
    var settings by remember { mutableStateOf(false) }
    if (settings) SettingsScreen { settings = false } else MainScreen { settings = true }
}

private fun startVpn(ctx: Context) {
    ctx.startForegroundService(Intent(ctx, GoldVpnService::class.java).setAction(GoldVpnService.ACTION_CONNECT))
}

private fun stopVpn(ctx: Context) {
    ctx.startService(Intent(ctx, GoldVpnService::class.java).setAction(GoldVpnService.ACTION_DISCONNECT))
}

@Composable
private fun MainScreen(openSettings: () -> Unit) {
    val ctx = LocalContext.current
    val s = Gold.snapshot
    val active = s.phase != Phase.IDLE
    val permit = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) startVpn(ctx)
    }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() } }

    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("IRAN GOLD", color = Gold1, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = openSettings) { Text("تنظیمات", color = Color.White) }
        }
        Spacer(Modifier.height(28.dp))
        val color = when (s.phase) {
            Phase.CONNECTED -> Ok
            Phase.FAILED -> Bad
            Phase.IDLE -> Color.Gray
            else -> Gold1
        }
        Box(Modifier.size(150.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(104.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.height(16.dp))
        Text(s.message, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                if (active) stopVpn(ctx)
                else {
                    val i = VpnService.prepare(ctx)
                    if (i != null) permit.launch(i) else startVpn(ctx)
                }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (active) Bad else Gold1, contentColor = Bg),
        ) { Text(if (active) "قطع اتصال" else "اتصال", fontSize = 18.sp, fontWeight = FontWeight.Bold) }

        Spacer(Modifier.height(20.dp))
        InfoCard {
            Row2("موتور فعال", s.activeEngine?.let { e -> Gold.engines.firstOrNull { it.id == e }?.displayName } ?: "—")
            Row2("سلامت", if (s.report?.ok == true) "سالم (امتیاز ${s.score})" else "—")
            Row2("IP خروجی", s.report?.exitIp ?: "—")
            Row2("تأخیر", s.report?.latencyMs?.let { "$it ms" } ?: "—")
            Row2("سرعت", s.report?.speedKbps?.let { "$it kbps" } ?: "—")
            val since = s.connectedSinceMs
            Row2("مدت اتصال", if (since != null) fmt((now - since) / 1000) else "—")
        }
        Spacer(Modifier.height(16.dp))
        InfoCard {
            Text("انتخاب موتور", color = Color.LightGray, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((k, label) in listOf("auto" to "خودکار", "xray" to "Xray", "tor" to "Tor")) {
                    FilterChip(
                        selected = Gold.engineMode == k,
                        onClick = { if (!active) Gold.updateEngineMode(k) },
                        label = { Text(label) },
                    )
                }
            }
            val xr = Gold.engines.first { it.id == "xray" }
            xr.unavailableReason()?.let { Text("Xray: $it", color = Color.Gray, fontSize = 12.sp) }
        }
    }
}

private fun fmt(sec: Long): String = "%02d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}

@Composable
private fun Row2(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, color = Color.Gray)
        Text(v, color = Color.White)
    }
}

@Composable
private fun SettingsScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf(false) }

    fun doImport(raw: String) {
        try {
            Gold.importConfig(raw)
            text = ""; err = false; msg = "کانفیگ ذخیره شد (رمزنگاری‌شده)"
        } catch (e: ConfigException) {
            err = true; msg = e.message
        } catch (e: Exception) {
            err = true; msg = "خطا در ذخیره کانفیگ"
        }
    }

    val qr = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { doImport(it) } }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readNBytes(256 * 1024 + 1) }
                if (bytes.size > 256 * 1024) { err = true; msg = "فایل خیلی بزرگ است" }
                else doImport(String(bytes, Charsets.UTF_8))
            } catch (_: Exception) { err = true; msg = "فایل خوانده نشد" }
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("تنظیمات", color = Gold1, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = back) { Text("بازگشت", color = Color.White) }
        }
        InfoCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("کیل‌سوییچ", color = Color.White, fontWeight = FontWeight.Medium)
                    Text("اگر هیچ موتوری سالم نبود، اینترنت قطع می‌شود تا چیزی بی‌حفاظ بیرون نرود.", color = Color.Gray, fontSize = 12.sp)
                }
                Switch(checked = Gold.killSwitch, onCheckedChange = { Gold.updateKillSwitch(it) })
            }
            Text("برای قفل کامل، در تنظیمات اندروید «VPN همیشه روشن» و «مسدود کردن اتصال‌های بدون VPN» را هم فعال کنید.", color = Color.Gray, fontSize = 12.sp)
        }
        InfoCard {
            Text("وارد کردن کانفیگ Xray (VLESS)", color = Color.White, fontWeight = FontWeight.Medium)
            Text("کانفیگ شما: ${Gold.importSummary ?: "هنوز وارد نشده"}", color = Color.Gray, fontSize = 13.sp)
            Text("این برنامه هیچ سرور یا کلیدی همراه ندارد؛ فقط کانفیگی را که خودتان دارید می‌پذیرد.", color = Color.Gray, fontSize = 12.sp)
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("لینک vless:// یا JSON") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { doImport(text) }, enabled = text.isNotBlank()) { Text("ذخیره") }
                OutlinedButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val t = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                    if (t.isBlank()) { err = true; msg = "کلیپ‌بورد خالی است" } else doImport(t)
                }) { Text("کلیپ‌بورد") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { qr.launch(ScanOptions().setPrompt("QR را اسکن کنید").setBeepEnabled(false).setOrientationLocked(false)) }) { Text("اسکن QR") }
                OutlinedButton(onClick = { file.launch(arrayOf("*/*")) }) { Text("فایل") }
                if (Gold.importSummary != null) TextButton(onClick = { Gold.clearImport(); msg = "حذف شد"; err = false }) { Text("حذف", color = Bad) }
            }
            msg?.let { Text(it, color = if (err) Bad else Ok, fontSize = 13.sp) }
        }
        InfoCard {
            Text("درباره", color = Color.White, fontWeight = FontWeight.Medium)
            Text("IRAN GOLD ۰٫۱٫۰ — متن‌باز، مجوز GPLv3. موتورها: Xray-core (MPL-2.0)، Tor و Snowflake. جزئیات مجوزها در THIRD_PARTY_LICENSES.md.", color = Color.Gray, fontSize = 12.sp)
        }
    }
}
