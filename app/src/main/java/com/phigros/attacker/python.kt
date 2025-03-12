package com.phigros.attacker
import com.chaquo.python.PyException
import com.chaquo.python.Python

fun callPythonGetSaves(zipBase64: String): String {
    val py = Python.getInstance()
    val module = py.getModule("simplify_pca")  // 导入指定的 Python 模块

    return try {
        // 调用 Python 函数并传递 zip_base64 参数
        val result = module.callAttr("get_saves", zipBase64).toString()  // 将 zipBase64 作为参数传递
        result  // 返回值是字符串类型
    } catch (e: PyException) {
        e.message ?: "发生错误"
    }
}
