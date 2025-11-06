package com.example.greetingcard.data

import com.example.greetingcard.BuildConfig
import com.example.greetingcard.net.RttClient
import com.example.greetingcard.net.optArray
import com.example.greetingcard.net.optObj
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

class TrainRepository(
    private val client: RttClient = RttClient
) {
    /**
     * @param origin 3-letter CRS (e.g. "SAC")
     * @param dest   3-letter CRS (e.g. "ZFD")
     * @param take   number of trains to include
     */
    suspend fun getStatusText(
        origin: String,
        dest: String,
        take: Int = 4,
        fastOnly: Boolean = false
    ): String =
        withContext(Dispatchers.IO) {
            if (BuildConfig.RTT_USER.isEmpty() || BuildConfig.RTT_PASS.isEmpty()) {
                return@withContext "RTT credentials missing. Add RTT_USER and RTT_PASS to ~/.gradle/gradle.properties"
            }

            val search = runCatching { client.search(origin, dest) }
                .getOrElse { return@withContext "Error fetching search: ${it.message}" }

            val servicesArray = search.optArray("services")
            if (servicesArray.length() == 0) return@withContext "No services returned."

            val destCrs = dest.uppercase()
            val services = mutableListOf<ServiceBlock>()

            for (i in 0 until servicesArray.length()) {
                val service = servicesArray.getJSONObject(i)
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

                val arrivalLookup = if (services.size < DETAIL_LOOKUP_LIMIT) {
                    findArrivalTime(uid, runDate, destCrs)
                } else {
                    null
                }
                val arrivalTime = arrivalLookup?.takeIf { isValidHHMM(it) }
                val journey = if (arrivalTime != null) elapsedMinutes(dep, arrivalTime) else null

                val status = when {
                    isCancelled -> "Cancelled"
                    hasRealtime -> "Live"
                    else -> "Scheduled"
                }

                // ---- Compact, two-line format ----
                val line1 = if (arrivalTime != null && journey != null) {
                    "$dep \u2192 $arrivalTime (${journey} min) • Platform $platform"
                } else {
                    "$dep • Platform $platform"
                }
                val line2Suffix = if (arrivalTime == null) " • ETA unavailable" else ""
                val line2 = "$destDesc • $status$line2Suffix"

                services += ServiceBlock(
                    departure = dep,
                    arrival = arrivalTime,
                    durationMinutes = journey,
                    block = "$line1\n$line2"
                )

                if (services.size == take) break
            }

            val filtered = if (fastOnly) filterSlowerServices(services) else services
            val blocks = filtered.map { it.block }

            if (blocks.isEmpty()) "No matching services found."
            else "Station: $origin \u2192 $dest\n" + blocks.joinToString("\n\n")
        }

    private data class ServiceBlock(
        val departure: String,
        val arrival: String?,
        val durationMinutes: Int?,
        val block: String
    )

    private fun filterSlowerServices(services: List<ServiceBlock>): List<ServiceBlock> {
        if (services.size <= 1) return services
        val result = mutableListOf<ServiceBlock>()
        for (i in services.indices) {
            val current = services[i]
            val next = services.getOrNull(i + 1)
            if (
                next != null &&
                current.durationMinutes != null &&
                next.durationMinutes != null &&
                isTimeBefore(current.departure, next.departure) &&
                current.durationMinutes > next.durationMinutes
            ) {
                continue
            }
            result += current
        }
        return result
    }

    private fun isTimeBefore(a: String, b: String): Boolean = toMinutes(a) < toMinutes(b)

    private fun toMinutes(value: String): Int {
        val hours = value.substring(0, 2).toInt()
        val minutes = value.substring(2).toInt()
        return hours * 60 + minutes
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

    private fun findArrivalTime(uid: String, runDate: String, destCrs: String): String? {
        val detail = runCatching { client.service(uid, runDate) }.getOrNull() ?: return null
        val locations = detail.optArray("locations")
        if (locations.length() == 0) return null

        for (i in 0 until locations.length()) {
            val loc = locations.getJSONObject(i)
            val crs = loc.optString("crs").uppercase()
            val matches = when {
                crs.isNotEmpty() -> crs == destCrs
                else -> loc.optString("description").equals(
                    friendlyDestName(destCrs),
                    ignoreCase = true
                )
            }
            if (!matches) continue

            val realtime = loc.optString("realtimeArrival").takeIf { isValidHHMM(it) }
            if (realtime != null) return realtime

            val scheduled = loc.optString("gbttBookedArrival").takeIf { isValidHHMM(it) }
            if (scheduled != null) return scheduled
        }

        return null
    }

    private companion object {
        /**
         * Limit how many per-train detail lookups we make. The widget only surfaces the first
         * few departures, and avoiding extra network calls keeps refreshes reliable on weak
         * connections while still providing journey times for the most relevant services.
         */
        private const val DETAIL_LOOKUP_LIMIT = 3
    }
}
