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

package com.protonvpn.android.di

import android.content.Context
import android.util.Log
import com.protonvpn.android.proxy.VlessManager
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


// Внутренний ProxySelector, который решает — через SOCKS или напрямую
class VlessProxySelector(
    private val vlessManager: VlessManager,
    private val context: Context
) : ProxySelector() {


    override fun select(uri: URI?): List<Proxy> {
        Log.d("VlessProxySelector", "select() called for $uri")
        val host = uri?.host ?: return listOf(Proxy.NO_PROXY)
        val prefs = context.getSharedPreferences("protonmod_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("proxy_enabled", false)

        return if (enabled) {
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
