/*
 *
 *  * Copyright (c) 2025. Proton AG
 *  *
 *  * This file is part of ProtonVPN.
 *  *
 *  * ProtonVPN is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * ProtonVPN is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with ProtonVPN.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.protonvpn.android.proxy

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import kotlin.system.measureTimeMillis

/**
 * Manages the lifecycle of the Xray binary and the local SOCKS5 proxy.
 */
class VlessManager private constructor(private val context: Context) {

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var xrayProcess: Process? = null
    private var outReaderJob: Job? = null
    private var errReaderJob: Job? = null

    // --- State Properties ---
    @Volatile private var isRunning = false
    @Volatile private var isReady = false
    @Volatile private var bestServerHost: String? = null
    @Volatile private var errorMessage: String? = null

    private val xrayBinary: File by lazy {
        File(context.applicationInfo.nativeLibraryDir, XRAY_BINARY_NAME)
    }

    fun start() {
        Log.d(TAG, "Start command received.")
        if (isRunning) {
            Log.w(TAG, "Manager is already running or starting. Command ignored.")
            return
        }

        coroutineScope.launch {
            try {
                isRunning = true
                isReady = false
                errorMessage = null
                bestServerHost = null
                Log.d(TAG, "State -> Starting. Checking for Xray binary...")

                if (!xrayBinary.exists() || !xrayBinary.canExecute()) {
                    throw Exception("Xray binary not found or not executable at: ${xrayBinary.absolutePath}")
                }
                Log.d(TAG, "Binary found and executable: ${xrayBinary.absolutePath}")

                Log.d(TAG, "State -> Pinging. Fetching and decoding configs...")
                val configs = fetchAndDecodeConfigs()
                val bestServer = findBestServer(configs)
                    ?: throw Exception("No responsive VLESS servers found after pinging.")

                val host = bestServer.extractHost()
                Log.d(TAG, "Best server: ${bestServer.optString("ps", "Unnamed")} ($host)")

                // Remove routing/dns blocks to avoid geoip/geosite errors
                bestServer.remove("routing")
                bestServer.remove("dns")

                // Patch TLS SNI if missing
                val outbound = bestServer.getJSONArray("outbounds").getJSONObject(0)
                outbound.optJSONObject("streamSettings")?.let { streamSettings ->
                    if (streamSettings.optString("security") == "tls") {
                        val tlsSettings = streamSettings.optJSONObject("tlsSettings")
                        if (tlsSettings != null && !tlsSettings.has("serverName")) {
                            val sni = streamSettings.optJSONObject("wsSettings")?.optString("host")
                                ?: bestServer.optString("sni", "")
                            if (sni.isNotEmpty()) {
                                tlsSettings.put("serverName", sni)
                                Log.d(TAG, "Patched missing SNI with: $sni")
                            }
                        }
                    }
                }

                val configFile = File(context.filesDir, "config.json")
                configFile.writeText(bestServer.toString(4))
                Log.d(TAG, "Config file created. Starting Xray process...")

                startXray()

                waitForProxyReady()
                Log.d(TAG, "Local proxy on port $PROXY_PORT confirmed ready.")

                if (testProxyConnection()) {
                    delay(100)
                    isReady = true
                    bestServerHost = host
                    Log.d(TAG, "State -> Ready. Xray started successfully.")
                } else {
                    throw Exception("Proxy connection test failed after setup.")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in VlessManager", e)
                errorMessage = e.message ?: "Unknown error"
                stop()
            }
        }
    }

    fun stop() {
        Log.d(TAG, "Stop command received.")

        // Cancel readers first
        outReaderJob?.cancel()
        errReaderJob?.cancel()
        runBlocking {
            try { outReaderJob?.join() } catch (_: Exception) {}
            try { errReaderJob?.join() } catch (_: Exception) {}
        }
        outReaderJob = null
        errReaderJob = null

        // Stop process
        xrayProcess?.let { p ->
            try {
                p.destroy()
                if (!p.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping Xray: ${e.message}")
            }
        }
        xrayProcess = null

        isRunning = false
        isReady = false
        bestServerHost = null
        Log.d(TAG, "State -> Stopped.")
    }

    fun destroy() {
        Log.d(TAG, "Destroying VlessManager instance.")
        stop()
        coroutineScope.cancel()
    }

    // --- Public State Checkers ---
    fun isRunning(): Boolean = isRunning
    fun isReady(): Boolean = isReady
    fun getBestServerHost(): String? = bestServerHost
    fun getErrorMessage(): String? = errorMessage

    private suspend fun testProxyConnection(): Boolean = withContext(Dispatchers.IO) {
        val socksProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", PROXY_PORT))
        val client = OkHttpClient.Builder()
            .proxy(socksProxy)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url("https://api.ipify.org").build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Proxy test SUCCESS! IP: ${response.body?.string()}")
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Proxy test FAILED", e)
        }
        false
    }

    private suspend fun fetchAndDecodeConfigs(): List<JSONObject> = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val response = client.newCall(Request.Builder().url(GITHUB_CONFIG_URL).build()).execute()
        if (!response.isSuccessful) throw Exception("Failed to download configs. Code: ${response.code}")

        val body = response.body?.string() ?: throw Exception("Empty response body")
        body.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                try {
                    val decoded = Base64.decode(trimmed, Base64.DEFAULT)
                    JSONObject(String(decoded, Charsets.UTF_8))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode config line", e)
                    null
                }
            } else null
        }
    }

    private suspend fun findBestServer(configs: List<JSONObject>): JSONObject? = withContext(Dispatchers.IO) {
        if (configs.isEmpty()) return@withContext null
        val results = configs.map { config ->
            async {
                var ping = PING_TIMEOUT_MS + 1L
                try {
                    val host = config.extractHost()
                    val port = config.extractPort()
                    ping = measureTimeMillis {
                        Socket().use { it.connect(InetSocketAddress(host, port), PING_TIMEOUT_MS) }
                    }
                    Log.d(TAG, "Ping ${config.optString("ps", host)}: $ping ms")
                } catch (_: Exception) {}
                config to ping
            }
        }.awaitAll()
        results.filter { it.second <= PING_TIMEOUT_MS }.minByOrNull { it.second }?.first
    }
    private fun startXray() {
        val configFile = File(context.filesDir, "config.json")
        if (!configFile.exists()) throw Exception("Xray config file not found")

        val command = listOf(xrayBinary.absolutePath, "run", "-c", configFile.absolutePath)
        xrayProcess = ProcessBuilder(command).start()

        // Чтение stdout
        outReaderJob = coroutineScope.launch {
            val reader = xrayProcess?.inputStream?.bufferedReader() ?: return@launch
            try {
                while (isActive) {
                    val line = reader.readLine() ?: break
                    Log.i(TAG, "[Xray-out] $line")
                }
            } catch (e: InterruptedIOException) {
                Log.i(TAG, "Xray stdout interrupted (normal on stop)")
            } catch (e: IOException) {
                Log.w(TAG, "Xray stdout error: ${e.message}")
            } finally {
                try { reader.close() } catch (_: Exception) {}
            }
        }

        // Чтение stderr
        errReaderJob = coroutineScope.launch {
            val reader = xrayProcess?.errorStream?.bufferedReader() ?: return@launch
            try {
                while (isActive) {
                    val line = reader.readLine() ?: break
                    Log.e(TAG, "[Xray-err] $line")
                }
            } catch (e: InterruptedIOException) {
                Log.i(TAG, "Xray stderr interrupted (normal on stop)")
            } catch (e: IOException) {
                Log.w(TAG, "Xray stderr error: ${e.message}")
            } finally {
                try { reader.close() } catch (_: Exception) {}
            }
        }

        Log.d(TAG, "Xray process started with command: ${command.joinToString(" ")}")
    }


    private suspend fun waitForProxyReady(
        timeoutMs: Long = 10_000L,
        intervalMs: Long = 200L
    ) = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        while (isActive && System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", PROXY_PORT), 100)
                }
                return@withContext
            } catch (_: Exception) {
                if (isActive) delay(intervalMs) else break
            }
        }
        throw IllegalStateException("Proxy on port $PROXY_PORT did not become available within ${timeoutMs}ms.")
    }


    private fun JSONObject.extractHost(): String = this.getJSONArray("outbounds").getJSONObject(0)
        .getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address")

    private fun JSONObject.extractPort(): Int = this.getJSONArray("outbounds").getJSONObject(0)
        .getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getInt("port")


    companion object {
        private const val TAG = "VLESS_MANAGER"
        private const val XRAY_BINARY_NAME = "libxray.so"
        private const val GITHUB_CONFIG_URL = "https://raw.githubusercontent.com/SMH01-MOD-NEXT/mod-next-proxy/refs/heads/main/vless.txt"
        private const val PING_TIMEOUT_MS = 2000
        const val PROXY_PORT = 10808

        @Volatile
        private var INSTANCE: VlessManager? = null

        fun getInstance(context: Context): VlessManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VlessManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
