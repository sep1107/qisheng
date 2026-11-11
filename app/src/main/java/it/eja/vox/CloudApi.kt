package it.eja.vox

import android.content.SharedPreferences
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class CloudApi(private val prefs: SharedPreferences, private val report: (String) -> Unit) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
    private val router = ApiRouter(PreferenceQuota(prefs))
    private val paraformer = ParaformerApi(client)
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun request(profile: ApiProfile, body: RequestBody): JSONObject {
        val request = Request.Builder().url(profile.url).header("Authorization", "Bearer ${profile.key}").post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val retry = response.header("Retry-After")?.let { raw ->
                    raw.toLongOrNull() ?: runCatching {
                        val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(raw)
                        maxOf(0, ((date?.time ?: 0) - System.currentTimeMillis()) / 1000)
                    }.getOrNull()
                }
                throw ApiHttpError(response.code, retry)
            }
            return try { JSONObject(response.body?.string() ?: "") }
            catch (e: org.json.JSONException) { throw ApiUnavailable("接口响应格式错误，保留数据，请检查配置") }
        }
    }

    fun transcribe(audio: File): String = router.run(ApiProfiles.load(prefs, "asr"),
        prefs.getBoolean("free_only", true), report) { p ->
        if (cancelled.get()) throw ApiUnavailable("转写已取消，录音仍保存在本机")
        if (p.url.startsWith("wss://")) return@run paraformer.transcribe(p, audio)
        val type = when (audio.extension.lowercase()) { "wav" -> "audio/wav"; "m4a" -> "audio/mp4"; else -> "audio/mpeg" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", audio.name, audio.asRequestBody(type.toMediaType()))
            .addFormDataPart("model", p.model).build()
        val json = request(p, body)
        if (!json.has("text")) throw ApiUnavailable("转写接口未返回 text，保留录音，请检查配置")
        json.getString("text")
    }

    fun chat(prompt: String): String = router.run(ApiProfiles.load(prefs, "llm"),
        prefs.getBoolean("free_only", true), report) { p ->
        val json = JSONObject().put("model", p.model).put("messages", JSONArray().put(
            JSONObject().put("role", "user").put("content", prompt)))
        val response = request(p, json.toString().toRequestBody("application/json".toMediaType()))
        try { response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content") }
        catch (e: org.json.JSONException) { throw ApiUnavailable("总结接口响应格式错误，请检查配置") }
    }

    fun cancel() { cancelled.set(true); paraformer.cancel(); client.dispatcher.cancelAll() }
}
