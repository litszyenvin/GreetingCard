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
    fun invalidateNetwork() {
        client.invalidate()
    }

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

            val destCrs = dest.uppercase()
            val blocks = mutableListOf<String>() // each block = 2 lines per service

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

                // Display destination name (for readability)
                val destList = location.optJSONArray("destination")
                val destDesc =
                    if (destList != null && destList.length() > 0)
                        destList.getJSONObject(0).optString("description", "Unknown")
                    else "Unknown"

                // Platform (may be missing)
                val platformRaw = location.optString("platform", "").trim()
                val platform = if (platformRaw.isBlank()) "—" else platformRaw

                // Per-service details: find ARRIVAL at our DEST (prefer match by CRS)
                val detail = runCatching { client.service(uid, runDate) }.getOrNull() ?: JSONObject()
                val locations = detail.optArray("locations")

                val arrivalTime = (0 until locations.length())
                    .asSequence()
                    .map { locations.getJSONObject(it) }
                    .firstOrNull { loc ->
                        val crs = loc.optString("crs").uppercase()
                        if (crs.isNotEmpty()) {
                            crs == destCrs
                        } else {
                            // Fallback to description if 'crs' missing
                            loc.optString("description").equals(
                                friendlyDestName(destCrs),
                                ignoreCase = true
                            )
                        }
                    }
                    ?.let { it.optString("realtimeArrival", it.optString("gbttBookedArrival")) }

                if (!isValidHHMM(arrivalTime)) continue

                val journey = elapsedMinutes(dep, arrivalTime!!)

                val status = when {
                    isCancelled -> "Cancelled"
                    hasRealtime -> "Live"
                    else -> "Scheduled"
                }

                // ---- Compact, two-line format ----
                // Line 1: dep → arr (X min) • Platform N
                val line1 = "$dep \u2192 $arrivalTime (${journey} min) • Platform $platform"
                // Line 2: Destination • Status
                val line2 = "$destDesc • $status"

                blocks += "$line1\n$line2"

                if (blocks.size == take) break
            }

            if (blocks.isEmpty()) "No matching services found."
            else "Station: $origin \u2192 $dest\n" + blocks.joinToString("\n\n")
        }

    /**
     * Human-readable station name fallback for description matching.
     * We prefer matching by locations[].crs above; this is only used if 'crs' is missing.
     */
    private fun friendlyDestName(destCrsUpper: String): String = when (destCrsUpper) {
        "ZFD" -> "Farringdon"
        "SAC" -> "St Albans City"
        // Add more as needed:
        // "STP" -> "London St Pancras International"
        else -> destCrsUpper
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
