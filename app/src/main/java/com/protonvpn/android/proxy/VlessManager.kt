package com.protonvpn.android.proxy

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
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
                // Set initial state flags
                isRunning = true
                isReady = false
                errorMessage = null
                bestServerHost = null
                Log.d(TAG, "State -> Starting. Checking for Xray binary...")

                if (!xrayBinary.exists() || !xrayBinary.canExecute()) {
                    throw Exception("Xray binary not found or not executable at: ${xrayBinary.absolutePath}. Ensure it is included in jniLibs.")
                }
                Log.d(TAG, "Binary found and executable: ${xrayBinary.absolutePath}")

                Log.d(TAG, "State -> Pinging. Fetching and decoding configs...")
                val configs = fetchAndDecodeConfigs()
                Log.d(TAG, "Found ${configs.size} configurations. Finding the best server...")

                val bestServer = findBestServer(configs)
                    ?: throw Exception("No responsive VLESS servers found after pinging.")

                val host = bestServer.extractHost()
                Log.d(TAG, "Found best server: ${bestServer.optString("ps", "Unnamed")} with host $host")

                // [FIX] Remove both routing and dns blocks to prevent crashes without geoip/geosite files.
                if (bestServer.has("routing")) {
                    bestServer.remove("routing")
                    Log.d(TAG, "'routing' block removed to avoid geoip/geosite errors.")
                }
                if (bestServer.has("dns")) {
                    bestServer.remove("dns")
                    Log.d(TAG, "'dns' block removed to avoid geoip/geosite errors.")
                }

                val outbound = bestServer.getJSONArray("outbounds").getJSONObject(0)
                if (outbound.has("streamSettings")) {
                    val streamSettings = outbound.getJSONObject("streamSettings")
                    if (streamSettings.optString("security") == "tls" && streamSettings.has("tlsSettings")) {
                        val tlsSettings = streamSettings.getJSONObject("tlsSettings")
                        if (!tlsSettings.has("serverName")) {
                            val sni = streamSettings.optJSONObject("wsSettings")?.optString("host")
                                ?: bestServer.optString("sni", "")
                            if (sni.isNotEmpty()) {
                                tlsSettings.put("serverName", sni)
                                Log.d(TAG, "Patched missing 'serverName' (SNI) with: $sni")
                            } else {
                                Log.w(TAG, "TLS is enabled, but 'serverName' (SNI) is missing and could not be inferred.")
                            }
                        }
                    }
                }

                Log.d(TAG, "Selected server config: ${bestServer.toString(2)}")

                val configFile = File(context.filesDir, "config.json")
                configFile.writeText(bestServer.toString(4))
                Log.d(TAG, "Config file created. Starting Xray process...")

                startXray()

                waitForProxyReady()
                Log.d(TAG, "Local proxy on port $PROXY_PORT confirmed to be ready.")

                if (testProxyConnection()) {
                    delay(100) // User-requested delay
                    isReady = true
                    bestServerHost = host
                    Log.d(TAG, "State -> Ready. Xray process started and tested successfully.")
                } else {
                    throw Exception("Proxy connection test failed after setup.")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in VlessManager", e)
                errorMessage = e.message ?: "Unknown error in VlessManager"
                stop() // Ensure cleanup on failure
            }
        }
    }

    fun stop() {
        Log.d(TAG, "Stop command received.")
        xrayProcess?.destroyForcibly()
        xrayProcess = null
        isRunning = false
        isReady = false
        bestServerHost = null
        // Error message is preserved until next start for inspection
        Log.d(TAG, "State -> Stopped. Xray process has been terminated.")
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
        Log.d(TAG, "Initiating proxy connection test...")

        val socksProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", PROXY_PORT))
        val client = OkHttpClient.Builder()
            .proxy(socksProxy)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("https://api.ipify.org")
            .build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val ip = response.body?.string()
                Log.d(TAG, "Proxy test SUCCESS! External IP: $ip")
                return@withContext true
            } else {
                Log.e(TAG, "Proxy test FAILED! Response code: ${response.code}, message: ${response.message}")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Proxy test FAILED with an exception.", e)
            return@withContext false
        }
    }

    private suspend fun fetchAndDecodeConfigs(): List<JSONObject> = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val request = Request.Builder().url(GITHUB_CONFIG_URL).build()
        Log.d(TAG, "Fetching configs from: $GITHUB_CONFIG_URL")

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("Failed to download configs from GitHub. Code: ${response.code}")

        val responseBody = response.body?.string() ?: throw Exception("Empty response body from GitHub")
        Log.d(TAG, "Configs downloaded, decoding and parsing JSON configs...")

        responseBody.lines().mapNotNull { base64Line ->
            val trimmedLine = base64Line.trim()
            if (trimmedLine.isNotEmpty()) {
                try {
                    val decodedBytes = Base64.decode(trimmedLine, Base64.DEFAULT)
                    val jsonString = String(decodedBytes, Charsets.UTF_8)
                    JSONObject(jsonString)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode/parse JSON from Base64 line: $trimmedLine", e)
                    null
                }
            } else {
                null
            }
        }
    }

    private suspend fun findBestServer(configs: List<JSONObject>): JSONObject? = withContext(Dispatchers.IO) {
        if (configs.isEmpty()) {
            return@withContext null
        }
        Log.d(TAG, "Pinging ${configs.size} servers in parallel...")

        val pingResults = configs.map { config ->
            async {
                var pingTime = PING_TIMEOUT_MS + 1L
                val serverName = config.optString("ps", "Unnamed")

                try {
                    val host = config.extractHost()
                    val port = config.extractPort()

                    if (coroutineContext.isActive) {
                        pingTime = measureTimeMillis {
                            Socket().use { socket ->
                                socket.connect(InetSocketAddress(host, port), PING_TIMEOUT_MS)
                            }
                        }
                        Log.d(TAG, "Ping for '$serverName' ($host): $pingTime ms")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not extract address/port or ping server '$serverName'")
                }
                config to pingTime
            }
        }.awaitAll()

        val bestResult = pingResults.filter { it.second <= PING_TIMEOUT_MS }.minByOrNull { it.second }
        Log.d(TAG, "Ping complete. Best server: ${bestResult?.first?.optString("ps") ?: "not found"}")
        bestResult?.first
    }

    private fun startXray() {
        val configFile = File(context.filesDir, "config.json")
        if (!configFile.exists()) throw Exception("Xray config file not found")

        val command = listOf(xrayBinary.absolutePath, "run", "-c", configFile.absolutePath)

        val processBuilder = ProcessBuilder(command)
        xrayProcess = processBuilder.start()

        coroutineScope.launch {
            xrayProcess?.inputStream?.bufferedReader()?.useLines { lines ->
                lines.forEach { line -> Log.i(TAG, "[Xray-out] $line") }
            }
        }
        coroutineScope.launch {
            xrayProcess?.errorStream?.bufferedReader()?.useLines { lines ->
                lines.forEach { line -> Log.e(TAG, "[Xray-err] $line") }
            }
        }

        Log.d(TAG, "Xray process started with command: ${command.joinToString(" ")}")
    }

    private suspend fun waitForProxyReady(timeoutMs: Long = 10000L, intervalMs: Long = 200L) = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", PROXY_PORT), 100)
                }
                return@withContext
            } catch (e: Exception) {
                delay(intervalMs)
            }
        }
        throw Exception("Proxy on port $PROXY_PORT did not become available within ${timeoutMs}ms.")
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
