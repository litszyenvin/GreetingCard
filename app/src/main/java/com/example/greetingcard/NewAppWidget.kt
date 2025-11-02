package com.example.greetingcard

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.example.greetingcard.data.TrainRepository
import com.example.greetingcard.widget.EXTRA_DEST
import com.example.greetingcard.widget.EXTRA_FAST_ONLY
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
        private const val ACTION_TOGGLE_FAST = "com.example.greetingcard.action.TOGGLE_FAST"
        private const val PREFS_NAME = "com.example.greetingcard.widget.PREFS"
        private const val PREF_FAST_ONLY = "pref_fast_only"
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Two routes
        private const val ORIGIN_A = "SAC"
        private const val DEST_A = "ZFD"
        private const val ORIGIN_B = "ZFD"
        private const val DEST_B = "SAC"
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val fastOnly = isFastOnlyEnabled(context)
        // Fill the widget area immediately with placeholders
        val routeAState = WidgetDataCache.get(ROUTE_ID_A, fastOnly)
            ?: loadingState("$ORIGIN_A → $DEST_A").also {
                WidgetDataCache.update(ROUTE_ID_A, fastOnly, it)
            }
        val routeBState = WidgetDataCache.get(ROUTE_ID_B, fastOnly)
            ?: loadingState("$ORIGIN_B → $DEST_B").also {
                WidgetDataCache.update(ROUTE_ID_B, fastOnly, it)
            }

        for (id in appWidgetIds) {
            val views = buildViews(context, id, routeAState, routeBState, fastOnly)
            manager.updateAppWidget(id, views)
        }
        fetchAndUpdateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH, AppWidgetManager.ACTION_APPWIDGET_UPDATE -> fetchAndUpdateAll(context)
            ACTION_TOGGLE_FAST -> {
                val newValue = !isFastOnlyEnabled(context)
                setFastOnlyEnabled(context, newValue)
                WidgetDataCache.clear()

                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, NewAppWidget::class.java))
                if (ids.isNotEmpty()) {
                    val routeAState = loadingState("$ORIGIN_A → $DEST_A")
                    val routeBState = loadingState("$ORIGIN_B → $DEST_B")
                    WidgetDataCache.update(ROUTE_ID_A, newValue, routeAState)
                    WidgetDataCache.update(ROUTE_ID_B, newValue, routeBState)

                    manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list_a)
                    manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list_b)

                    for (id in ids) {
                        val views = buildViews(context, id, routeAState, routeBState, newValue)
                        manager.updateAppWidget(id, views)
                    }
                }

                fetchAndUpdateAll(context)
            }
        }
    }

    private fun fetchAndUpdateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, NewAppWidget::class.java))
        if (ids.isEmpty()) return

        appScope.launch {
            val repo = TrainRepository()
            val fastOnly = isFastOnlyEnabled(context)

            val aRaw = runCatching { repo.getStatusText(ORIGIN_A, DEST_A, take = 8, fastOnly = fastOnly) }
                .getOrElse { "Error: ${it.message}" }
            val bRaw = runCatching { repo.getStatusText(ORIGIN_B, DEST_B, take = 8, fastOnly = fastOnly) }
                .getOrElse { "Error: ${it.message}" }

            val routeAState = parseWidgetRouteState(aRaw, "$ORIGIN_A → $DEST_A")
            val routeBState = parseWidgetRouteState(bRaw, "$ORIGIN_B → $DEST_B")

            WidgetDataCache.update(ROUTE_ID_A, fastOnly, routeAState)
            WidgetDataCache.update(ROUTE_ID_B, fastOnly, routeBState)

            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list_a)
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list_b)

            for (id in ids) {
                val views = buildViews(context, id, routeAState, routeBState, fastOnly)
                manager.updateAppWidget(id, views)
            }
        }
    }

    private fun buildViews(
        context: Context,
        appWidgetId: Int,
        routeA: WidgetRouteState,
        routeB: WidgetRouteState,
        fastOnly: Boolean
    ): RemoteViews {
        val toggleStateText = if (fastOnly) {
            context.getString(R.string.widget_fast_only_on)
        } else {
            context.getString(R.string.widget_fast_only_off)
        }
        val toggleBackground = if (fastOnly) {
            R.drawable.widget_chip_toggle_bg_checked
        } else {
            R.drawable.widget_chip_toggle_bg_unchecked
        }
        val toggleTextColor = if (fastOnly) {
            ContextCompat.getColor(context, android.R.color.white)
        } else {
            ContextCompat.getColor(context, R.color.widget_grey_accent)
        }
        return RemoteViews(context.packageName, R.layout.new_app_widget).apply {
            setTextViewText(R.id.route_a_title, routeA.title)
            setTextViewText(R.id.route_b_title, routeB.title)
            setTextViewText(R.id.widget_empty_a, routeA.emptyMessage)
            setTextViewText(R.id.widget_empty_b, routeB.emptyMessage)
            setRemoteAdapter(
                R.id.widget_list_a,
                serviceIntent(context, appWidgetId, ROUTE_ID_A, ORIGIN_A, DEST_A, fastOnly)
            )
            setRemoteAdapter(
                R.id.widget_list_b,
                serviceIntent(context, appWidgetId, ROUTE_ID_B, ORIGIN_B, DEST_B, fastOnly)
            )
            setEmptyView(R.id.widget_list_a, R.id.widget_empty_a)
            setEmptyView(R.id.widget_list_b, R.id.widget_empty_b)
            setOnClickPendingIntent(R.id.widget_root, suppressClickPI(context))
            setPendingIntentTemplate(R.id.widget_list_a, suppressClickPI(context))
            setPendingIntentTemplate(R.id.widget_list_b, suppressClickPI(context))
            setOnClickPendingIntent(R.id.widget_refresh, refreshPI(context))
            setTextViewText(R.id.widget_fast_toggle, toggleStateText)
            setInt(R.id.widget_fast_toggle, "setBackgroundResource", toggleBackground)
            setTextColor(R.id.widget_fast_toggle, toggleTextColor)
            setContentDescription(R.id.widget_fast_toggle, toggleStateText)
            setOnClickPendingIntent(R.id.widget_fast_toggle, toggleFastPI(context))
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
        dest: String,
        fastOnly: Boolean
    ): Intent {
        return Intent(context, WidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(EXTRA_ROUTE_ID, routeId)
            putExtra(EXTRA_ORIGIN, origin)
            putExtra(EXTRA_DEST, dest)
            putExtra(EXTRA_FAST_ONLY, fastOnly)
            data = Uri.parse("widget://${context.packageName}/$routeId/$appWidgetId/$fastOnly")
        }
    }

    private fun refreshPI(context: Context): PendingIntent {
        val intent = Intent(context, NewAppWidget::class.java).apply { action = ACTION_REFRESH }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun toggleFastPI(context: Context): PendingIntent {
        val intent = Intent(context, NewAppWidget::class.java).apply { action = ACTION_TOGGLE_FAST }
        return PendingIntent.getBroadcast(
            context, 2, intent,
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

    private fun isFastOnlyEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_FAST_ONLY, false)

    private fun setFastOnlyEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_FAST_ONLY, enabled)
            .apply()
    }
}
