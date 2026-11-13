package it.eja.vox

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

object ApiProfiles {
    fun load(prefs: SharedPreferences, kind: String): List<ApiProfile> = (0..2).map { index ->
        val id = "${kind}_$index"
        val prefix = if (index == 0) "" else "${id}_"
        val urlKey = if (index != 0) prefix + "url" else if (kind == "asr") "url" else "llm_url"
        val keyKey = if (index != 0) prefix + "key" else if (kind == "asr") "token" else "llm_auth"
        val modelKey = if (index != 0) prefix + "model" else if (kind == "asr") "v_model" else "llm_model"
        ApiProfile(id, prefs.getString("${id}_name", "${if (kind == "asr") "转写" else "总结"}接口 ${index + 1}")!!,
            prefs.getString(urlKey, "")!!, prefs.getString(keyKey, "")!!, prefs.getString(modelKey, "")!!,
            prefs.getBoolean("${id}_enabled", index == 0), prefs.getBoolean("${id}_free", false),
            prefs.getInt("${id}_limit", 0), prefs.getString("${id}_free_until", "") ?: "")
    }

    fun save(prefs: SharedPreferences, profiles: List<ApiProfile>) {
        val edit = prefs.edit()
        profiles.forEach { p ->
            val first = p.id.endsWith("_0")
            val asr = p.id.startsWith("asr")
            edit.putString(if (first) if (asr) "url" else "llm_url" else "${p.id}_url", p.url.trim())
                .putString(if (first) if (asr) "token" else "llm_auth" else "${p.id}_key", p.key.trim())
                .putString(if (first) if (asr) "v_model" else "llm_model" else "${p.id}_model", p.model.trim())
                .putString("${p.id}_name", p.name).putBoolean("${p.id}_enabled", p.enabled)
                .putBoolean("${p.id}_free", p.free).putInt("${p.id}_limit", p.dailyLimit)
                .putString("${p.id}_free_until", p.freeUntil.trim())
        }
        edit.apply()
    }
}

class PreferenceQuota(private val prefs: SharedPreferences) : ApiQuota {
    override fun blockedUntil(host: String) = prefs.getLong("cooldown_$host", 0)
    override fun block(host: String, until: Long) { prefs.edit().putLong("cooldown_$host", until).apply() }
    override fun used(id: String, day: Long): Int =
        if (prefs.getLong("day_$id", -1) == day) prefs.getInt("used_$id", 0) else 0
    @Synchronized override fun reserve(id: String, day: Long) {
        if (!prefs.edit().putLong("day_$id", day).putInt("used_$id", used(id, day) + 1).commit()) {
            throw ApiUnavailable("无法保存本机额度计数，暂停请求")
        }
    }
}

