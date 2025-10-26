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
 * Home-screen widget (v2) without any top-level functions,
 * so it won't clash with the template's original NewAppWidget.kt.
 */
class NewAppWidget2 : AppWidgetProvider() {

    companion object {
        private const val DEFAULT_ORIGIN = "SAC"
        private const val DEFAULT_DEST = "ZFD"
        private const val ACTION_REFRESH_2 = "com.example.greetingcard.action.REFRESH2"
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Show loading state
        for (id in appWidgetIds) {
            val views = buildViews(context, "Loading…")
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context))
            appWidgetManager.updateAppWidget(id, views)
        }
        fetchAndUpdateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_2 || intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            fetchAndUpdateAll(context)
        }
    }

    private fun fetchAndUpdateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, NewAppWidget2::class.java))
        if (ids.isEmpty()) return

        appScope.launch {
            val repo = TrainRepository()
            val text = try {
                repo.getStatusText(DEFAULT_ORIGIN, DEFAULT_DEST, take = 4)
            } catch (e: Exception) {
                "Error: ${e.message ?: "unknown"}"
            }

            val views = buildViews(context, text)
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context))
            manager.updateAppWidget(ids, views)
        }
    }

    private fun buildViews(context: Context, body: String): RemoteViews {
        return RemoteViews(context.packageName, R.layout.new_app_widget2).apply {
            setTextViewText(R.id.widget_title, context.getString(R.string.widget_title))
            setTextViewText(R.id.widget_text, body)
        }
    }

    private fun refreshPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, NewAppWidget2::class.java).apply { action = ACTION_REFRESH_2 }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
