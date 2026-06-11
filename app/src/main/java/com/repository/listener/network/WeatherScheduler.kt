package com.repository.listener.network

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.repository.listener.config.AppConfig
import java.util.concurrent.TimeUnit

object WeatherScheduler {
    private const val UNIQUE = "listener_weather_periodic"

    fun schedule(ctx: Context) {
        val refreshMin = AppConfig.getWeatherRefreshMin(ctx).coerceAtLeast(15)
        val req = PeriodicWorkRequestBuilder<WeatherWorker>(refreshMin.toLong(), TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE)
    }

    fun oneShot(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<WeatherWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(ctx).enqueue(req)
    }
}
