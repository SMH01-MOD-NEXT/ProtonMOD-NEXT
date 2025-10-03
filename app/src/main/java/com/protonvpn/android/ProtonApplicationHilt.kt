package com.protonvpn.android

import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.WebView
import androidx.hilt.work.HiltWorkerFactory
import androidx.startup.AppInitializer
import androidx.work.Configuration
import com.protonvpn.android.logging.MemoryMonitor
import com.protonvpn.android.proxy.VlessManager
import com.protonvpn.android.ui.onboarding.OnboardingTelemetry
import com.protonvpn.android.ui.promooffers.TestNotificationLoader
import com.protonvpn.android.utils.SentryIntegration
import com.protonvpn.android.utils.isMainProcess
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.proton.core.auth.presentation.MissingScopeInitializer
import me.proton.core.crypto.validator.presentation.init.CryptoValidatorInitializer
import me.proton.core.network.data.di.SharedOkHttpClient
import me.proton.core.network.presentation.init.UnAuthSessionFetcherInitializer
import me.proton.core.plan.presentation.UnredeemedPurchaseInitializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import javax.inject.Inject

class CrashReporter(
    private val context: Context,
    private val botToken: String,
    private val chatId: String
) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val prefs = context.getSharedPreferences("crash_reporter", Context.MODE_PRIVATE)

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            Log.e(TAG, "Uncaught exception in thread ${t.name}", e)

            val crashDir = File(context.cacheDir, "crash_reports").apply { mkdirs() }
            val timestamp = System.currentTimeMillis()

            // stacktrace.txt теперь содержит и device info, и сам стек
            val stackFile = File(crashDir, "stacktrace_$timestamp.txt").apply {
                val deviceInfo = collectDeviceInfo()
                val stacktrace = Log.getStackTraceString(e)
                writeText(deviceInfo + "\n=== Stacktrace ===\n" + stacktrace)
            }

            val logcatFile = File(crashDir, "logcat_$timestamp.txt").apply {
                try {
                    val process = Runtime.getRuntime().exec("logcat -d -t 500")
                    process.inputStream.use { input ->
                        outputStream().use { out -> input.copyTo(out) }
                    }
                    process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                } catch (ex: Exception) {
                    writeText("Failed to run logcat: ${Log.getStackTraceString(ex)}")
                }
            }

            Log.i(TAG, "Crash saved: ${stackFile.name}, ${logcatFile.name}")
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to save crash logs", ex)
        } finally {
            defaultHandler?.uncaughtException(t, e)
        }
    }

    fun handleStartupLogs() {
        val launches = prefs.getInt("launch_count", 0) + 1
        prefs.edit().putInt("launch_count", launches).apply()
        Log.i(TAG, "App launch #$launches")

        val pendingFile = File(context.cacheDir, "pending_startup.txt")

        if (launches % 2 == 1) {
            Log.i(TAG, "Collecting startup logs (odd launch)")
            val header = "Startup log at ${System.currentTimeMillis()}\n\n"
            val deviceInfo = collectDeviceInfo()
            val vlessLogs = collectTaggedLogs("VLESS_MANAGER", "VlessProxySelector")

            pendingFile.writeText(header + deviceInfo + vlessLogs)
            Log.i(TAG, "Startup log saved: ${pendingFile.absolutePath}, size=${pendingFile.length()}")

            Runtime.getRuntime().exec("logcat -c")
        } else {
            if (pendingFile.exists()) {
                Log.i(TAG, "Sending pending startup logs (even launch)")
                sendToTelegram(pendingFile) { it.delete() }
            } else {
                Log.w(TAG, "No pending startup logs to send")
            }
        }
    }

    fun sendPendingCrashes() {
        val crashDir = File(context.cacheDir, "crash_reports")
        if (!crashDir.exists()) return

        crashDir.listFiles()?.forEach { file ->
            if (file.exists()) {
                Log.i(TAG, "Sending crash file ${file.name}")
                sendToTelegram(file) { it.delete() }
            }
        }
    }

    private fun collectDeviceInfo(): String = buildString {
        appendLine("=== Device Info ===")
        appendLine("Manufacturer: ${Build.MANUFACTURER}")
        appendLine("Brand: ${Build.BRAND}")
        appendLine("Model: ${Build.MODEL}")
        appendLine("Device: ${Build.DEVICE}")
        appendLine("Product: ${Build.PRODUCT}")
        appendLine("Hardware: ${Build.HARDWARE}")
        appendLine("Board: ${Build.BOARD}")
        appendLine("Bootloader: ${Build.BOOTLOADER}")
        appendLine("Fingerprint: ${Build.FINGERPRINT}")
        appendLine("Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Security Patch: ${Build.VERSION.SECURITY_PATCH}")
        appendLine("Incremental: ${Build.VERSION.INCREMENTAL}")
        appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine()
    }

    private fun collectTaggedLogs(vararg tags: String): String {
        val builder = StringBuilder()
        try {
            val cmd = mutableListOf("logcat", "-d", "-t", "2000")
            tags.forEach { tag -> cmd.add("$tag:D") }
            cmd.add("*:S")

            val process = Runtime.getRuntime().exec(cmd.toTypedArray())
            val logs = process.inputStream.bufferedReader().readText()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            builder.appendLine("=== Filtered logs for ${tags.joinToString()} ===")
            builder.appendLine(logs)
        } catch (e: Exception) {
            builder.appendLine("Failed to collect logs: ${e.message}")
        }
        return builder.toString()
    }

    private fun sendToTelegram(vararg files: File, onSuccess: (File) -> Unit = {}) {
        if (botToken.isBlank() || chatId.isBlank()) {
            Log.w(TAG, "Telegram credentials missing, skipping send")
            return
        }

        val client = OkHttpClient()

        files.forEach { file ->
            if (!file.exists()) {
                Log.w(TAG, "File ${file.absolutePath} does not exist, skipping")
                return@forEach
            }

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart(
                    "document", file.name,
                    file.asRequestBody("text/plain".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("https://api.telegram.org/bot$botToken/sendDocument")
                .post(body)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Error sending ${file.name}", e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (it.isSuccessful) {
                            Log.i(TAG, "Successfully sent ${file.name}")
                            onSuccess(file)
                        } else {
                            Log.e(TAG, "Failed to send ${file.name}, code=${it.code}")
                        }
                    }
                }
            })
        }
    }

    fun sendTestLog() {
        Log.i(TAG, "Manual test send triggered")
        val dummy = File(context.cacheDir, "dummy.txt").apply {
            writeText("Hello from CrashReporter test")
        }
        sendToTelegram(dummy)
    }

    companion object {
        private const val TAG = "CrashReporter"

        fun init(context: Context, botToken: String, chatId: String) {
            Log.i(TAG, "Initializing CrashReporter…")
            Thread.setDefaultUncaughtExceptionHandler(
                CrashReporter(context, botToken, chatId)
            )
        }
    }
}

