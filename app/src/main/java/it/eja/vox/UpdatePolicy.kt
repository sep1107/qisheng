package it.eja.vox

import java.net.URI

object UpdatePolicy {
    fun versionCode(tag: String): Int {
        val match = Regex("v?([0-9]{1,3})\\.([0-9]{1,2})\\.([0-9]{1,2})").matchEntire(tag) ?: error("版本号格式不正确")
        val (major, minor, patch) = match.destructured
        return major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
    }
    fun validateAsset(url: String, digest: String, size: Long) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1)
        require(uri.rawPath.startsWith("/sep1107/qisheng/releases/download/") && uri.path.endsWith(".apk"))
        require(uri.query == null && uri.fragment == null && !uri.path.contains("/../"))
        require(Regex("sha256:[0-9a-fA-F]{64}").matches(digest))
        require(size in 1..150_000_000L)
    }
}
