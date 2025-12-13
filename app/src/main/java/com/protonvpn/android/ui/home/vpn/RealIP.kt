package com.protonvpn.android.ui.home.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Proxy

data class UserLocation(
    val ipAddress: String,
    val country: String,
    val isp: String,
    val latitude: Float,
    val longitude: Float
)

/**
 * Получает реальное местоположение пользователя по IP-адресу, в обход любых прокси и VPN.
 *
 * @return Объект [UserLocation] с данными о местоположении или null в случае ошибки.
 */
suspend fun fetchRealLocation(): UserLocation? = withContext(Dispatchers.IO) {
    try {
        // Создаем клиент OkHttp, который игнорирует системные прокси/VPN
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .build()

        // Запрос к сервису геолокации
        val request = Request.Builder()
            .url("https://ipwho.is/")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    val json = JSONObject(body)
                    val connection = json.optJSONObject("connection")

                    val ip = json.optString("ip", "")
                    val countryCode = json.optString("country_code", "")
                    val isp = connection?.optString("isp", "Unknown ISP") ?: "Unknown ISP"
                    val lat = json.optDouble("latitude").toFloat()
                    val lon = json.optDouble("longitude").toFloat()

                    // Используем двухбуквенный код страны, если он доступен
                    if (countryCode.isNotEmpty()) {
                        return@withContext UserLocation(
                            ipAddress = ip,
                            country = countryCode,
                            isp = isp,
                            latitude = lat,
                            longitude = lon
                        )
                    }
                }
            }
        }
    } catch (e: Exception) {
        // В случае ошибки выводим ее в лог для отладки
        e.printStackTrace()
    }
    null
}

/**
 * Получает реальный IP-адрес пользователя в обход прокси и VPN.
 * @return Строку с IP-адресом или null в случае ошибки.
 */
suspend fun fetchRealIp(): String? = withContext(Dispatchers.IO) {
    try {
        // Клиент без системного прокси/туннеля
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .build()

        val request = Request.Builder()
            .url("https://api.ipify.org?format=json")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    return@withContext JSONObject(body).getString("ip")
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    null
}
