package com.phigros.attacker

import okhttp3.*
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*
import kotlin.collections.Map

class PigeonRequest(
    private val sessionToken: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val headers: Map<String, String> = mapOf(
        "X-LC-Session" to sessionToken,
        "X-LC-Id" to "rAK3FfdieFob2Nn8Am",
        "X-LC-Key" to "Qr9AEqtuoSVS3zeD6iVbM4ZC0AtkJcQ89tywVyi0",
        "User-Agent" to "LeanCloud-CSharp-SDK/1.0.3",
        "Accept" to "application/json",
        "Connection" to "keep-alive"
    )
) {

    suspend fun request(method: String, url: String, headers: Map<String, String>? = null, body: RequestBody? = null): Response {
        val finalHeaders = headers ?: this.headers
        val requestBuilder = Request.Builder().url(url)

        finalHeaders.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        // 设置请求方法
        when (method.uppercase()) {
            "GET" -> requestBuilder.get()
            "DELETE" -> requestBuilder.delete()
            "POST" -> requestBuilder.post(body ?: RequestBody.create(null, ""))
            "PUT" -> requestBuilder.put(body ?: RequestBody.create(null, ""))
            else -> throw IllegalArgumentException("Unsupported HTTP method: $method")
        }

        val request = requestBuilder.build()

        // 执行请求并返回响应
        val response = client.newCall(request).execute()

        // 日志输出
        Timber.d("请求类型: $method,\n请求URL: $url,\n请求头: $finalHeaders,\n状态码: ${response.code}",)

        if (!response.isSuccessful) {
            throw IOException("Unexpected code $response")
        }

        return response
    }

    suspend fun get(url: String, headers: Map<String, String>? = null): Response {
        return request("GET", url, headers)
    }

    suspend fun delete(url: String, headers: Map<String, String>? = null): Response {
        return request("DELETE", url, headers)
    }

    suspend fun post(url: String, body: RequestBody, headers: Map<String, String>? = null): Response {
        return request("POST", url, headers, body)
    }

    suspend fun put(url: String, body: RequestBody, headers: Map<String, String>? = null): Response {
        return request("PUT", url, headers, body)
    }
}

class PhigrosCloud(
    private val sessionToken: String,
    private val client: OkHttpClient? = null
) {

    private val createClient: Boolean
    private val request: PigeonRequest
    private val baseUrl = "https://rak3ffdi.cloud.tds1.tapapis.cn/1.1/"

    init {
        createClient = client == null
        this.request = PigeonRequest(sessionToken, client ?: OkHttpClient())
    }

    // 获取玩家 summary 数据
    var isRequestInProgress = false
    suspend fun getSummary(): Map<String, Any>? {
        Timber.d("调用函数：getSummary()")
        val response = request.get(baseUrl + "classes/_GameSave?limit=1")
        val jsonObject = JSONObject(response.body?.string() ?: "")
        val result = jsonObject.getJSONArray("results").getJSONObject(0)
        val summaryData = Base64.getDecoder().decode(result.getString("summary"))

        // 解包 summary 数据
        val summary = unpackSummary(summaryData)

        val returnData = mapOf(
            "checksum" to result.getJSONObject("gameFile").getJSONObject("metaData").getString("_checksum"),
            "updateAt" to result.getString("updatedAt"),
            "url" to result.getJSONObject("gameFile").getString("url"),
            "saveVersion" to summary[0],
            "challenge" to summary[1],
            "rks" to summary[2],
            "gameVersion" to summary[3],
            "avatar" to String(summary[4] as ByteArray),
            "EZ" to summary.slice(5..7),
            "HD" to summary.slice(8..10),
            "IN" to summary.slice(11..13),
            "AT" to summary.slice(14..16)
        )

        Timber.d("函数\"getSummary()\"返回：$returnData")
        return returnData
    }
    suspend fun getSave(): JSONObject? {
        if (isRequestInProgress) return null
        isRequestInProgress = true
        return try {
            // 执行 GET 请求获取存档数据
            val response = request.get(baseUrl + "classes/_GameSave?limit=1")

            // 只读取一次响应体
            val responseBody = response.body?.string()

            // 如果响应体不为空
            if (responseBody != null) {
                val jsonObject = JSONObject(responseBody)
                val results = jsonObject.getJSONArray("results")

                // 如果 results 数组不为空，返回第一个元素
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

        } catch (e: Exception) {
            Timber.e(e, "获取存档失败")
            null
        } finally {
            isRequestInProgress = false
        }
    }
    // 解包 summary 数据
    private fun unpackSummary(summaryData: ByteArray): List<Any> {
        val buffer = ByteBuffer.wrap(summaryData)

        // 提取不同类型的数据，基于 Python 中 unpack 的模式
        val saveVersion = buffer.short.toInt() // =B
        val challenge = buffer.short.toInt()  // =H
        val rks = buffer.float // =f
        val gameVersion = buffer.float // =f
        val avatar = ByteArray(buffer.get().toInt())  // =x%s 处理字节数组
        buffer.get(avatar) // 读取头像数据

        // 创建一个 List 返回相应数据
        val result = mutableListOf<Any>()
        result.add(saveVersion)
        result.add(challenge)
        result.add(rks)
        result.add(gameVersion)
        result.add(avatar)

        // 接下来的数据将按照题目中的要求进行解包
        result.add(buffer.short) // EZ难度评级
        result.add(buffer.short) // HD难度评级
        result.add(buffer.short) // IN难度评级
        result.add(buffer.short) // AT难度评级

        return result
    }
}
