package com.example.swarmai.di

import android.content.Context
import com.example.swarmai.ai.TFLiteHelper
import com.example.swarmai.data.repository.ModelRepository
import com.example.swarmai.data.repository.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideTFLiteHelper(
        @ApplicationContext context: Context
    ): TFLiteHelper {
        return TFLiteHelper(context)
    }

    @Provides
    @Singleton
    fun provideModelRepository(
        tfLiteHelper: TFLiteHelper
    ): ModelRepository {
        return ModelRepository(tfLiteHelper)
    }

    @Provides
    @Singleton
    fun provideSettingsRepository(): SettingsRepository {
        return SettingsRepository()
    }
}
