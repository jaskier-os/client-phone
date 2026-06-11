package com.repository.listener.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.repository.listener.config.AppConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Provides a best-effort (lat, lon) for the weather widget.
 * - Tries PRIORITY_HIGH_ACCURACY, then PRIORITY_BALANCED_POWER_ACCURACY, then fusedClient.lastLocation.
 * - Caches successful results via AppConfig.setLastKnownLatLon.
 * - Falls back to AppConfig.getLastKnownLatLon on permission denial or total failure.
 */
class WeatherLocationProvider {

    @SuppressLint("MissingPermission")
    suspend fun getLocation(ctx: Context): Pair<Double, Double>? {
        val hasFine = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            return AppConfig.getLastKnownLatLon(ctx)
        }

        val client: FusedLocationProviderClient =
            LocationServices.getFusedLocationProviderClient(ctx)

        return try {
            val high = requestCurrent(client, Priority.PRIORITY_HIGH_ACCURACY, 8000L)
            val picked = high ?: requestCurrent(client, Priority.PRIORITY_BALANCED_POWER_ACCURACY, 8000L)
                ?: lastLocation(client)
            if (picked != null) {
                AppConfig.setLastKnownLatLon(ctx, picked.first, picked.second)
                picked
            } else {
                AppConfig.getLastKnownLatLon(ctx)
            }
        } catch (e: Exception) {
            AppConfig.getLastKnownLatLon(ctx)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestCurrent(
        client: FusedLocationProviderClient,
        priority: Int,
        timeoutMs: Long,
    ): Pair<Double, Double>? = suspendCancellableCoroutine { cont ->
        try {
            val req = CurrentLocationRequest.Builder()
                .setPriority(priority)
                .setDurationMillis(timeoutMs)
                .build()
            val task = client.getCurrentLocation(req, null)
            task.addOnSuccessListener { loc ->
                if (!cont.isActive) return@addOnSuccessListener
                if (loc == null) cont.resume(null)
                else cont.resume(Pair(loc.latitude, loc.longitude))
            }
            task.addOnFailureListener {
                if (cont.isActive) cont.resume(null)
            }
            task.addOnCanceledListener {
                if (cont.isActive) cont.resume(null)
            }
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun lastLocation(
        client: FusedLocationProviderClient
    ): Pair<Double, Double>? = suspendCancellableCoroutine { cont ->
        try {
            val task = client.lastLocation
            task.addOnSuccessListener { loc ->
                if (!cont.isActive) return@addOnSuccessListener
                if (loc == null) cont.resume(null)
                else cont.resume(Pair(loc.latitude, loc.longitude))
            }
            task.addOnFailureListener {
                if (cont.isActive) cont.resume(null)
            }
            task.addOnCanceledListener {
                if (cont.isActive) cont.resume(null)
            }
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }
}
