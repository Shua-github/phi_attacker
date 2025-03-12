package com.phigros.attacker

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.RemoteException
import android.util.Base64
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import timber.log.Timber
import org.json.JSONObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class MainActivity : AppCompatActivity() {

    private var shizukuServiceState = false
    private lateinit var checkPermissionButton: Button
    private lateinit var readFileButton: Button
    private lateinit var connectShizukuButton: Button
    private lateinit var clearCacheButton: Button // 清理缓存按钮
    private lateinit var fileContentTextView: TextView
    private var iUserService: IUserService? = null
    private val encryptionKey = BuildConfig.OUT_ENCRYPTION_KEY

    private val cacheManager by lazy { CacheManager(cacheDir) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViews()
        addEvents()
        initShizuku()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }

    private fun initShizuku() {
        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            Toast.makeText(this, if (granted) "Shizuku授权成功" else "Shizuku授权失败", Toast.LENGTH_SHORT).show()
        }
        Shizuku.addBinderReceivedListenerSticky(onBinderReceivedListener)
        Shizuku.addBinderDeadListener(onBinderDeadListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(onBinderReceivedListener)
        Shizuku.removeBinderDeadListener(onBinderDeadListener)
        iUserService?.let {
            try {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            } catch (e: Exception) {
                Timber.e("MainActivity", "Shizuku unbindUserService failed", e)
            }
        }
    }

    private val onBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        shizukuServiceState = true
        Toast.makeText(this, "Shizuku服务已启动", Toast.LENGTH_SHORT).show()
    }

    private val onBinderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuServiceState = false
        iUserService = null
        Toast.makeText(this, "Shizuku服务被终止", Toast.LENGTH_SHORT).show()
    }

    private fun updateFileContentTextView(displayText: String) {
        fileContentTextView.text = displayText
    }

    private fun addEvents() {
        Timber.plant(Timber.DebugTree())

        // 检查权限按钮点击事件
        checkPermissionButton.setOnClickListener {
            if (!shizukuServiceState) {
                Toast.makeText(this, "Shizuku服务状态异常", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, if (checkPermission()) "已拥有权限" else "未拥有权限", Toast.LENGTH_SHORT).show()
        }

        // 读取文件按钮点击事件
        readFileButton.setOnClickListener {
            lifecycleScope.launch {
                // 处理缓存情况
                val displayText = try {
                    if (cacheManager.isCache()) {
                        getDisplayText()
                    } else {
                        if (iUserService == null) {
                            Toast.makeText(this@MainActivity, "请先连接Shizuku服务", Toast.LENGTH_SHORT).show()
                            return@launch // 如果没有连接服务，直接返回
                        } else {
                            getDisplayText() // 正常情况下获取显示文本
                        }
                    }
                } catch (e: Exception) {
                    updateFileContentTextView("Phigros未安装或未登录云存档")
                    e.printStackTrace()
                    return@launch // 异常时直接返回
                }

                // 更新 UI
                updateFileContentTextView(displayText.trim())
            }
        }

        // 连接 Shizuku 服务按钮点击事件
        connectShizukuButton.setOnClickListener {
            if (!shizukuServiceState) {
                Toast.makeText(this, "Shizuku服务状态异常", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (iUserService == null) {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
            }
        }

        // 清理缓存按钮点击事件
        clearCacheButton.setOnClickListener {
            lifecycleScope.launch {
                clearCache()
            }
        }
    }

    private fun clearCache() {
        try {
            cacheManager.clearCache() // 调用 CacheManager 的 clearCache 方法
            Toast.makeText(this, "缓存已清理", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "清理缓存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private suspend fun getDisplayText(): String {
        val tokenFile = cacheManager.getTokenFile()
        val savesFile = cacheManager.getSavesFile()
        val savesBase64File = cacheManager.getSavesBase64File()

        val sessionToken: String  // 初始为空字符串
        val savesURL: String      // 初始为空字符串
        val encryptToken: String  // 初始为空字符串
        val savesSummary: Map<String, Any>  // 初始为空Map
        val savesBase64: String

        // 提取一个统一的函数来处理从 JSON 中获取 URL 和 summary
        fun extractSavesData(savesJSON: JSONObject): Pair<String, Map<String, Any>> {
            val url = savesJSON.getJSONObject("gameFile").getString("url").trim()
            val summary = getSummary(savesJSON.getString("summary"))
            return Pair(url, summary)
        }

        // 获取token逻辑
        if (tokenFile.exists() && savesFile.exists() && savesBase64File.exists()) {
            // 读取缓存
            encryptToken = tokenFile.readText()
            val savesJSON = JSONObject(savesFile.readText())
            val (url, summary) = extractSavesData(savesJSON)
            savesURL = url
            savesSummary = summary
            sessionToken = decrypt(encryptToken, key = encryptionKey).trim()
            savesBase64 = savesBase64File.readText()
        } else {
            // 获取token
            val result = exec("cat ${Environment.getExternalStorageDirectory().path}/Android/data/com.PigeonGames.Phigros/files/.userdata").trim()
            if (result.isEmpty()) throw Exception("返回结果为空")

            val resultMap: Map<String, Any> = Gson().fromJson(result, object : TypeToken<Map<String, Any>>() {}.type)
            sessionToken = (resultMap["sessionToken"] as? String).orEmpty().trim()

            encryptToken = encrypt(sessionToken, encryptionKey).trim()
            cacheManager.cacheToken(encryptToken)

            // 获得云存档
            val phigrosCloud = PhigrosCloud(sessionToken = sessionToken)
            val saveData = withContext(Dispatchers.IO) { phigrosCloud.getSave() }

            if (saveData != null) {
                val (url, summary) = extractSavesData(saveData)
                savesURL = url
                savesSummary = summary
                cacheManager.cacheSaves(saveData.toString().trim())
                savesBase64 = withContext(Dispatchers.IO) { phigrosCloud.getSaveFileAsBase64(savesURL).toString() }
                cacheManager.cacheSavesBase64(savesBase64)
            } else {
                throw Exception("存档数据为空")
            }
        }

        fun formatSaveData(data: List<Any>): String {
            return data.take(3).mapIndexed { index, value ->
                val strValue = value.toString()
                when (index) {
                    0 -> "Played: $strValue"
                    1 -> "FC: $strValue"
                    2 -> "AP: $strValue"
                    else -> strValue
                }
            }.joinToString(", ")
        }

        // 定义一个包含所有难度的列表
        val difficultyList = listOf("EZ", "HD", "IN", "AT")

        // 使用字典 (Map) 来存储每个难度的格式化数据
        val difficultyDataMap = difficultyList.associateWith { difficulty ->
            val data = savesSummary[difficulty] as? List<Any> ?: return@associateWith "No data available"
            formatSaveData(data)
        }

        // 通过循环生成难度相关的输出，而不是直接使用难度名称
        val formattedDifficultyData = difficultyList.joinToString("\n") { difficulty ->
            "$difficulty: ${difficultyDataMap[difficulty].toString().trim()}"
        }

        val test = callPythonGetSaves(savesBase64)

        return """
        |卡密: ${encryptToken.trim()}
        |存档URL: ${savesURL.trim()}
        |rks: ${savesSummary["rks"].toString().trim()}
        |$formattedDifficultyData
        |test:${test}
        """.trimMargin("|")
    }


    @Throws(RemoteException::class)
    private suspend fun exec(command: String): String {
        return withContext(Dispatchers.IO) {
            if (command.contains("\"")) {
                iUserService?.execArr(command.split(" ").toTypedArray()) ?: ""
            } else {
                iUserService?.execLine(command) ?: ""
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            iUserService = IUserService.Stub.asInterface(service)
            Toast.makeText(this@MainActivity, "Shizuku服务连接成功", Toast.LENGTH_SHORT).show()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            iUserService = null
            Toast.makeText(this@MainActivity, "Shizuku服务连接断开", Toast.LENGTH_SHORT).show()
        }
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, UserService::class.java.name))
        .daemon(false)
        .processNameSuffix("adb_service")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private fun checkPermission(): Boolean {
        return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }

    private fun findViews() {
        checkPermissionButton = findViewById(R.id.check_permission_button)
        readFileButton = findViewById(R.id.read_file_button)
        connectShizukuButton = findViewById(R.id.connect_shizuku_button)
        fileContentTextView = findViewById(R.id.file_content_text_view)
        clearCacheButton = findViewById(R.id.clear_cache_button) // 获取清理缓存按钮
    }

    companion object {
        @SuppressLint("GetInstance")
        @Throws(Exception::class)
        fun encrypt(data: String, key: String): String {
            if (key.isEmpty()) {
                return data // 如果 key 为空，直接返回原始数据
            }

            require(key.length == 16) { "AES 密钥长度必须为 16 字节" }
            val secretKey = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val encryptedData = cipher.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            return Base64.encodeToString(encryptedData, Base64.DEFAULT)
        }

        @SuppressLint("GetInstance")
        fun decrypt(data: String, key: String): String {
            if (key.isEmpty()) {
                return data // 如果 key 为空，直接返回原始数据
            }

            require(key.length == 16) { "AES 密钥长度必须为 16 字节" }
            val secretKey = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)

            val encryptedData = Base64.decode(data, Base64.DEFAULT)
            val decryptedData = cipher.doFinal(encryptedData)

            return String(decryptedData, StandardCharsets.UTF_8)
        }
    }


    class CacheManager(private val cacheDir: File) {

        fun getTokenFile(): File {
            return File(cacheDir, "cached_token.txt")
        }

        fun getSavesFile(): File {
            return File(cacheDir, "cached_saves.json")
        }

        // 获取 cached_saves_base64.txt 文件
        fun getSavesBase64File(): File {
            return File(cacheDir, "cached_saves_base64.txt")
        }

        fun isCache(): Boolean {
            return getTokenFile().exists() && getSavesFile().exists()
        }

        fun cacheToken(encryptedToken: String) {
            val tokenFile = getTokenFile()
            tokenFile.writeText(encryptedToken)
        }

        fun cacheSaves(saveData: String) {
            val savesFile = getSavesFile()
            savesFile.writeText(saveData)
        }

        // 使用 android.util.Base64 缓存 Base64 编码的存档数据
        fun cacheSavesBase64(saveData: String) {
            val savesBase64File = getSavesBase64File()
            savesBase64File.writeText(saveData)
        }

        // 清除缓存
        fun clearCache() {
            val tokenFile = getTokenFile()
            val savesFile = getSavesFile()
            val savesBase64File = getSavesBase64File()

            if (tokenFile.exists()) {
                tokenFile.delete()
            }
            if (savesFile.exists()) {
                savesFile.delete()
            }
            if (savesBase64File.exists()) {
                savesBase64File.delete()
            }
        }
    }
}
