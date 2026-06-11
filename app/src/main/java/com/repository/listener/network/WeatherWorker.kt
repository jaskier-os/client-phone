package com.repository.listener.network

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.repository.listener.config.AppConfig
import com.repository.listener.location.WeatherLocationProvider

class WeatherWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!AppConfig.isWeatherEnabled(ctx)) return Result.success()
        val loc = WeatherLocationProvider().getLocation(ctx) ?: return Result.success()
        val w = WeatherFetcher().fetch(loc.first, loc.second) ?: return Result.retry()
        AppConfig.setLastWeather(ctx, w.iconTag, w.tempC, "")
        ctx.sendBroadcast(Intent(ACTION_WEATHER_UPDATED).setPackage(ctx.packageName))
        return Result.success()
    }

    companion object {
        const val ACTION_WEATHER_UPDATED = "com.repository.listener.action.WEATHER_UPDATED"
    }
}