@HiltAndroidApp
class ProtonApplicationHilt : ProtonApplication(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var onboardingTelemetry: dagger.Lazy<OnboardingTelemetry>
    @Inject lateinit var testNotificationLoader: dagger.Lazy<TestNotificationLoader>
    @Inject lateinit var updateMigration: UpdateMigration
    @Inject lateinit var memoryMonitor: dagger.Lazy<MemoryMonitor>

    @Inject
    @SharedOkHttpClient
    lateinit var okHttpClient: OkHttpClient

    private val job = SupervisorJob()
    val appScope = CoroutineScope(job + Dispatchers.IO)
    val vlessManager: VlessManager by lazy { VlessManager.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        SentryIntegration.initAccountSentry()
        ProxyPrefs(this).setEnabled(false)

        if (isMainProcess()) {
            initDependencies()

            AppInitializer.getInstance(this).initializeComponent(CryptoValidatorInitializer::class.java)
            AppInitializer.getInstance(this).initializeComponent(MissingScopeInitializer::class.java)
            AppInitializer.getInstance(this).initializeComponent(UnredeemedPurchaseInitializer::class.java)
            AppInitializer.getInstance(this).initializeComponent(UnAuthSessionFetcherInitializer::class.java)

            if (BuildConfig.DEBUG) {
                WebView.setWebContentsDebuggingEnabled(true)
                testNotificationLoader.get().loadTestFile()
            }

            updateMigration.handleUpdate()
            onboardingTelemetry.get().onAppStart()
            memoryMonitor.get().start()
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        memoryMonitor.get().onTrimMemory()
    }
}
