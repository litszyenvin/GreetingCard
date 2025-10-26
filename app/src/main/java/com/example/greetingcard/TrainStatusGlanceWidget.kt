package com.example.greetingcard

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greetingcard.data.TrainRepository

class TrainStatusGlanceWidget : GlanceAppWidget() {

    companion object {
        private const val ORIGIN_A = "SAC"
        private const val DEST_A = "ZFD"
        private const val ORIGIN_B = "ZFD"
        private const val DEST_B = "SAC"
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = TrainRepository()

        val aRaw = runCatching { repository.getStatusText(ORIGIN_A, DEST_A, take = 4) }
            .getOrElse { "Error: ${it.message}" }
        val bRaw = runCatching { repository.getStatusText(ORIGIN_B, DEST_B, take = 4) }
            .getOrElse { "Error: ${it.message}" }

        val (aTitle, aBody) = splitHeaderBodyOrFallback(aRaw, "$ORIGIN_A → $DEST_A")
        val (bTitle, bBody) = splitHeaderBodyOrFallback(bRaw, "$ORIGIN_B → $DEST_B")

        val widgetTitle = context.getString(R.string.widget_title)
        val refreshLabel = context.getString(R.string.widget_refresh)

        provideContent {
            TrainStatusGlanceContent(
                widgetTitle = widgetTitle,
                routeA = RouteSection(aTitle, aBody),
                routeB = RouteSection(bTitle, bBody),
                refreshLabel = refreshLabel
            )
        }
    }

    private fun splitHeaderBodyOrFallback(text: String, fallbackTitle: String): Pair<String, String> {
        val lines = text.lines()
        val header = lines.firstOrNull()?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val body = lines.drop(1).joinToString("\n").ifBlank { "No services found." }
        val normalizedHeader = header.removePrefix("Station: ").trim().ifBlank { fallbackTitle }
        return normalizedHeader to body
    }

    private data class RouteSection(val title: String, val body: String)

    @Composable
    private fun TrainStatusGlanceContent(
        widgetTitle: String,
        routeA: RouteSection,
        routeB: RouteSection,
        refreshLabel: String
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .padding(12.dp)
        ) {
            Text(
                text = widgetTitle,
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
            )

            Spacer(modifier = GlanceModifier.height(8.dp))

            RouteSectionContent(routeA)

            DividerSpacer()

            RouteSectionContent(routeB)

            Spacer(modifier = GlanceModifier.height(12.dp))

            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Horizontal.End
            ) {
                Text(
                    text = refreshLabel,
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    modifier = GlanceModifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clickable(actionRunCallback<RefreshTrainStatusAction>())
                )
            }
        }
    }

    @Composable
    private fun RouteSectionContent(section: RouteSection) {
        Text(
            text = section.title,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
        Text(
            text = section.body,
            style = TextStyle(fontSize = 13.sp)
        )
    }

    @Composable
    private fun DividerSpacer() {
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp)
                .height(1.dp)
                .background(ColorProvider(day = Color(0x22000000), night = Color(0x22FFFFFF)))
        ) { }
    }
}

class TrainStatusGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TrainStatusGlanceWidget()
}

class RefreshTrainStatusAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        TrainStatusGlanceWidget().update(context, glanceId)
    }
}
