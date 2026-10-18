package com.raven.mobile

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Raven 网关「一次性 nonce 链接」会话流程。
 *
 * 1) POST {base}/auth/nonce
 *    请求头：X-Raven-Token: <64 位 token>、Authorization: Basic <base64(user:pass)>
 *    响应：{"nonce":"..."}（一次性，约 30 分钟有效）
 * 2) POST {base}/auth/exchange
 *    请求体：{"nonce":"..."}，同样带 Basic Auth
 *    响应：200 + Set-Cookie: raven_session_<端口>=<值>
 *
 * 拿到的 name=value 交给 MainActivity 写进 WebView 的 CookieManager，
 * 之后加载首页即完整可用（WebSocket 升级 /file 等）。
 *
 * 本工程未引入 OkHttp，这里用 HttpURLConnection；全程只走 https。
 */
object GatewayAuth {

    enum class RejectKind { BASIC_AUTH, TOKEN }

    /** 认证过程中的阶段，用于给界面做进度提示。 */
    enum class Step { CONNECTING, MINT_NONCE, EXCHANGE }

    sealed class Result {
        /** 成功拿到会话 cookie */
        data class Success(val cookieName: String, val cookieValue: String) : Result()

        /** 服务端明确拒绝：401/407 → Basic Auth 错；403 → token 错 */
        data class Rejected(val kind: RejectKind, val httpCode: Int) : Result()

        /** 连不上（DNS / 超时 / 拒连 / SSL 握手失败） */
        data class NetworkError(val error: Exception) : Result()

        /** 连上了但响应不符合预期（缺 nonce、缺 Set-Cookie、异常状态码） */
        data class ProtocolError(val detail: String) : Result()
    }

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val SESSION_COOKIE_PREFIX = "raven_session"

    private class Call(
        val code: Int,
        val body: String,
        val setCookies: List<String>
    )

    fun authenticate(
        baseUrl: String,
        basicUser: String,
        basicPass: String,
        token: String,
        onStep: (Step) -> Unit = {}
    ): Result {
        val base = baseUrl.trimEnd('/')
        val basic = basicAuthValue(basicUser, basicPass)
        return try {
            // ---- 1. mint nonce ----
            onStep(Step.MINT_NONCE)
            val nonceCall = post(
                url = "$base/auth/nonce",
                basic = basic,
                extraHeaders = arrayOf("X-Raven-Token" to token),
                jsonBody = null
            )
            if (nonceCall.code !in 200..299) return reject(nonceCall.code)
            val nonce = JSONObject(nonceCall.body).optString("nonce")
            if (nonce.isBlank()) return Result.ProtocolError("/auth/nonce 未返回 nonce 字段")

            // ---- 2. exchange ----
            onStep(Step.EXCHANGE)
            val exchangeCall = post(
                url = "$base/auth/exchange",
                basic = basic,
                extraHeaders = emptyArray(),
                jsonBody = JSONObject().put("nonce", nonce).toString()
            )
            if (exchangeCall.code !in 200..299) return reject(exchangeCall.code)

            val cookie = extractSessionCookie(exchangeCall.setCookies)
                ?: return Result.ProtocolError("/auth/exchange 未返回会话 Cookie")
            Result.Success(cookie.first, cookie.second)
        } catch (e: IOException) {
            Result.NetworkError(e)
        } catch (e: Exception) {
            Result.ProtocolError(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun reject(code: Int): Result = when (code) {
        401, 407 -> Result.Rejected(RejectKind.BASIC_AUTH, code)
        403 -> Result.Rejected(RejectKind.TOKEN, code)
        else -> Result.ProtocolError("HTTP $code")
    }

    private fun post(
        url: String,
        basic: String,
        extraHeaders: Array<Pair<String, String>>,
        jsonBody: String?
    ): Call {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = jsonBody != null
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Authorization", basic)
            setRequestProperty("Accept", "application/json")
            for ((key, value) in extraHeaders) setRequestProperty(key, value)
            if (jsonBody != null) setRequestProperty("Content-Type", "application/json")
        }
        try {
            if (jsonBody != null) {
                conn.outputStream.use { it.write(jsonBody.toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = conn.responseCode
            val body = readBody(conn)
            return Call(code, body, collectSetCookieHeaders(conn))
        } finally {
            conn.disconnect()
        }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = try {
            conn.inputStream
        } catch (e: IOException) {
            conn.errorStream
        } ?: return ""
        return stream.use { raw ->
            BufferedReader(InputStreamReader(raw, StandardCharsets.UTF_8)).use { it.readText() }
        }
    }

    private fun collectSetCookieHeaders(conn: HttpURLConnection): List<String> {
        val out = ArrayList<String>()
        for ((key, values) in conn.headerFields) {
            if (key != null && key.equals("Set-Cookie", ignoreCase = true)) {
                out.addAll(values)
            }
        }
        if (out.isEmpty()) {
            conn.getHeaderField("Set-Cookie")?.let { out.add(it) }
        }
        return out
    }

    /**
     * 从 Set-Cookie 头里解析 name=value。
     * cookie 名是 raven_session_<端口>，端口会变，所以只匹配前缀、不硬编码端口；
     * 万一前缀变了，兜底用第一个 Set-Cookie 的第一段。
     */
    private fun extractSessionCookie(headers: List<String>): Pair<String, String>? {
        for (header in headers) {
            val pair = splitFirstCookiePair(header) ?: continue
            if (pair.first.startsWith(SESSION_COOKIE_PREFIX, ignoreCase = true)) {
                return pair
            }
        }
        for (header in headers) {
            splitFirstCookiePair(header)?.let { return it }
        }
        return null
    }

    private fun splitFirstCookiePair(header: String): Pair<String, String>? {
        val first = header.substringBefore(';').trim()
        if (!first.contains('=')) return null
        val name = first.substringBefore('=').trim()
        val value = first.substringAfter('=', "").trim()
        if (name.isEmpty() || value.isEmpty()) return null
        return name to value
    }

    internal fun basicAuthValue(user: String, pass: String): String {
        val raw = "$user:$pass".toByteArray(StandardCharsets.UTF_8)
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }
}
