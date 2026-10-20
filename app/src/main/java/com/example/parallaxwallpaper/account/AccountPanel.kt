package com.example.parallaxwallpaper.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.parallaxwallpaper.ui.SetupViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun AccountPanel(vm: SetupViewModel, required: Boolean = false, onClose: () -> Unit) {
    val user by vm.account.collectAsState()
    val waiting by vm.accountBusy.collectAsState()
    val error by vm.accountMessage.collectAsState()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshAccount() }
    AlertDialog(onDismissRequest = { if (!waiting && !required) onClose() }, title = { Text(if (user == null) "登录浮光" else "账号与会员") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val current = user
                if (current == null) {
                    OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true, enabled = !waiting)
                    OutlinedTextField(password, { password = it }, label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !waiting)
                    Button(onClick = { vm.authenticate(username, password, register); password = "" }, enabled = !waiting && username.length >= 3 && password.length >= 8) { Text(if (register) "注册并登录" else "登录") }
                    TextButton(onClick = { register = !register }, enabled = !waiting) { Text(if (register) "已有账号？去登录" else "还没有账号？注册") }
                } else {
                    Text(current.optString("username"), style = MaterialTheme.typography.titleMedium)
                    val expiry = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }.format(Date(current.optLong("expires") * 1000))
                    Text(if (current.optInt("lifetime") == 1) "永久会员" else if (current.optBoolean("member")) "会员有效至 $expiry" else "本周剩余 ${current.optInt("remaining")} / 10 次")
                    Text("月卡 ¥1.99 · 年卡 ¥19.99 · 永久卡 ¥29.99")
                    OutlinedTextField(code, { code = it }, label = { Text("会员兑换码") }, singleLine = true, enabled = !waiting)
                    Button(onClick = { vm.redeem(code); code = "" }, enabled = !waiting && code.isNotBlank()) { Text("兑换会员") }
                    TextButton(onClick = vm::logout, enabled = !waiting) { Text("退出登录") }
                }
                if (waiting) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }, confirmButton = { if (!required) TextButton(onClick = onClose, enabled = !waiting) { Text("完成") } })
}
