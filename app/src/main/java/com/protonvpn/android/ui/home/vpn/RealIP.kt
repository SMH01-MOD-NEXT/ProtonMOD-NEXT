package com.protonvpn.android.ui.home.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.Proxy
import java.util.Locale

data class UserLocation(
    val ipAddress: String,
    val country: String,
    val isp: String,
    val latitude: Float,
    val longitude: Float
)

/**
 * Получает реальное местоположение пользователя по IP-адресу, в обход любых прокси и VPN.
 * Также определяет язык системы и получает название страны на этом языке.
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
                    val rawCountryName = json.optString("country", "Unknown")
                    val countryCode = json.optString("country_code", "")
                    val isp = connection?.optString("isp", "Unknown ISP") ?: "Unknown ISP"
                    val lat = json.optDouble("latitude").toFloat()
                    val lon = json.optDouble("longitude").toFloat()

                    // --- НОВЫЙ НАДЁЖНЫЙ МЕТОД ПОЛУЧЕНИЯ НАЗВАНИЯ СТРАНЫ ---
                    // Получаем локализованное название страны из её кода (например, "RU" -> "Россия").
                    // Это работает без внешних сервисов перевода.
                    val countryName = if (countryCode.isNotEmpty()) {
                        try {
                            val sourceLocale = Locale("", countryCode) // Локаль на основе кода страны
                            val targetLocale = Locale.getDefault()     // Локаль системы пользователя
                            sourceLocale.getDisplayCountry(targetLocale)
                        } catch (e: Exception) {
                            rawCountryName // В случае ошибки возвращаем английское название
                        }
                    } else {
                        rawCountryName // Если код страны не пришёл, используем английское название
                    }


                    // Форматируем название страны: первая буква каждого слова заглавная, остальные строчные.
                    // Например: "UNITED KINGDOM" -> "United Kingdom", "RUSSIA" -> "Russia"
                    val formattedCountry = countryName.split(" ").joinToString(" ") { word ->
                        word.lowercase(Locale.getDefault()).replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                        }
                    }

                    return@withContext UserLocation(
                        ipAddress = ip,
                        country = formattedCountry,
                        isp = isp,
                        latitude = lat,
                        longitude = lon
                    )
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

