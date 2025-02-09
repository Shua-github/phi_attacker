package com.phigros.attacker

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.RemoteException
import android.util.Base64
import android.util.Log
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

class MainActivity : AppCompatActivity() {

    private val PERMISSION_CODE = 10001
    private var shizukuServiceState = false
    private lateinit var checkPermissionButton: Button
    private lateinit var readFileButton: Button
    private lateinit var connectShizukuButton: Button
    private lateinit var fileContentTextView: TextView
    private var iUserService: IUserService? = null
    private val encryptionKey = "bYQ8t5agSkfPCiLa"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViews()
        addEvents()
        initShizuku()
    }

    private fun getCachedTokenFile(): File {
        return File(cacheDir, "cached_token.txt")
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
                Log.e("MainActivity", "Shizuku unbindUserService failed", e)
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
                val tokenFile = getCachedTokenFile()

                if (tokenFile.exists()) {
                    val encrypttoken = tokenFile.readText()
                    val sessionToken = decrypt(encrypttoken, key = encryptionKey)
                    val phigrosCloud = PhigrosCloud(sessionToken = sessionToken)

                    // 异步获取存档数据
                    val saveData = withContext(Dispatchers.IO) {
                        phigrosCloud.getSave()
                    }

                    // 检查获取的数据
                    if (saveData != null) {
                        fileContentTextView.text = "卡密: $encrypttoken,存档URL: ${saveData.getJSONObject("gameFile").getString("url")}"
                    } else {
                        fileContentTextView.text = "卡密: $encrypttoken, 没有找到存档数据"
                    }

                    return@launch
                }

                if (iUserService == null) {
                    Toast.makeText(this@MainActivity, "请先连接Shizuku服务", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                try {
                    // 获取 Phigros 配置文件的 sessionToken
                    val command = "cat ${Environment.getExternalStorageDirectory().getPath()}/Android/data/com.PigeonGames.Phigros/files/.userdata"
                    val result = exec(command)

                    if (result.trim().isEmpty()) throw Exception("返回结果为空")

                    val resultMap: Map<String, Any>? = Gson().fromJson(result, object : TypeToken<Map<String, Any>>() {}.type)
                    val sessionToken = resultMap?.get("sessionToken") as? String
                    if (sessionToken == null) throw Exception("未找到 sessionToken")

                    val encryptedToken = encrypt(sessionToken, encryptionKey)
                    tokenFile.writeText(encryptedToken)
                    fileContentTextView.text = "卡密: $encryptedToken"

                } catch (e: Exception) {
                    fileContentTextView.text = "Phigros未安装或未登录云存档"
                    e.printStackTrace()
                }
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
    }

    companion object {
        @Throws(Exception::class)
        fun encrypt(data: String, key: String): String {
            require(key.length == 16) { "AES 密钥长度必须为 16 字节" }
            val secretKey = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "AES")
            val cipher = Cipher.getInstance("AES")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val encryptedData = cipher.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            return Base64.encodeToString(encryptedData, Base64.DEFAULT)
        }

        fun decrypt(data: String, key: String): String {
            require(key.length == 16) { "AES 密钥长度必须为 16 字节" }
            val secretKey = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "AES")
            val cipher = Cipher.getInstance("AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)

            // 先将 Base64 编码的数据解码为字节数组
            val encryptedData = Base64.decode(data, Base64.DEFAULT)

            // 执行解密
            val decryptedData = cipher.doFinal(encryptedData)

            // 将解密后的字节数组转换为字符串并返回
            return String(decryptedData, StandardCharsets.UTF_8)
        }
    }
}