@Composable
fun ApiSettingsDialog(prefs: SharedPreferences, checkUpdate: () -> Unit, dismiss: () -> Unit, saved: () -> Unit) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(0) }
    var asrIndex by remember { mutableStateOf(0) }
    var llmIndex by remember { mutableStateOf(0) }
    var asr by remember { mutableStateOf(ApiProfiles.load(prefs, "asr")) }
    var llm by remember { mutableStateOf(ApiProfiles.load(prefs, "llm")) }
    var freeOnly by remember { mutableStateOf(prefs.getBoolean("free_only", true)) }
    var language by remember { mutableStateOf(prefs.getString("language", "中文") ?: "中文") }
    var liveEnabled by remember { mutableStateOf(prefs.getBoolean("live_enabled", true)) }
    var receiverEnabled by remember { mutableStateOf(prefs.getBoolean("receiver_enabled", false)) }
    var autoSend by remember { mutableStateOf(prefs.getBoolean("receiver_auto_send", false)) }
    var receiverUrl by remember { mutableStateOf(prefs.getString("receiver_url", "").orEmpty()) }
    var receiverToken by remember { mutableStateOf(prefs.getString("receiver_token", "").orEmpty()) }
    var receiverError by remember { mutableStateOf("") }
    val index = if (page == 0) asrIndex else llmIndex
    val profile = (if (page == 0) asr else llm)[index]
    fun update(value: ApiProfile) {
        if (page == 0) asr = asr.toMutableList().apply { set(index, value) }
        else llm = llm.toMutableList().apply { set(index, value) }
    }
    AlertDialog(onDismissRequest = dismiss, title = {
        Column {
            Row(Modifier.fillMaxWidth()) {
                Text("设置", Modifier.weight(1f))
                TextButton(checkUpdate) { Text("检查更新") }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("转写 API", "总结 API", "发送配置").forEachIndexed { n, label ->
                    TextButton({ page = n }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                        colors = ButtonDefaults.textButtonColors(containerColor = if (page == n) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                        Text(label, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (receiverError.isNotBlank()) Text(receiverError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }, text = {
        key(page) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (page == 2) {
                    Text("接收端使用同一协议，只需填写地址和令牌。")
                    Row { Checkbox(receiverEnabled, { receiverEnabled = it; if (!it) autoSend = false }); Text("启用接收端") }
                    OutlinedTextField(receiverUrl, { receiverUrl = it }, label = { Text("完整 HTTPS 接收地址") }, singleLine = true)
                    OutlinedTextField(receiverToken, { receiverToken = it }, label = { Text("接收令牌（至少24位）") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Row { Checkbox(autoSend, { autoSend = it }, enabled = receiverEnabled); Text("总结完成后自动发送") }
                    Text("原文和总结一起发送，不自动生成总结、不发送音频、不追溯旧总结。断网后台重试，不阻塞录音；已发送版本不自动覆盖。")
                } else {
                    if (page == 0) {
                        Row { Checkbox(liveEnabled, { liveEnabled = it }); Text("实时字幕") }
                        Text("默认开启，完整文字以分段转写为准。")
                    }
                    Text("按 1→2→3 顺序尝试；仅启用的接口会接收${if (page == 0) "音频" else "文字"}。")
                    Row { Checkbox(freeOnly, { freeOnly = it }); Text("仅使用我确认仍有免费额度的接口") }
                    Row { (0..2).forEach { n -> TextButton({ if (page == 0) asrIndex = n else llmIndex = n }) { Text(if (index == n) "【${n + 1}】" else "${n + 1}") } } }
                    Row { Checkbox(profile.enabled, { update(profile.copy(enabled = it)) }); Text("启用此接口") }
                    TextField(profile.name, { update(profile.copy(name = it)) }, label = { Text("接口名称") })
                    TextField(profile.url, { update(profile.copy(url = it)) }, label = { Text(if (page == 0) "完整 HTTPS / WSS 接口地址" else "完整 HTTPS 总结接口地址") })
                    TextField(profile.key, { update(profile.copy(key = it)) }, label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation())
                    TextField(profile.model, { update(profile.copy(model = it)) }, label = { Text("模型 ID") })
                    Row { Checkbox(profile.free, { update(profile.copy(free = it)) }); Text("已在控制台确认有免费额度") }
                    TextField(profile.freeUntil, { update(profile.copy(freeUntil = it)) }, label = { Text("免费截止日期 YYYY-MM-DD（UTC，可留空）") })
                    TextField(if (profile.dailyLimit == 0) "" else profile.dailyLimit.toString(),
                        { value -> if (value.all(Char::isDigit)) update(profile.copy(dailyLimit =
                            if (value.isEmpty()) 0 else (value.toLongOrNull() ?: Int.MAX_VALUE.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())) },
                        label = { Text("本机每日请求上限（留空不限制）") })
                    val host = runCatching { java.net.URI(profile.url).host }.getOrNull()
                    val until = host?.let { prefs.getLong("cooldown_${it.lowercase()}", 0) } ?: 0
                    if (until > System.currentTimeMillis()) Text("服务商冷却至 " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(until)))
                    if (page == 1) TextField(language, { language = it }, label = { Text("总结语言") })
                    Text("UTC 每日重置；本机计数不是服务商真实剩余额度。免费价格须在服务商核实，超时重试可能计费。")
                }
            }
        }
    }, confirmButton = { TextButton({
        runCatching {
            if (receiverEnabled) {
                RecordingExport.receiverUrl(receiverUrl)
                require(receiverToken.trim().length >= 24 && receiverToken.trim().all { it.code in 33..126 }) { "接收令牌至少24位，不能包含空格或换行" }
            }
            val resetSince = autoSend && (!prefs.getBoolean("receiver_auto_send", false) || prefs.getString("receiver_url", "") != receiverUrl.trim())
            ApiProfiles.save(prefs, asr + llm)
            val edit = prefs.edit().putBoolean("free_only", freeOnly).putString("language", language).putBoolean("live_enabled", liveEnabled)
                .putBoolean("receiver_enabled", receiverEnabled).putBoolean("receiver_auto_send", autoSend && receiverEnabled)
                .putString("receiver_url", receiverUrl.trim()).putString("receiver_token", receiverToken.trim())
            if (resetSince) edit.putLong("receiver_auto_since", System.currentTimeMillis())
            check(edit.commit()) { "设置保存失败，请检查存储空间" }
            NoteDelivery.resume(context)
            saved()
        }.onFailure { receiverError = it.message ?: "接收配置无效，请检查后重试" }
    }) { Text("保存") } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}
