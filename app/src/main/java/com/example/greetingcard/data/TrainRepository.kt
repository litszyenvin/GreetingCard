package com.example.greetingcard.data

import com.example.greetingcard.BuildConfig
import com.example.greetingcard.net.RttClient
import com.example.greetingcard.net.optArray
import com.example.greetingcard.net.optObj
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.max

class TrainRepository(
    private val client: RttClient = RttClient
) {
    /**
     * @param origin 3-letter CRS (e.g. "SAC")
     * @param dest   3-letter CRS (e.g. "ZFD")
     * @param take   number of trains to include
     */
    suspend fun getStatusText(origin: String, dest: String, take: Int = 4): String =
        withContext(Dispatchers.IO) {
            if (BuildConfig.RTT_USER.isEmpty() || BuildConfig.RTT_PASS.isEmpty()) {
                return@withContext "RTT credentials missing. Add RTT_USER and RTT_PASS to ~/.gradle/gradle.properties"
            }

            val search = runCatching { client.search(origin, dest) }
                .getOrElse { return@withContext "Error fetching search: ${it.message}" }

            val services = search.optArray("services")
            if (services.length() == 0) return@withContext "No services returned."

            val results = mutableListOf<String>()
            for (i in 0 until services.length()) {
                val service = services.getJSONObject(i)
                val location = service.optObj("locationDetail") ?: continue
                val uid = service.optString("serviceUid")
                val runDate = service.optString("runDate")
                if (uid.isBlank() || runDate.isBlank()) continue

                val isCancelled = location.has("cancelReasonCode")
                val hasRealtime = location.has("realtimeDeparture")

                val dep = when {
                    isCancelled -> location.optString("gbttBookedDeparture")
                    hasRealtime -> location.optString("realtimeDeparture")
                    else -> location.optString("gbttBookedDeparture")
                }
                if (!isValidHHMM(dep)) continue

                val nextDay = location.optBoolean("gbttBookedDepartureNextDay", false)
                if (!isLaterThanNow(dep) && !nextDay) continue

                // Destination description
                val destList = location.optJSONArray("destination")
                val destDesc =
                    if (destList != null && destList.length() > 0)
                        destList.getJSONObject(0).optString("description", "Unknown")
                    else "Unknown"

                // Platform
                val platform = location.optString("platform", "—")

                // Per-service details for arrival time at our friendly destination name
                val detail = runCatching { client.service(uid, runDate) }.getOrNull() ?: JSONObject()
                val locations = detail.optArray("locations")
                val arrivalTime = (0 until locations.length())
                    .asSequence()
                    .map { locations.getJSONObject(it) }
                    .firstOrNull { it.optString("description") == friendlyDestName(dest) }
                    ?.let { it.optString("realtimeArrival", it.optString("gbttBookedArrival")) }

                if (!isValidHHMM(arrivalTime)) continue

                val journey = elapsedMinutes(dep, arrivalTime!!)
                val status = when {
                    isCancelled -> "Cancelled"
                    hasRealtime -> "Live"
                    else -> "Scheduled"
                }

                val label = if (results.isEmpty()) "Next train" else "Following"
                results += "$label: $dep → $arrivalTime (${journey} min) • Platform $platform • $status • $destDesc"

                if (results.size == take) break
            }

            if (results.isEmpty()) "No matching services found."
            else "Station: $origin → $dest\n" + results.joinToString("\n")
        }

    private fun friendlyDestName(destCrs: String): String = when (destCrs.uppercase()) {
        "ZFD" -> "Farringdon"
        else -> destCrs
    }

    private fun isValidHHMM(value: String?): Boolean =
        value != null && value.length >= 4 && value.all { it.isDigit() }

    private fun isLaterThanNow(hhmm: String): Boolean {
        val now = android.text.format.DateFormat.format("HHmm", java.util.Date()).toString()
        val nh = now.substring(0, 2).toInt()
        val nm = now.substring(2).toInt()
        val h = hhmm.substring(0, 2).toInt()
        val m = hhmm.substring(2).toInt()
        return (h > nh) || (h == nh && m > nm)
    }

    private fun elapsedMinutes(start: String, end: String): Int {
        val s = start.substring(0, 2).toInt() * 60 + start.substring(2).toInt()
        var e = end.substring(0, 2).toInt() * 60 + end.substring(2).toInt()
        if (e < s) e += 24 * 60 // midnight wrap
        return max(0, e - s)
    }
}
