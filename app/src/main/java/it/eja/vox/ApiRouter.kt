package it.eja.vox

import java.io.IOException
import java.net.URI

data class ApiProfile(val id: String, val name: String, val url: String, val key: String,
                      val model: String, val enabled: Boolean = false,
                      val free: Boolean = false, val dailyLimit: Int = 0, val freeUntil: String = "")

class ApiHttpError(val status: Int, val retrySeconds: Long? = null) : IOException()
class ApiUnavailable(message: String) : IOException(message)

interface ApiQuota {
    fun blockedUntil(host: String): Long
    fun block(host: String, until: Long)
    fun used(id: String, day: Long): Int
    fun reserve(id: String, day: Long)
}

/** Sequential fallback only. Provider cooldown is shared across keys/models on the same host. */
class ApiRouter(private val quota: ApiQuota, private val now: () -> Long = System::currentTimeMillis) {
    fun <T> run(profiles: List<ApiProfile>, freeOnly: Boolean, report: (String) -> Unit,
                request: (ApiProfile) -> T): T {
        val day = now() / 86_400_000L
        for (profile in profiles) {
            if (!profile.enabled || (freeOnly && !profile.free)) continue
            if (freeOnly && profile.freeUntil.isNotBlank()) {
                val expiry = runCatching {
                    require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(profile.freeUntil))
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
                        isLenient = false
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }.parse(profile.freeUntil)!!.time + 86_400_000L
                }.getOrNull()
                if (expiry == null || now() >= expiry) {
                    report("${profile.name} 免费期限已过或日期无效，尝试下一接口"); continue
                }
            }
            val uri = runCatching { URI(profile.url) }.getOrNull()
            if (uri == null || uri.scheme !in listOf("https", "wss") || uri.host.isNullOrBlank() || uri.userInfo != null || profile.model.isBlank()) {
                report("${profile.name} 配置不完整，已跳过"); continue
            }
            val host = uri.host.lowercase()
            if (quota.blockedUntil(host) > now()) {
                report("${profile.name} 冷却中，尝试下一接口"); continue
            }
            if (profile.dailyLimit > 0 && quota.used(profile.id, day) >= profile.dailyLimit) {
                report("${profile.name} 已达本机每日请求上限，尝试下一接口"); continue
            }
            // Reserve before sending: an ambiguous timeout may still have consumed provider quota.
            quota.reserve(profile.id, day)
            report("正在使用 ${profile.name}")
            try {
                return request(profile)
            } catch (e: ApiHttpError) {
                val seconds = when (e.status) {
                    429 -> maxOf(60L, e.retrySeconds ?: 60L)
                    402 -> maxOf(6 * 3600L, e.retrySeconds ?: 0L)
                    401, 403 -> maxOf(60L, e.retrySeconds ?: 60L)
                    in 500..599 -> maxOf(60L, e.retrySeconds ?: 60L)
                    else -> throw ApiUnavailable("${profile.name} 返回 HTTP ${e.status}，保留数据，请检查接口")
                }
                quota.block(host, now() + seconds.coerceAtMost(31_536_000L) * 1000)
                report("${profile.name} 返回 HTTP ${e.status}，已冷却并尝试下一接口")
            } catch (e: ApiUnavailable) {
                throw e
            } catch (e: IOException) {
                quota.block(host, now() + 60_000)
                report("${profile.name} 网络失败，尝试下一接口；超时可能已计费")
            }
        }
        throw ApiUnavailable("没有可用接口：检查免费标记、每日上限或冷却状态；音频仍保存在本机")
    }
}
