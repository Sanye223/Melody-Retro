package com.melody.player.data.lyrics

import java.net.HttpURLConnection
import java.net.URI

/** 歌词请求失败：区分网络问题与服务端异常，便于给用户不同的提示。 */
class LyricsException(
    message: String,
    val isNetwork: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 歌词接口共用的 HTTP GET 工具。
 * 只被歌词模块使用，超时设置较短，避免拖慢播放页。
 */
internal object LyricsHttp {

    /** 部分接口（QQ 音乐）对非浏览器 UA 不友好，统一带一个常见 UA。 */
    const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 7_000,
        readTimeoutMs: Int = 10_000,
    ): String {
        var connection: HttpURLConnection? = null
        try {
            connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", BROWSER_UA)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                throw LyricsException("歌词服务返回异常（HTTP $code）")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (exception: LyricsException) {
            throw exception
        } catch (throwable: Exception) {
            throw LyricsException(
                message = "网络请求失败，请检查网络后重试",
                isNetwork = true,
                cause = throwable,
            )
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}
