package com.example.greetingcard.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.greetingcard.R
import com.example.greetingcard.data.TrainRepository
import kotlinx.coroutines.runBlocking

class WidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return RouteRemoteViewsFactory(applicationContext, intent)
    }
}

private class RouteRemoteViewsFactory(
    private val context: Context,
    intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private val routeId: String = intent.getStringExtra(EXTRA_ROUTE_ID) ?: ROUTE_ID_A
    private val origin: String = intent.getStringExtra(EXTRA_ORIGIN) ?: ""
    private val dest: String = intent.getStringExtra(EXTRA_DEST) ?: ""

    private var items: List<WidgetServiceItem> = emptyList()

    override fun onCreate() {
        // No-op
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun onDataSetChanged() {
        val cached = WidgetDataCache.get(routeId)
        if (cached != null) {
            items = cached.services
            return
        }

        if (origin.isBlank() || dest.isBlank()) {
            items = emptyList()
            return
        }

        val repo = TrainRepository()
        val raw = runCatching {
            runBlocking { repo.getStatusText(origin, dest, take = 8) }
        }.getOrElse { "Error: ${it.message}" }

        val parsed = parseWidgetRouteState(raw, "$origin → $dest")
        WidgetDataCache.update(routeId, parsed)
        items = parsed.services
    }

    override fun getViewAt(position: Int): RemoteViews? {
        if (position < 0 || position >= items.size) return null
        val item = items[position]
        return RemoteViews(context.packageName, R.layout.widget_service_item).apply {
            setTextViewText(R.id.widget_item_line1, item.line1)
            setTextViewText(R.id.widget_item_line2, item.line2)
        }
    }

    override fun getCount(): Int = items.size

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = true
}
