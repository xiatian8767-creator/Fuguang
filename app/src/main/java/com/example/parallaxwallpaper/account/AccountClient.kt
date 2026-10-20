package com.example.parallaxwallpaper.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.parallaxwallpaper.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AccountException(val status: Int, message: String) : Exception(message)

/** Only the entitlement request leaves this device; photos never enter this client. */
class AccountClient(context: Context) {
    private val prefs = context.getSharedPreferences("fuguang_account", Context.MODE_PRIVATE)
    private val alias = "fuguang_session_v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun saveToken(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("token", encoded).commit()) { "无法保存登录状态" }
    }
    private fun token(): String = try {
        val data = Base64.decode(prefs.getString("token", ""), Base64.NO_WRAP)
        if (data.size < 13) "" else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
        }
    } catch (_: Exception) { "" }
    fun hasSession() = token().isNotEmpty()
    private fun request(path: String, data: JSONObject? = null): JSONObject {
        val connection = URL(BuildConfig.ACCOUNT_BASE_URL + "/api/" + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000; connection.readTimeout = 20000
        connection.requestMethod = if (data == null) "GET" else "POST"
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Content-Type", "application/json")
        val session = token()
        if (session.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $session")
        try {
            if (data != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val result = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            if (code !in 200..299) {
                if (code == 401) prefs.edit().remove("token").commit()
                val detail = result.opt("detail")
                throw AccountException(code, if (detail is String) detail else "服务暂不可用，请稍后重试（$code）")
            }
            return result
        } finally { connection.disconnect() }
    }
    fun login(username: String, password: String, register: Boolean): JSONObject {
        val r = request(if (register) "register" else "login", JSONObject().put("username", username.trim()).put("password", password))
        saveToken(r.getString("token")); return r.getJSONObject("user")
    }
    fun me() = request("me")
    fun redeem(code: String) = request("redeem", JSONObject().put("code", code.trim()))
    fun logout() { request("logout", JSONObject()); prefs.edit().clear().commit() }
    fun reserve(id: String) = request("generations/reserve", JSONObject().put("id", id))
    fun commit(id: String) = request("generations/$id/commit", JSONObject())
    fun cancel(id: String) = request("generations/$id/cancel", JSONObject())
    fun campaign(): JSONObject? = request("campaigns?version=${BuildConfig.VERSION_CODE}").optJSONObject("campaign")
    fun displayed(id: String, revision: Int) = request("campaigns/$id/displayed", JSONObject().put("revision", revision))
}
