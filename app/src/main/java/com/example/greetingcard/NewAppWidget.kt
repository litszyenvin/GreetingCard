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

        // Two routes shown in widget as well
        private const val ORIGIN_A = "SAC"
        private const val DEST_A = "ZFD"
        private const val ORIGIN_B = "ZFD"
        private const val DEST_B = "SAC"
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        // quick loading state on all instances
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
                val a = repo.getStatusText(ORIGIN_A, DEST_A, take = 3)
                val b = repo.getStatusText(ORIGIN_B, DEST_B, take = 3)
                "$a\n\n$b"
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
