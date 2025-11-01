package com.example.greetingcard.widget

const val ROUTE_ID_A = "route_a"
const val ROUTE_ID_B = "route_b"
const val EXTRA_ROUTE_ID = "com.example.greetingcard.widget.EXTRA_ROUTE_ID"
const val EXTRA_ORIGIN = "com.example.greetingcard.widget.EXTRA_ORIGIN"
const val EXTRA_DEST = "com.example.greetingcard.widget.EXTRA_DEST"

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
    private val routeStates: MutableMap<String, WidgetRouteState> = mutableMapOf()

    @Synchronized
    fun update(routeId: String, state: WidgetRouteState) {
        routeStates[routeId] = state
    }

    @Synchronized
    fun get(routeId: String): WidgetRouteState? = routeStates[routeId]
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
