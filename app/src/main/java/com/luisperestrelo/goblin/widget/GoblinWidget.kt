package com.luisperestrelo.goblin.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.luisperestrelo.goblin.MainActivity
import com.luisperestrelo.goblin.domain.model.Money
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Hilt bridge: Glance widgets aren't injected, so pull the repository this way. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetRepository(): com.luisperestrelo.goblin.data.repo.WidgetRepository
}

class GoblinWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
            .widgetRepository()
        val data = repository.loadWidgetData()

        provideContent {
            GlanceTheme {
                WidgetShell(data)
            }
        }
    }

    private companion object {
        val SMALL = DpSize(140.dp, 100.dp)
        val MEDIUM = DpSize(250.dp, 100.dp)
    }
}

@androidx.compose.runtime.Composable
private fun WidgetShell(data: WidgetData) {
    val context = LocalContext.current
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java)))
            .padding(14.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        when (data.status) {
            WidgetStatus.SETUP_REQUIRED -> MessageContent("goblin", "Open to set up")
            WidgetStatus.NEEDS_REAUTH -> MessageContent("Re-authorize", "Consent expired - tap to renew")
            WidgetStatus.READY -> {
                val wide = LocalSize.current.width >= MEDIUM_BREAKPOINT
                if (wide) MediumContent(data) else SmallContent(data)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SmallContent(data: WidgetData) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Label("...${data.accountLast4}")
        Spacer(GlanceModifier.height(2.dp))
        Text(
            data.balance.headline(),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(GlanceModifier.height(6.dp))
        DeltaLine(data)
    }
}

@androidx.compose.runtime.Composable
private fun MediumContent(data: WidgetData) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                data.balance.headline(),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 26.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.defaultWeight())
            Label("...${data.accountLast4}")
        }
        Spacer(GlanceModifier.height(6.dp))
        Text(
            "Spent ${data.spentThisWeek.orZero()}   Received ${data.receivedThisWeek.orZero()}",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
        )
        Spacer(GlanceModifier.height(2.dp))
        DeltaLine(data)
        Spacer(GlanceModifier.defaultWeight())
        Label(freshness(data))
    }
}

@androidx.compose.runtime.Composable
private fun DeltaLine(data: WidgetData) {
    val delta = data.spentDeltaVsLastWeek
    if (delta == null) {
        Text("this week", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
        return
    }
    val spentMore = delta.cents > 0
    val amount = Money(abs(delta.cents), delta.currency).formatted()
    val text = when {
        delta.cents == 0L -> "same as last week"
        spentMore -> "up $amount vs last week"
        else -> "down $amount vs last week"
    }
    val color = when {
        delta.cents == 0L -> GlanceTheme.colors.onSurfaceVariant
        spentMore -> ColorProvider(SPENT_MORE)
        else -> ColorProvider(SPENT_LESS)
    }
    Text(text, style = TextStyle(color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium))
}

@androidx.compose.runtime.Composable
private fun MessageContent(title: String, subtitle: String) {
    Column(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold))
        Spacer(GlanceModifier.height(4.dp))
        Text(subtitle, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
    }
}

@androidx.compose.runtime.Composable
private fun Label(text: String) {
    Text(text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
}

private fun Money?.headline(): String = this?.formatted() ?: "--"
private fun Money?.orZero(): String = (this ?: Money(0, "EUR")).formatted()

private fun freshness(data: WidgetData): String {
    val ms = data.lastSyncEpochMillis ?: return "not synced yet"
    val time = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)
    return if (data.isStale) "stale - synced $time" else "synced $time"
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val MEDIUM_BREAKPOINT = 200.dp
private val SPENT_MORE = Color(0xFFE57373)
private val SPENT_LESS = Color(0xFF66BB6A)
