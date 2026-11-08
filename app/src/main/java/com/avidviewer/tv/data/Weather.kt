package com.avidviewer.tv.data

import org.json.JSONObject
import java.net.URLEncoder

data class Place(val name: String, val lat: Double, val lon: Double, val timeZone: String)
data class Weather(val temp: Int, val units: String, val code: Int, val isDay: Boolean, val fetchedAt: Long) {
    val condition: String get() = describe(code)
}

/**
 * Weather from Open-Meteo (free, no account). Only the postal code (for the lookup) and the
 * resulting coordinates are sent; nothing else about the user leaves the device.
 */
object WeatherService {
    private val usZip = Regex("^\\d{5}$")
    private val caPostal = Regex("^[A-Za-z]\\d[A-Za-z] ?\\d?[A-Za-z]?\\d?$")

    /** Turns a US ZIP or Canadian postal code into a place with coordinates and its time zone. */
    fun lookup(postal: String): Place? {
        val code = postal.trim().uppercase()
        val (query, country) = when {
            usZip.matches(code) -> code to "US"
            caPostal.matches(code) -> code.replace(" ", "").take(3) to "CA" // lookup knows the first 3 characters
            else -> code to null
        }
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" + URLEncoder.encode(query, "UTF-8") +
            "&count=10&language=en&format=json" + (country?.let { "&countryCode=$it" } ?: "")
        val results = JSONObject(Net.text(url)).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        var best = results.getJSONObject(0)
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val pcs = r.optJSONArray("postcodes")
            if (pcs != null && (0 until pcs.length()).any { pcs.getString(it).equals(query, true) }) { best = r; break }
        }
        val region = best.optString("admin1")
        val name = best.optString("name") + if (region.isNotBlank()) ", $region" else ""
        return Place(name, best.getDouble("latitude"), best.getDouble("longitude"), best.optString("timezone"))
    }

    fun current(lat: Double, lon: Double, units: String): Weather {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day&timezone=auto" +
            if (units == "F") "&temperature_unit=fahrenheit" else ""
        val cur = JSONObject(Net.text(url)).getJSONObject("current")
        return Weather(
            temp = Math.round(cur.getDouble("temperature_2m")).toInt(),
            units = units,
            code = cur.optInt("weather_code", -1),
            isDay = cur.optInt("is_day", 1) == 1,
            fetchedAt = System.currentTimeMillis(),
        )
    }
}

/** Short labels for WMO weather codes, sized for a TV corner. */
fun describe(code: Int): String = when (code) {
    0 -> "Clear"
    1 -> "Mainly clear"
    2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Fog"
    51, 53, 55 -> "Drizzle"
    56, 57 -> "Freezing drizzle"
    61, 63 -> "Rain"
    65 -> "Heavy rain"
    66, 67 -> "Freezing rain"
    71, 73 -> "Snow"
    75 -> "Heavy snow"
    77 -> "Snow grains"
    80, 81 -> "Showers"
    82 -> "Heavy showers"
    85, 86 -> "Snow showers"
    95 -> "Thunderstorm"
    96, 99 -> "Storm, hail"
    97 -> "Heavy storm"
    else -> ""
}
