package com.phigros.attacker

import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder

val isoDate = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)!!

class PigeonRequest(
    private val sessionToken: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val headers: Map<String, String> = mapOf(
        "X-LC-Session" to sessionToken,
        "X-LC-Id" to "rAK3FfdieFob2Nn8Am",
        "X-LC-Key" to "Qr9AEqtuoSVS3zeD6iVbM4ZC0AtkJcQ89tywVyi0",
        "User-Agent" to "LeanCloud-CSharp-SDK/1.0.3",
        "Accept" to "application/json",
        "Connection" to "keep-alive",
        "Content-Type" to "application/json"
    )
) {

    private fun request(method: String, url: String, headers: Map<String, String>? = null, body: RequestBody? = null): Response {
        val finalHeaders = headers ?: this.headers
        val requestBuilder = Request.Builder().url(url)
        finalHeaders.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        when (method.uppercase()) {
            "GET" -> requestBuilder.get()
            "DELETE" -> requestBuilder.delete()
            "POST" -> requestBuilder.post(body ?: "".toRequestBody(null))
            "PUT" -> requestBuilder.put(body ?: "".toRequestBody(null))
            else -> throw IllegalArgumentException("Unsupported HTTP method: $method")
        }

        val request = requestBuilder.build()
        val response = client.newCall(request).execute()

        Timber.d("请求类型: $method,\n请求URL: $url,\n请求头: $finalHeaders,\n状态码: ${response.code}")

        if (!response.isSuccessful) {
            throw IOException("Unexpected code $response")
        }

        return response
    }

    fun get(url: String, headers: Map<String, String>? = null): Response {
        return request("GET", url, headers)
    }

    fun delete(url: String, headers: Map<String, String>? = null): Response {
        return request("DELETE", url, headers)
    }

    fun post(url: String, body: RequestBody, headers: Map<String, String>? = null): Response {
        return request("POST", url, headers, body)
    }

    fun put(url: String, body: RequestBody, headers: Map<String, String>? = null): Response {
        return request("PUT", url, headers, body)
    }
}

class PhigrosCloud(
    sessionToken: String,
    client: OkHttpClient? = null
) {

    private val request: PigeonRequest = PigeonRequest(sessionToken, client ?: OkHttpClient())
    private val baseUrl = "https://rak3ffdi.cloud.tds1.tapapis.cn/1.1/"

    private var isRequestInProgress = false

    private suspend fun <T> safeRequest(call: suspend () -> T): T? {
        if (isRequestInProgress) {
            Timber.w("请求正在处理中")
            return null
        }
        isRequestInProgress = true
        return try {
            call()
        } catch (e: Exception) {
            Timber.e(e, "请求失败")
            null
        } finally {
            isRequestInProgress = false
        }
    }

    suspend fun deleteSave(): Boolean? {
        return safeRequest {
            Timber.d("调用函数：deleteSave()")
            val response = request.get(baseUrl + "classes/_GameSave?limit=1")
            val responseBody = response.body?.string()

            if (responseBody != null) {
                val jsonObject = JSONObject(responseBody)
                val results = jsonObject.getJSONArray("results")
                val objectId = results.getJSONObject(0).getJSONObject("gameFile").getString("objectId")
                val savesDeleteData = request.delete(baseUrl + "files/$objectId")
                Timber.d(savesDeleteData.toString())
            } else {
                Timber.e("响应体为空")
            }
            true
        }
    }

    suspend fun getSave(): JSONObject? {
        return safeRequest {
            Timber.d("调用函数：getSave()")
            val response = request.get(baseUrl + "classes/_GameSave?limit=1")
            val responseBody = response.body?.string()

            if (responseBody != null) {
                val jsonObject = JSONObject(responseBody)
                val results = jsonObject.getJSONArray("results")
                if (results.length() > 0) {
                    results.getJSONObject(0)
                } else {
                    Timber.e("没有找到存档数据")
                    null
                }
            } else {
                Timber.e("响应体为空")
                null
            }
        }
    }

    // 新增函数，用于获取存档文件并返回Base64编码
    suspend fun getSaveFileAsBase64(url: String): String? {
        return safeRequest {
            // 发送 GET 请求以获取存档文件
            val response = request.get(url)

            // 获取响应体
            val responseBody: ResponseBody = response.body ?: throw IOException("空的响应体")

            // 获取存档文件的二进制数据
            val fileBytes = responseBody.bytes()

            // 将文件二进制数据转换为 Base64 字符串
            val base64Encoded = Base64.getEncoder().encodeToString(fileBytes)

            // 返回 Base64 编码的字符串
            base64Encoded
        }
    }
}

fun getSummary(summaryBase64: String): Map<String, Any> {
    val summaryBytes = Base64.getDecoder().decode(summaryBase64)
    // 获取头像字符串的长度，即原字节数组的第8个字节（索引8）
    val avatarLength = summaryBytes[8].toInt() and 0xFF

    // 创建ByteBuffer并设置字节顺序为小端（与Python的struct.unpack '=' 一致）
    val byteBuffer = ByteBuffer.wrap(summaryBytes).order(ByteOrder.LITTLE_ENDIAN)

    // 按顺序解析字段
    val saveVersion = byteBuffer.get().toInt() and 0xFF         // 1字节，无符号
    val challenge = byteBuffer.short.toInt() and 0xFFFF         // 2字节，无符号短整型
    val rks = byteBuffer.float                                  // 4字节，浮点数
    val gameVersion = byteBuffer.get().toInt() and 0xFF          // 1字节，无符号
    byteBuffer.get()                                             // 跳过1字节填充（x）

    // 读取动态长度的头像字符串
    val avatarBytes = ByteArray(avatarLength)
    byteBuffer.get(avatarBytes)
    val avatar = String(avatarBytes, Charsets.UTF_8)

    // 读取12个无符号短整型（H）作为评级数据
    val ratings = mutableListOf<Int>()
    repeat(12) {
        ratings.add(byteBuffer.short.toInt() and 0xFFFF)
    }

    return mapOf(
        "saveVersion" to saveVersion,
        "challenge" to challenge,
        "rks" to rks,
        "gameVersion" to gameVersion,
        "avatar" to avatar,
        "EZ" to ratings.subList(0, 3),
        "HD" to ratings.subList(3, 6),
        "IN" to ratings.subList(6, 9),
        "AT" to ratings.subList(9, 12)
    )
}
