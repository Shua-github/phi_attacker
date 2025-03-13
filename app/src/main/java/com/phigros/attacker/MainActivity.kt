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
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.File
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import com.tds.common.entities.TapConfig
import com.tapsdk.bootstrap.TapBootstrap
import com.tapsdk.bootstrap.account.TDSUser
import com.tapsdk.bootstrap.exceptions.TapError
import com.tds.common.models.TapRegionType
import com.tapsdk.bootstrap.Callback

class MainActivity : AppCompatActivity() {

    private var shizukuServiceState = false
    private lateinit var checkPermissionButton: Button
    private lateinit var readFileButton: Button
    private lateinit var connectShizukuButton: Button
    private lateinit var clearCacheButton: Button
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

        val tdsConfig = TapConfig.Builder()
            .withAppContext(this)
            .withClientId("rAK3FfdieFob2Nn8Am")
            .withClientToken("Qr9AEqtuoSVS3zeD6iVbM4ZC0AtkJcQ89tywVyi0")
            .withServerUrl("https://rak3ffdi.cloud.tds1.tapapis.cn/")
            .withRegionType(TapRegionType.CN)
            .build()

        TapBootstrap.init(this@MainActivity, tdsConfig)
    }

    private fun initShizuku() {
        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            showToast(if (grantResult == PackageManager.PERMISSION_GRANTED) "Shizuku授权成功" else "Shizuku授权失败")
        }
        Shizuku.addBinderReceivedListener(onBinderReceivedListener)
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
        showToast("Shizuku服务已启动")
    }

    private val onBinderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuServiceState = false
        iUserService = null
        showToast("Shizuku服务被终止")
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun updateFileContentTextView(displayText: String) {
        fileContentTextView.text = displayText
    }

    private fun addEvents() {
        Timber.plant(Timber.DebugTree())

        checkPermissionButton.setOnClickListener {
            if (!shizukuServiceState) {
                showToast("Shizuku服务状态异常")
                return@setOnClickListener
            }
            showToast(if (checkPermission()) "已拥有权限" else "未拥有权限")
        }

        readFileButton.setOnClickListener {
            lifecycleScope.launch { handleFileReading() }
        }

        connectShizukuButton.setOnClickListener {
            if (iUserService == null) {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
            }
        }

        clearCacheButton.setOnClickListener {
            lifecycleScope.launch { cacheManager.clearCache() }
        }

        findViewById<Button>(R.id.login_taptap_button).setOnClickListener {
            loginWithTapTap()
        }
    }

    private suspend fun handleFileReading() {
        try {
            val displayText = if (cacheManager.isCache()) {
                getDisplayText()
            } else {
                if (iUserService == null) {
                    showToast("请先连接Shizuku服务")
                    return
                } else {
                    getDisplayText()
                }
            }
            updateFileContentTextView(displayText.trim())
        } catch (e: Exception) {
            updateFileContentTextView("Phigros未安装或未登录云存档")
            Timber.e(e)
        }
    }

    private suspend fun getDisplayText(): String {
        val (sessionToken, encryptToken, savesURL, savesSummary, savesBase64) = fetchDataFromCacheOrNetwork()

        val formattedDifficultyData = formatDifficultyData(savesSummary)
        val pythonData = callPythonGetSaves(savesBase64)

        return """
            |卡密: ${encryptToken.trim()}
            |存档URL: ${savesURL.trim()}
            |rks: ${savesSummary["rks"].toString().trim()}
            |$formattedDifficultyData
            |test: $pythonData
        """.trimMargin("|")
    }

    private suspend fun fetchSaveData(sessionToken: String): SaveData {
        val encryptToken = encrypt(sessionToken, encryptionKey).trim()

        // 缓存加密后的 token
        cacheManager.cacheToken(encryptToken)

        val phigrosCloud = PhigrosCloud(sessionToken)

        // 获取保存数据
        val saveData = withContext(Dispatchers.IO) { phigrosCloud.getSave() }

        if (saveData != null) {
            val (url, summary) = extractSavesData(saveData)
            val savesBase64 = withContext(Dispatchers.IO) { phigrosCloud.getSaveFileAsBase64(url).toString() }

            // 缓存保存数据和 Base64 数据
            cacheManager.cacheSaves(saveData.toString().trim())
            cacheManager.cacheSavesBase64(savesBase64)

            return SaveData(sessionToken, encryptToken, url, summary, savesBase64)
        } else {
            throw Exception("存档数据为空")
        }
    }

    private suspend fun fetchDataFromCacheOrNetwork(): SaveData {
        val tokenFile = cacheManager.getTokenFile()
        val savesFile = cacheManager.getSavesFile()
        val savesBase64File = cacheManager.getSavesBase64File()

        return if (tokenFile.exists() && savesFile.exists() && savesBase64File.exists()) {
            // 从缓存读取数据
            val encryptToken = tokenFile.readText()
            val savesJSON = JSONObject(savesFile.readText())
            val (url, summary) = extractSavesData(savesJSON)
            val sessionToken = decrypt(encryptToken, key = encryptionKey).trim()
            val savesBase64 = savesBase64File.readText()

            SaveData(sessionToken, encryptToken, url, summary, savesBase64)
        } else {
            // 网络获取数据
            val result = getFiletext("${Environment.getExternalStorageDirectory().path}/Android/data/com.PigeonGames.Phigros/files/.userdata").trim()
            val resultMap: Map<String, Any> = Gson().fromJson(result, object : TypeToken<Map<String, Any>>() {}.type)
            val sessionToken = (resultMap["sessionToken"] as? String).orEmpty().trim()
            return fetchSaveData(sessionToken)
        }
    }

    private fun extractSavesData(savesJSON: JSONObject): Pair<String, Map<String, Any>> {
        val url = savesJSON.getJSONObject("gameFile").getString("url").trim()
        val summary = getSummary(savesJSON.getString("summary"))
        return Pair(url, summary)
    }

    private fun formatDifficultyData(savesSummary: Map<String, Any>): String {
        val difficultyList = listOf("EZ", "HD", "IN", "AT")
        return difficultyList.joinToString("\n") { difficulty ->
            val data = savesSummary[difficulty] as? List<Any>  // 安全转换
            if (data != null) {
                "$difficulty: ${formatSaveData(data)}"
            } else {
                "$difficulty: No data"
            }
        }
    }

    private fun formatSaveData(data: List<Any>): String {
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


    @Throws(RemoteException::class)
    private suspend fun getFiletext(filePath: String): String {
        return withContext(Dispatchers.IO) {
            iUserService?.getFileText(filePath).toString()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            iUserService = IUserService.Stub.asInterface(service)
            showToast("Shizuku服务连接成功")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            iUserService = null
            showToast("Shizuku服务连接断开")
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
        clearCacheButton = findViewById(R.id.clear_cache_button)
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
        fun getTokenFile() = File(cacheDir, "cached_token.txt")
        fun getSavesFile() = File(cacheDir, "cached_saves.json")
        fun getSavesBase64File() = File(cacheDir, "cached_saves_base64.txt")

        fun isCache() = getTokenFile().exists() && getSavesFile().exists() && getSavesBase64File().exists()

        fun cacheToken(encryptedToken: String) {
            getTokenFile().writeText(encryptedToken)
        }

        fun cacheSaves(saveData: String) {
            getSavesFile().writeText(saveData)
        }

        fun cacheSavesBase64(saveData: String) {
            getSavesBase64File().writeText(saveData)
        }

        fun clearCache() {
            getTokenFile().delete()
            getSavesFile().delete()
            getSavesBase64File().delete()
        }
    }

    data class SaveData(
        val sessionToken: String,
        val encryptToken: String,
        val savesURL: String,
        val savesSummary: Map<String, Any>,
        val savesBase64: String
    )

    private fun loginWithTapTap() {
        TDSUser.loginWithTapTap(this, object : Callback<TDSUser> {
            override fun onSuccess(resultUser: TDSUser) {
                Toast.makeText(this@MainActivity, "成功登录 Taptap.", Toast.LENGTH_SHORT).show()

                Timber.d("User Info: $resultUser")
                val usertoken = resultUser.getString("sessionToken")
                lifecycleScope.launch {
                    fetchSaveData(usertoken)
                }
                val userId = resultUser.objectId
                val avatar = resultUser.getString("avatar")
                val nickName = resultUser.getString("nickname")

                Timber.d("User ID: $userId, Avatar: $avatar, Nickname: $nickName")
            }

            override fun onFail(error: TapError) {
                Toast.makeText(this@MainActivity, error.message, Toast.LENGTH_SHORT).show()
            }
        }, "public_profile")
    }

}
