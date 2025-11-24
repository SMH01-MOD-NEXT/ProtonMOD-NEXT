/*
 *
 * * Copyright (c) 2025. Proton AG
 * *
 * * This file is part of ProtonVPN.
 * *
 * * ProtonVPN is free software: you can redistribute it and/or modify
 * * it under the terms of the GNU General Public License as published by
 * * the Free Software Foundation, either version 3 of the License, or
 * * (at your option) any later version.
 * *
 * * ProtonVPN is distributed in the hope that it will be useful,
 * * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * * GNU General Public License for more details.
 * *
 * * You should have received a copy of the GNU General Public License
 * * along with ProtonVPN.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.protonvpn.android.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.protonvpn.android.proxy.VlessManager
import com.protonvpn.android.utils.Storage
import com.protonvpn.android.vpn.VpnDns
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.proton.core.network.data.ProtonCookieStore
import me.proton.core.network.domain.ApiClient
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.*
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import me.proton.core.network.data.di.SharedOkHttpClient
import java.io.File

class VlessProxySelector(
    private val vlessManager: VlessManager,
    private val context: Context
) : ProxySelector() {

    /**
     * Selects the proxy server to use, if any.
     * This method is called by the networking library (OkHttp) before establishing a connection.
     * It performs a real-time check of the VPN state, user preferences, and VlessManager readiness.
     */
    override fun select(uri: URI?): List<Proxy> {
        // Log.d("VlessProxySelector", "select() called for $uri") // Optional: Reduce log spam
        val host = uri?.host ?: return listOf(Proxy.NO_PROXY)

        // 1. REAL-TIME CHECK: Is VPN currently active?
        // If VPN is active, we MUST bypass the local proxy to avoid conflicts or loops.
        if (isVpnActive()) {
            Log.d("VlessProxySelector", "State: VPN Active. Routing: DIRECT. Host: $host")
            return listOf(Proxy.NO_PROXY)
        }

        // 2. REAL-TIME CHECK: Is the Proxy Toggle enabled?
        val enabled = Storage.getBoolean("proxy_enabled", false)

        if (!enabled) {
            Log.d("VlessProxySelector", "State: VPN Inactive, Proxy Disabled. Routing: DIRECT. Host: $host")
            return listOf(Proxy.NO_PROXY)
        }

        // 3. REAL-TIME CHECK: Is VlessManager actually ready to handle traffic?
        // Even if enabled, if the local proxy isn't running/ready yet (e.g. still connecting/testing),
        // we fallback to direct connection to ensure connectivity.
        if (!vlessManager.isReady()) {
            Log.d("VlessProxySelector", "State: Proxy Enabled BUT VlessManager NOT READY. Routing: DIRECT. Host: $host")
            return listOf(Proxy.NO_PROXY)
        }

        // 4. If Enabled AND Ready -> Use Proxy
        // Get the current port dynamically from the instance
        val currentPort = VlessManager.PROXY_PORT
        Log.d("VlessProxySelector", "State: VPN Inactive, Proxy Enabled & Ready. Routing: SOCKS5 ($currentPort). Host: $host")
        return listOf(
            Proxy(
                Proxy.Type.SOCKS,
                InetSocketAddress("127.0.0.1", currentPort)
            )
        )
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        Log.w("VlessProxySelector", "Connection failed for $uri", ioe)
    }

    /**
     * Checks if the device currently has an active VPN transport.
     * This queries the system ConnectivityManager for the current active network capabilities.
     */
    private fun isVpnActive(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = connectivityManager?.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

            // Check if the active network transport is VPN
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } catch (e: Exception) {
            Log.e("VlessProxySelector", "Error checking VPN state", e)
            false
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object OkHttpCacheModule {
    @Provides
    @Singleton
    fun provideOkHttpCache(@ApplicationContext context: Context): Cache {
        val cacheDir = File(context.cacheDir, "http_cache")
        return Cache(cacheDir, 50L * 1024L * 1024L) // 50 MB
    }
}


@Module
@InstallIn(SingletonComponent::class)
object ProxyOverrideModule {

    @Provides
    @Singleton
    @SharedOkHttpClient
    fun provideSharedOkHttpClient(
        vpnDns: VpnDns,
        apiClient: ApiClient,
        cookieStore: ProtonCookieStore,
        cache: dagger.Lazy<Cache>,
        vlessManager: VlessManager,
        @ApplicationContext context: Context
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .dns(vpnDns)
            .cache(cache.get())
            .callTimeout(apiClient.callTimeoutSeconds, TimeUnit.SECONDS)
            .connectTimeout(apiClient.connectTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(apiClient.writeTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(apiClient.readTimeoutSeconds, TimeUnit.SECONDS)
            .cookieJar(cookieStore)
            .proxySelector(VlessProxySelector(vlessManager, context))
            .build()
    }
}