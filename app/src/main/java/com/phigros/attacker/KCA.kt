package com.phigros.attacker

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException

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
        Timber.d("请求类型: $method")
        Timber.d("请求URL: $url")
        Timber.d("请求头: $finalHeaders")
        Timber.d("状态码: ${response.code}")

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

    // 异步删除保存
    suspend fun deleteSave() {
        try {
            // 在后台线程中执行网络请求
            val response = request.get(baseUrl + "classes/_GameSave?limit=1")

            // 只读取一次响应体并存储
            val responseBody = response.body?.string()

            // 如果响应体不为空
            if (responseBody != null) {
                val jsonObject = JSONObject(responseBody)
                val results = jsonObject.getJSONArray("results")

                // 检查 results 数组是否为空
                if (results.length() > 0) {
                    // 获取 objectId
                    val objectId = results.getJSONObject(0).getString("objectId")

                    // 删除存档
                    objectId?.let {
                        request.delete(baseUrl + "files/$it")
                    }
                } else {
                    Timber.e("没有找到保存数据，results 数组为空$responseBody")
                }
            } else {
                Timber.e("响应体为空")
            }

        } catch (e: Exception) {
            Timber.e(e, "删除保存失败")
        }
    }

    // 异步获取存档数据
    suspend fun getSave(): JSONObject? {
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
        }
    }

}
