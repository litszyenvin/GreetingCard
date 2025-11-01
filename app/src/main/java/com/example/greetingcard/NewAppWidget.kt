package com.example.greetingcard

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.greetingcard.data.TrainRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NewAppWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_REFRESH = "com.example.greetingcard.action.REFRESH"
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Two routes
        private const val ORIGIN_A = "SAC"
        private const val DEST_A = "ZFD"
        private const val ORIGIN_B = "ZFD"
        private const val DEST_B = "SAC"
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        // Fill the widget area immediately with placeholders
        for (id in appWidgetIds) {
            val views = buildViews(
                context = context,
                titleA = "$ORIGIN_A → $DEST_A",
                bodyA = "Loading…",
                titleB = "$ORIGIN_B → $DEST_B",
                bodyB = "Loading…"
            )
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPI(context))
            manager.updateAppWidget(id, views)
        }
        fetchAndUpdateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH || intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            fetchAndUpdateAll(context)
        }
    }

    private fun fetchAndUpdateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, NewAppWidget::class.java))
        if (ids.isEmpty()) return

        appScope.launch {
            val repo = TrainRepository()

            val aRaw = runCatching { repo.getStatusText(ORIGIN_A, DEST_A, take = 4) }.getOrElse { "Error: ${it.message}" }
            val bRaw = runCatching { repo.getStatusText(ORIGIN_B, DEST_B, take = 4) }.getOrElse { "Error: ${it.message}" }

            val (aTitle, aBody) = splitHeaderBodyOrFallback(aRaw, "$ORIGIN_A → $DEST_A")
            val (bTitle, bBody) = splitHeaderBodyOrFallback(bRaw, "$ORIGIN_B → $DEST_B")

            val views = buildViews(context, aTitle, aBody, bTitle, bBody)
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPI(context))
            manager.updateAppWidget(ids, views)
        }
    }

    private fun splitHeaderBodyOrFallback(text: String, fallbackTitle: String): Pair<String, String> {
        val lines = text.lines()
        val header = lines.firstOrNull()?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val body = lines.drop(1).joinToString("\n").ifBlank { "No services found." }
        // If the repository already included a header like "Station: X → Y", prefer the concise route for the title
        val normalizedHeader = header.removePrefix("Station: ").trim().ifBlank { fallbackTitle }
        return normalizedHeader to body
    }

    private fun buildViews(
        context: Context,
        titleA: String,
        bodyA: String,
        titleB: String,
        bodyB: String
    ): RemoteViews {
        return RemoteViews(context.packageName, R.layout.new_app_widget).apply {
            setTextViewText(R.id.route_a_title, titleA)
            setTextViewText(R.id.widget_text_a, bodyA)
            setTextViewText(R.id.route_b_title, titleB)
            setTextViewText(R.id.widget_text_b, bodyB)
        }
    }

    private fun refreshPI(context: Context): PendingIntent {
        val intent = Intent(context, NewAppWidget::class.java).apply { action = ACTION_REFRESH }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
