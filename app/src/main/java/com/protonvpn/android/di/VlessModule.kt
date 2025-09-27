package com.protonvpn.android.di

import android.content.Context
import com.protonvpn.android.proxy.VlessManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VlessModule {

    @Provides
    @Singleton
    fun provideVlessManager(@ApplicationContext context: Context): VlessManager {
        return VlessManager.getInstance(context)
    }
}

