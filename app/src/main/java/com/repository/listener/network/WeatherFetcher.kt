package com.repository.listener.network

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class WeatherFetcher {
    data class Weather(val iconTag: String, val tempC: Int)

    fun fetch(lat: Double, lon: Double): Weather? {
        return try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code" +
                    "&temperature_unit=celsius"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) {
                conn.disconnect(); return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val current = JSONObject(body).optJSONObject("current") ?: return null
            val temp = current.optDouble("temperature_2m", Double.NaN)
            val code = current.optInt("weather_code", -1)
            if (temp.isNaN() || code < 0) return null
            Weather(iconOf(code), temp.toInt())
        } catch (e: Exception) {
            null
        }
    }

    private fun iconOf(code: Int): String = when (code) {
        0 -> "clear"
        1, 2, 3 -> "cloudy"
        45, 48 -> "fog"
        in 51..67, in 80..82 -> "rain"
        in 71..77, 85, 86 -> "snow"
        in 95..99 -> "thunder"
        else -> "cloudy"
    }
}
