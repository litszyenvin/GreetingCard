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

/**
 * Template-named widget provider that fetches live RTT data.
 */
class NewAppWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_REFRESH = "com.example.greetingcard.action.REFRESH"
        private const val DEFAULT_ORIGIN = "SAC"
        private const val DEFAULT_DEST = "ZFD"
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        // show quick loading while we fetch
        for (id in appWidgetIds) {
            val views = buildViews(context, "Loading…")
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
            val text = try {
                repo.getStatusText(DEFAULT_ORIGIN, DEFAULT_DEST, take = 4)
            } catch (e: Exception) {
                "Error: ${e.message ?: "unknown"}"
            }
            val views = buildViews(context, text)
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPI(context))
            manager.updateAppWidget(ids, views)
        }
    }

    private fun buildViews(context: Context, body: String): RemoteViews {
        return RemoteViews(context.packageName, R.layout.new_app_widget).apply {
            setTextViewText(R.id.widget_title, context.getString(R.string.widget_title))
            setTextViewText(R.id.widget_text, body)
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
