package com.protonvpn.android.di

import android.util.Log
import com.protonvpn.android.proxy.VlessManager
import com.protonvpn.android.vpn.VpnDns
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.proton.core.network.data.ProtonCookieStore
import me.proton.core.network.domain.ApiClient
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.*
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

// Внутренний ProxySelector, который решает — через SOCKS или напрямую
class VlessProxySelector(
    private val vlessManager: VlessManager
) : ProxySelector() {

    private val proxiedHosts = setOf("vpn-api.proton.me")

    override fun select(uri: URI?): List<Proxy> {
        val host = uri?.host ?: return listOf(Proxy.NO_PROXY)

        return if (vlessManager.isReady() && proxiedHosts.contains(host)) {
            Log.d("VlessProxySelector", "Using SOCKS proxy for host: $host")
            listOf(
                Proxy(
                    Proxy.Type.SOCKS,
                    InetSocketAddress("127.0.0.1", VlessManager.PROXY_PORT)
                )
            )
        } else {
            Log.d("VlessProxySelector", "Direct connection for host: $host")
            listOf(Proxy.NO_PROXY)
        }
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        Log.w("VlessProxySelector", "Connection failed for $uri", ioe)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object ProxyOverrideModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(
        original: dagger.Lazy<OkHttpClient>,
        vpnDns: VpnDns,
        apiClient: ApiClient,
        cookieStore: ProtonCookieStore,
        cache: dagger.Lazy<Cache>,
        vlessManager: VlessManager
    ): OkHttpClient {
        return original.get().newBuilder()
            .dns(vpnDns)
            .cache(cache.get())
            .callTimeout(apiClient.callTimeoutSeconds, TimeUnit.SECONDS)
            .connectTimeout(apiClient.connectTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(apiClient.writeTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(apiClient.readTimeoutSeconds, TimeUnit.SECONDS)
            .cookieJar(cookieStore)
            .proxySelector(VlessProxySelector(vlessManager)) // 👈 точечное проксирование
            .build()
    }
}
