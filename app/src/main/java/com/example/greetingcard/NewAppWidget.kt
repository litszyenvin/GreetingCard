package com.example.greetingcard

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.example.greetingcard.data.TrainRepository
import com.example.greetingcard.widget.EXTRA_DEST
import com.example.greetingcard.widget.EXTRA_ORIGIN
import com.example.greetingcard.widget.EXTRA_ROUTE_ID
import com.example.greetingcard.widget.ROUTE_ID_A
import com.example.greetingcard.widget.ROUTE_ID_B
import com.example.greetingcard.widget.WidgetDataCache
import com.example.greetingcard.widget.WidgetRouteState
import com.example.greetingcard.widget.parseWidgetRouteState
import com.example.greetingcard.widget.WidgetService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NewAppWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_REFRESH = "com.example.greetingcard.action.REFRESH"
        private const val ACTION_SUPPRESS_CLICK = "com.example.greetingcard.action.SUPPRESS"
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Two routes
        private const val ORIGIN_A = "SAC"
        private const val DEST_A = "ZFD"
        private const val ORIGIN_B = "ZFD"
        private const val DEST_B = "SAC"
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        // Fill the widget area immediately with placeholders
        val routeAState = WidgetDataCache.get(ROUTE_ID_A)
            ?: loadingState("$ORIGIN_A → $DEST_A").also { WidgetDataCache.update(ROUTE_ID_A, it) }
        val routeBState = WidgetDataCache.get(ROUTE_ID_B)
            ?: loadingState("$ORIGIN_B → $DEST_B").also { WidgetDataCache.update(ROUTE_ID_B, it) }

        for (id in appWidgetIds) {
            val views = buildViews(context, id, routeAState, routeBState)
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

            val aRaw = runCatching { repo.getStatusText(ORIGIN_A, DEST_A, take = 8) }
                .getOrElse { "Error: ${it.message}" }
            val bRaw = runCatching { repo.getStatusText(ORIGIN_B, DEST_B, take = 8) }
                .getOrElse { "Error: ${it.message}" }

            val routeAState = parseWidgetRouteState(aRaw, "$ORIGIN_A → $DEST_A")
            val routeBState = parseWidgetRouteState(bRaw, "$ORIGIN_B → $DEST_B")

            WidgetDataCache.update(ROUTE_ID_A, routeAState)
            WidgetDataCache.update(ROUTE_ID_B, routeBState)

            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list_a)
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list_b)

            for (id in ids) {
                val views = buildViews(context, id, routeAState, routeBState)
                manager.updateAppWidget(id, views)
            }
        }
    }

    private fun buildViews(
        context: Context,
        appWidgetId: Int,
        routeA: WidgetRouteState,
        routeB: WidgetRouteState
    ): RemoteViews {
        return RemoteViews(context.packageName, R.layout.new_app_widget).apply {
            setTextViewText(R.id.route_a_title, routeA.title)
            setTextViewText(R.id.route_b_title, routeB.title)
            setTextViewText(R.id.widget_empty_a, routeA.emptyMessage)
            setTextViewText(R.id.widget_empty_b, routeB.emptyMessage)
            setRemoteAdapter(R.id.widget_list_a, serviceIntent(context, appWidgetId, ROUTE_ID_A, ORIGIN_A, DEST_A))
            setRemoteAdapter(R.id.widget_list_b, serviceIntent(context, appWidgetId, ROUTE_ID_B, ORIGIN_B, DEST_B))
            setEmptyView(R.id.widget_list_a, R.id.widget_empty_a)
            setEmptyView(R.id.widget_list_b, R.id.widget_empty_b)
            setOnClickPendingIntent(R.id.widget_root, suppressClickPI(context))
            setPendingIntentTemplate(R.id.widget_list_a, suppressClickPI(context))
            setPendingIntentTemplate(R.id.widget_list_b, suppressClickPI(context))
            setOnClickPendingIntent(R.id.widget_refresh, refreshPI(context))
        }
    }

    private fun loadingState(title: String) = WidgetRouteState(
        title = title,
        services = emptyList(),
        emptyMessage = "Loading…"
    )

    private fun serviceIntent(
        context: Context,
        appWidgetId: Int,
        routeId: String,
        origin: String,
        dest: String
    ): Intent {
        return Intent(context, WidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(EXTRA_ROUTE_ID, routeId)
            putExtra(EXTRA_ORIGIN, origin)
            putExtra(EXTRA_DEST, dest)
            data = Uri.parse("widget://${context.packageName}/$routeId/$appWidgetId")
        }
    }

    private fun refreshPI(context: Context): PendingIntent {
        val intent = Intent(context, NewAppWidget::class.java).apply { action = ACTION_REFRESH }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun suppressClickPI(context: Context): PendingIntent {
        val intent = Intent(context, NewAppWidget::class.java).apply { action = ACTION_SUPPRESS_CLICK }
        return PendingIntent.getBroadcast(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
