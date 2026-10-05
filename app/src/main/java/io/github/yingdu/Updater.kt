package io.github.yingdu

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 检查更新（版本比较、解析有单元测试）：每天看一次 GitHub 上萤读的最新 Release，有新版就在手机上问用户「跳过 / 立即下载」。
 * 下载完交给系统安装器，要用户自己点「安装」；不会在后台悄悄更新。
 * 查版本先问 GitHub，连不上再看仓库里的 update.json（经 jsDelivr）；下载先走 jsDelivr（国内快），再走 GitHub。
 */
object Updater {
    const val REPO = "codexmasterme/yingdu"
    private const val UA = "Yingdu-Updater (+https://github.com/$REPO)"

    /** 一个新版本：版本号（不带 v）、APK 文件名、几个下载地址（按顺序试）、SHA-256（Release 说明里写了才有）、更新说明。 */
    data class Release(val version: String, val apkName: String, val urls: List<String>, val sha256: String?, val notes: String)

    /** a 比 b 新吗（「1.0.10」比「1.0.9」新；前面的 v 和后面的 -beta 之类不算）。 */
    fun newer(a: String, b: String): Boolean {
        fun parts(s: String) = s.trim().removePrefix("v").removePrefix("V").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private val SHA = Regex("\\b[0-9a-fA-F]{64}\\b")

    /** 下载地址：jsDelivr（按标签）、GitHub Release 附件、jsDelivr（main 分支）、GitHub 原始文件。 */
    fun urls(version: String, apkName: String, asset: String?): List<String> = listOfNotNull(
        "https://cdn.jsdelivr.net/gh/$REPO@v$version/releases/$apkName",
        asset,
        "https://cdn.jsdelivr.net/gh/$REPO@main/releases/$apkName",
        "https://raw.githubusercontent.com/$REPO/main/releases/$apkName",
    ).distinct()

    /** 更新说明：去掉 Markdown 记号、SHA-256 那行和安装步骤以外的链接，最多 600 字。 */
    fun cleanNotes(body: String): String = body.lines()
        .filter { !it.contains("SHA-256", ignoreCase = true) && !SHA.containsMatchIn(it) }
        .map { it.replace(Regex("^#+\\s*"), "").replace("**", "").replace("`", "").replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1").trimEnd() }
        .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim().let { if (it.length > 600) it.take(600).trimEnd() + "…" else it }

    /** GitHub 的 releases/latest 接口。 */
    fun parseGithub(json: String): Release? {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val version = o.optString("tag_name").removePrefix("v").removePrefix("V").ifEmpty { return null }
        val assets = o.optJSONArray("assets")
        var apkName = "Yingdu-v$version.apk"; var asset: String? = null
        for (i in 0 until (assets?.length() ?: 0)) {
            val a = assets!!.optJSONObject(i) ?: continue
            if (a.optString("name").endsWith(".apk")) { apkName = a.optString("name"); asset = a.optString("browser_download_url").ifEmpty { null }; break }
        }
        val body = o.optString("body")
        return Release(version, apkName, urls(version, apkName, asset), SHA.find(body)?.value?.lowercase(), cleanNotes(body))
    }

    /** 仓库里的 update.json：{"version":"1.0.3","apk":"Yingdu-v1.0.3.apk","sha256":"…","notes":"…"}。 */
    fun parseUpdateJson(json: String): Release? {
        val o = JSONObject(json)
        val version = o.optString("version").removePrefix("v").ifEmpty { return null }
        val apkName = o.optString("apk").substringAfterLast('/').ifEmpty { "Yingdu-v$version.apk" }
        return Release(version, apkName, urls(version, apkName, null), o.optString("sha256").lowercase().takeIf { SHA.matches(it) }, cleanNotes(o.optString("notes")))
    }

    private fun get(url: String, accept: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", UA)
        if (accept != null) c.setRequestProperty("Accept", accept)
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** 查最新版本（后台线程）。查不到抛异常。 */
    fun latest(): Release {
        val errors = ArrayList<String>()
        runCatching { parseGithub(get("https://api.github.com/repos/$REPO/releases/latest", "application/vnd.github+json")) }
            .onSuccess { if (it != null) return it }.onFailure { errors.add("GitHub：${it.message}") }
        runCatching { parseUpdateJson(get("https://cdn.jsdelivr.net/gh/$REPO@main/update.json")) }
            .onSuccess { if (it != null) return it }.onFailure { errors.add("jsDelivr：${it.message}") }
        throw java.io.IOException(errors.joinToString("；").ifEmpty { "没查到版本信息" })
    }

    /**
     * 下载 APK 到 dest（后台线程）：几个地址按顺序试；有 SHA-256 就核对，对不上算失败。
     * progress(已下载, 总大小（不知道时 -1）)；cancelled() 返回 true 就停。
     */
    fun download(r: Release, dest: File, progress: (Long, Long) -> Unit, cancelled: () -> Boolean) {
        val errors = ArrayList<String>()
        for (url in r.urls) {
            if (cancelled()) throw InterruptedException("已取消")
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", UA)
                try {
                    if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
                    val total = c.contentLengthLong
                    val md = MessageDigest.getInstance("SHA-256")
                    var got = 0L
                    dest.outputStream().use { out ->
                        c.inputStream.use { ins ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (cancelled()) throw InterruptedException("已取消")
                                val n = ins.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n); md.update(buf, 0, n); got += n
                                progress(got, total)
                            }
                        }
                    }
                    if (got < 100_000) throw java.io.IOException("文件太小（$got 字节）")
                    val sha = md.digest().joinToString("") { "%02x".format(it) }
                    if (r.sha256 != null && sha != r.sha256) throw java.io.IOException("SHA-256 对不上")
                    return
                } finally { c.disconnect() }
            } catch (e: InterruptedException) { dest.delete(); throw e
            } catch (e: Exception) { errors.add(URL(url).host + "：" + e.message); dest.delete() }
        }
        throw java.io.IOException("下载失败（" + errors.joinToString("；") + "）")
    }
}
