package com.example.greetingcard.widget

import com.example.greetingcard.data.TrainRepository
import kotlinx.coroutines.delay

const val ROUTE_ID_A = "route_a"
const val ROUTE_ID_B = "route_b"
const val EXTRA_ROUTE_ID = "com.example.greetingcard.widget.EXTRA_ROUTE_ID"
const val EXTRA_ORIGIN = "com.example.greetingcard.widget.EXTRA_ORIGIN"
const val EXTRA_DEST = "com.example.greetingcard.widget.EXTRA_DEST"
const val EXTRA_FAST_ONLY = "com.example.greetingcard.widget.EXTRA_FAST_ONLY"

private const val DEFAULT_EMPTY_MESSAGE = "No upcoming services."

/** Two-line entry rendered inside the widget's list views. */
data class WidgetServiceItem(
    val line1: String,
    val line2: String
)

/** Holds the display state for a single widget route section. */
data class WidgetRouteState(
    val title: String,
    val services: List<WidgetServiceItem>,
    val emptyMessage: String
)

/**
 * In-memory cache shared between the widget provider and the RemoteViewsService.
 * This avoids re-fetching data for every ListView bind.
 */
object WidgetDataCache {
    private data class CacheKey(val routeId: String, val fastOnly: Boolean)

    private val routeStates: MutableMap<CacheKey, WidgetRouteState> = mutableMapOf()

    @Synchronized
    fun update(routeId: String, fastOnly: Boolean, state: WidgetRouteState) {
        routeStates[CacheKey(routeId, fastOnly)] = state
    }

    @Synchronized
    fun get(routeId: String, fastOnly: Boolean): WidgetRouteState? =
        routeStates[CacheKey(routeId, fastOnly)]

    @Synchronized
    fun clear() {
        routeStates.clear()
    }
}

/** Attempt to fetch widget data with retries and fall back to the last good state. */
suspend fun fetchWidgetRouteState(
    repo: TrainRepository,
    origin: String,
    dest: String,
    take: Int,
    fastOnly: Boolean,
    fallbackTitle: String,
    previousState: WidgetRouteState? = null
): WidgetRouteState {
    var lastErrorMessage: String? = null

    repeat(WIDGET_FETCH_ATTEMPTS) { attempt ->
        val result = runCatching { repo.getStatusText(origin, dest, take, fastOnly) }
        val raw = result.getOrNull()
        val parsed = raw?.let { parseWidgetRouteState(it, fallbackTitle) }
        val isError = raw == null || raw.trim().startsWith("Error", ignoreCase = true)

        if (!isError && parsed != null) {
            return parsed
        }

        lastErrorMessage = parsed?.emptyMessage
            ?: result.exceptionOrNull()?.message?.let { "Error: $it" }
            ?: GENERIC_ERROR_MESSAGE

        if (attempt < WIDGET_FETCH_ATTEMPTS - 1) {
            delay(WIDGET_FETCH_RETRY_DELAY_MS * (attempt + 1))
        }
    }

    previousState?.let { previous ->
        if (previous.services.isNotEmpty()) {
            val warningTitle = addWarningIndicator(previous.title)
            return previous.copy(title = warningTitle)
        }
    }

    val message = lastErrorMessage ?: GENERIC_ERROR_MESSAGE
    return WidgetRouteState(
        title = fallbackTitle,
        services = emptyList(),
        emptyMessage = message
    )
}

/**
 * Convert the repository's status text into a widget-friendly structure.
 */
fun parseWidgetRouteState(raw: String, fallbackTitle: String): WidgetRouteState {
    val trimmed = raw.trim()
    if (!trimmed.contains('\n')) {
        val message = trimmed.ifBlank { DEFAULT_EMPTY_MESSAGE }
        return WidgetRouteState(
            title = fallbackTitle,
            services = emptyList(),
            emptyMessage = message
        )
    }

    val (title, body) = splitHeaderBodyOrFallback(trimmed, fallbackTitle)
    val blocks = body.split("\n\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val services = blocks
        .map { block ->
            val lines = block.lines()
            val line1 = lines.getOrNull(0)?.trim().orEmpty()
            val line2 = lines.getOrNull(1)?.trim().orEmpty()
            WidgetServiceItem(line1, line2)
        }
        .filter { it.line1.isNotEmpty() || it.line2.isNotEmpty() }

    val emptyMessage = if (services.isEmpty()) {
        body.trim().ifBlank { DEFAULT_EMPTY_MESSAGE }
    } else {
        DEFAULT_EMPTY_MESSAGE
    }

    return WidgetRouteState(
        title = title,
        services = services.take(8),
        emptyMessage = emptyMessage
    )
}

private fun splitHeaderBodyOrFallback(text: String, fallbackTitle: String): Pair<String, String> {
    val lines = text.lines()
    val header = lines.firstOrNull()?.takeIf { it.isNotBlank() } ?: fallbackTitle
    val body = lines.drop(1).joinToString("\n").ifBlank { DEFAULT_EMPTY_MESSAGE }
    val normalizedHeader = header
        .removePrefix("Station: ")
        .trim()
        .ifBlank { fallbackTitle }
        .replace(" -> ", " → ")
        .replace(" - > ", " → ")
        .replace("-->", "→")
        .let { ensureArrowDirection(it, fallbackTitle) }
    return normalizedHeader to body
}

private fun ensureArrowDirection(value: String, fallbackTitle: String): String {
    if ('→' in value) return value
    val normalized = value.replace("-", "→")
    return if ('→' in normalized) normalized else fallbackTitle
}

private fun addWarningIndicator(title: String): String =
    if (title.contains("⚠")) title else "$title ⚠"

private const val WIDGET_FETCH_ATTEMPTS = 3
private const val WIDGET_FETCH_RETRY_DELAY_MS = 500L
private const val GENERIC_ERROR_MESSAGE = "Error: Unable to load services."
