/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.ui.compose

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.text.format.DateUtils
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import net.kollnig.missioncontrol.R
import net.kollnig.missioncontrol.data.InsightsData
import net.kollnig.missioncontrol.data.TimelineEntry
import net.kollnig.missioncontrol.data.TrackerCategory
import java.text.NumberFormat

/** The four reasons an empty Timeline can be shown. */
enum class TimelineEmptyState {
    TRACKER_CONTROL_OFF,
    RECORDING_OFF,
    RECORDING_UNAVAILABLE,
    WATCHING
}

/** Immutable state for the Insights summary at the top of the Timeline. */
sealed interface TimelineInsightsState {
    data object Loading : TimelineInsightsState

    @Immutable
    data class Populated(val data: TimelineInsightsData) : TimelineInsightsState
}

@Immutable
data class TimelineInsightsData(
    val totalTrackingAttempts: Int,
    val blockedTrackingAttempts: Int,
    val allowedTrackingAttempts: Int,
    val uniqueTrackerCompanies: Int,
    val blockedPercentage: Int,
    val allowedPercentage: Int
) {
    companion object {
        @JvmStatic
        fun from(data: InsightsData): TimelineInsightsData = TimelineInsightsData(
            totalTrackingAttempts = data.totalTrackingAttempts,
            blockedTrackingAttempts = data.blockedTrackingAttempts,
            allowedTrackingAttempts = data.allowedTrackingAttempts,
            uniqueTrackerCompanies = data.uniqueTrackerCompanies,
            blockedPercentage = data.getBlockedPercentage(),
            allowedPercentage = 100 - data.getBlockedPercentage()
        )
    }
}

/** Immutable tracker-contact row shown below an app. */
@Immutable
data class TimelineTrackerContact(
    val key: String,
    val companyName: String,
    val category: String?,
    val blocked: Boolean,
    val statusLabel: String
) {
    val displayName: String
        get() = if (category.isNullOrBlank()) companyName else "$companyName · $category"
}

/** Stable-keyed rows consumed by the single Timeline LazyColumn. */
sealed interface TimelineRow {
    val key: String

    @Immutable
    data class Section(
        override val key: String,
        val title: String
    ) : TimelineRow

    @Immutable
    data class App(
        override val key: String,
        val sectionKey: String,
        val uid: Int,
        val appName: String,
        val packageName: String?,
        val relativeTime: String,
        val summary: String,
        val blockedCount: Int,
        val allowedCount: Int,
        val contacts: List<TimelineTrackerContact>,
        val overflowCount: Int
    ) : TimelineRow {
        val clickable: Boolean
            get() = packageName != null
    }
}

@Immutable
data class TimelineScreenModel(
    val insights: TimelineInsightsState,
    val emptyState: TimelineEmptyState?,
    val showHint: Boolean,
    val rows: List<TimelineRow>,
    val isRefreshing: Boolean = false,
    val protectionEnabled: Boolean = false,
    val featurePageTitle: String? = null
) {
    companion object {
        @JvmStatic
        fun initial(): TimelineScreenModel = TimelineScreenModel(
            insights = TimelineInsightsState.Loading,
            emptyState = null,
            showHint = false,
            rows = emptyList(),
            isRefreshing = false
        )

        /** Initial model seeded from ActivityMain's persisted protection preference. */
        @JvmStatic
        fun initial(protectionEnabled: Boolean): TimelineScreenModel =
            initial().copy(protectionEnabled = protectionEnabled)
    }
}

/** Callbacks for actions whose implementation remains in TimelineFragment. */
interface TimelineScreenCallbacks {
    fun onEntryClick(uid: Int, appName: String, packageName: String)
    fun onOpenApp()
    fun onOpenSettings()
    fun onOpenInsights()
    fun onShareInsights()
    fun onDismissHint()

    /** Pull-to-refresh gesture; rebuilds the timeline and the insights card. */
    fun onRefresh()

    /** Request a protection-state change through ActivityMain's existing VPN flow. */
    fun onProtectionToggle(enabled: Boolean)

    /** Open a separate, intentionally empty feature destination inside Main. */
    fun onOpenFeaturePage(title: String)

    /** Return from a feature destination to the retained Main dashboard and Timeline. */
    fun onCloseFeaturePage()
}

/** Handle used by Java to update Compose while retaining controller lifecycle ownership. */
class TimelineScreenController internal constructor(
    private val state: MutableState<TimelineScreenModel>
) {
    @Volatile
    private var valid = true

    fun updateTimeline(
        entries: List<TimelineEntry>,
        context: Context,
        emptyState: TimelineEmptyState?,
        showHint: Boolean
    ) {
        if (!valid) return
        state.value = state.value.copy(
            emptyState = if (entries.isEmpty()) emptyState else null,
            showHint = showHint,
            rows = timelineRows(entries, context),
            // The rebuilt list is the completion signal for a pull-to-refresh,
            // as it was for the SwipeRefreshLayout this screen replaced.
            isRefreshing = false
        )
    }

    /** Show or hide the pull-to-refresh indicator. */
    fun setRefreshing(refreshing: Boolean) {
        if (!valid) return
        state.value = state.value.copy(isRefreshing = refreshing)
    }

    fun updateInsights(data: InsightsData) {
        if (!valid) return
        state.value = state.value.copy(
            insights = TimelineInsightsState.Populated(TimelineInsightsData.from(data))
        )
    }

    fun dismissHint() {
        if (!valid) return
        state.value = state.value.copy(showHint = false)
    }

    fun updateProtectionEnabled(enabled: Boolean) {
        if (!valid) return
        state.value = state.value.copy(protectionEnabled = enabled)
    }

    fun openFeaturePage(title: String) {
        if (!valid) return
        state.value = state.value.copy(featurePageTitle = title)
    }

    fun closeFeaturePage(): Boolean {
        if (!valid || state.value.featurePageTitle == null)
            return false
        state.value = state.value.copy(featurePageTitle = null)
        return true
    }

    fun invalidate() {
        valid = false
    }
}

/** Java-facing entry point for the migrated Timeline screen. */
object TimelineScreen {
    @JvmStatic
    fun install(
        composeView: ComposeView,
        callbacks: TimelineScreenCallbacks
    ): TimelineScreenController = install(composeView, TimelineScreenModel.initial(), callbacks)

    @JvmStatic
    fun install(
        composeView: ComposeView,
        initialModel: TimelineScreenModel,
        callbacks: TimelineScreenCallbacks
    ): TimelineScreenController {
        val state = mutableStateOf(initialModel)
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            TrackerControlTheme {
                TimelineScreenContent(state.value, callbacks)
            }
        }
        return TimelineScreenController(state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimelineScreenContent(
    model: TimelineScreenModel,
    callbacks: TimelineScreenCallbacks
) {
    val iconCache = remember { mutableMapOf<String, Drawable?>() }
    CompositionLocalProvider(LocalAppIconCache provides iconCache) {
        Box(modifier = Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = model.isRefreshing,
                onRefresh = callbacks::onRefresh,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .then(
                        if (model.featurePageTitle != null)
                            Modifier.clearAndSetSemantics { }
                        else
                            Modifier
                    )
            ) {
                TimelineList(model, callbacks)
            }

            // Keep the Timeline mounted underneath this destination so its scroll
            // position, refresh state and loaded data survive Back navigation.
            // Clear the covered list's semantics while the page is open so
            // accessibility and tests interact only with the active destination.
            model.featurePageTitle?.let { title ->
                FeatureDestinationPage(title, callbacks)
            }
        }
    }
}

@Composable
private fun TimelineList(
    model: TimelineScreenModel,
    callbacks: TimelineScreenCallbacks
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item(key = "vianshield-dashboard") {
            VianshieldDashboard(model.protectionEnabled, callbacks)
        }

        item(key = "timeline-heading") {
            Text(
                text = stringResource(R.string.dashboard_timeline_heading),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics { heading() }
                    .testTag("timeline-section-heading"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        item(key = "insights-summary") {
            TimelineInsightsCard(model.insights, callbacks)
        }

        model.emptyState?.let { emptyState ->
            item(key = "timeline-empty") {
                TimelineEmptyContent(emptyState, callbacks)
            }
        }

        if (model.showHint) {
            item(key = "timeline-hint") {
                TimelineHintContent(callbacks)
            }
        }

        model.rows.forEachIndexed { index, row ->
            item(key = row.key) {
                when (row) {
                    is TimelineRow.Section -> DetailsSectionHeading(text = row.title)
                    is TimelineRow.App -> {
                        val next = model.rows.getOrNull(index + 1)
                        val showDivider = next is TimelineRow.App &&
                            next.sectionKey == row.sectionKey
                        TimelineAppRow(
                            row = row,
                            showDivider = showDivider,
                            onClick = {
                                row.packageName?.let { packageName ->
                                    callbacks.onEntryClick(row.uid, row.appName, packageName)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineInsightsCard(
    state: TimelineInsightsState,
    callbacks: TimelineScreenCallbacks
) {
    val insightsTitle = stringResource(R.string.insights_title)
    when (state) {
        TimelineInsightsState.Loading -> Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { contentDescription = insightsTitle },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .testTag("timeline-insights-loading"),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        is TimelineInsightsState.Populated -> {
            val data = state.data
            val locale = LocalLocale.current.platformLocale
            val numberFormat = NumberFormat.getNumberInstance(locale)
            val percentFormat = NumberFormat.getPercentInstance(locale).apply {
                maximumFractionDigits = 0
            }
            val totalText = numberFormat.format(data.totalTrackingAttempts)
            val blockedText = numberFormat.format(data.blockedTrackingAttempts)
            val allowedText = numberFormat.format(data.allowedTrackingAttempts)
            val blockedPercentageText = percentFormat.format(data.blockedPercentage / 100f)
            val seeMoreLabel = stringResource(R.string.insights_see_more)
            val shareLabel = stringResource(R.string.insights_share)
            val totalDescription = stringResource(
                R.string.accessibility_stat_description,
                totalText,
                stringResource(R.string.insights_tracking_attempts)
            )
            val companiesText = numberFormat.format(data.uniqueTrackerCompanies)
            val companiesDescription = stringResource(
                R.string.accessibility_stat_description,
                companiesText,
                stringResource(R.string.insights_tracker_companies)
            )
            val percentageDescription = stringResource(
                R.string.accessibility_stat_description,
                blockedPercentageText,
                stringResource(R.string.insights_blocked)
            )
            val statusText = stringResource(
                R.string.timeline_insights_status,
                blockedText,
                stringResource(R.string.insights_blocked),
                allowedText,
                stringResource(R.string.insights_allowed)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clickable(
                        role = Role.Button,
                        onClick = callbacks::onOpenInsights
                    )
                    .testTag("timeline-insights-card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_shield_check),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(R.string.insights_subtitle_7days),
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        // No size() here: IconButton's own 48dp minimum touch
                        // target must survive; the glyph is sized on the Icon.
                        IconButton(
                            onClick = callbacks::onShareInsights,
                            modifier = Modifier.semantics { contentDescription = shareLabel }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_ios_share),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = totalDescription
                                }
                        ) {
                            Text(
                                text = totalText,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.insights_tracking_attempts),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        // Middle stat of the pre-Compose hero row: how many
                        // distinct tracking companies the week's contacts came
                        // from, between the attempt count and the blocked share.
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = companiesDescription
                                }
                        ) {
                            Text(
                                text = companiesText,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.insights_tracker_companies),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Column(
                            modifier = Modifier.semantics(mergeDescendants = true) {
                                contentDescription = percentageDescription
                            },
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = blockedPercentageText,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.insights_blocked),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    TimelineProgressBar(
                        progress = data.blockedPercentage / 100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .height(6.dp)
                            .testTag("timeline-insights-progress"),
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surface
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = statusText,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = statusText },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = seeMoreLabel,
                            modifier = Modifier.padding(start = 12.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/**
 * Vianshield's Main dashboard. The protection control and four feature
 * destinations live in this same LazyColumn as the Timeline, not in a nested
 * scrolling container.
 */
@Composable
private fun VianshieldDashboard(
    protectionEnabled: Boolean,
    callbacks: TimelineScreenCallbacks
) {
    val protectionDescription = stringResource(
        if (protectionEnabled) R.string.dashboard_protection_on_description
        else R.string.dashboard_protection_off_description
    )
    val protectionAccent = if (protectionEnabled) Color(0xFF218B4B) else Color(0xFF64748B)
    val protectionGlow = Brush.radialGradient(
        colors = if (protectionEnabled) {
            listOf(Color(0x332D9A59), Color(0x172D9A59), Color.Transparent)
        } else {
            listOf(Color(0x160F172A), Color(0x080F172A), Color.Transparent)
        }
    )
    val protectionFill = Brush.radialGradient(
        colors = if (protectionEnabled) {
            listOf(Color.White, Color(0xFFF5FBF6), Color(0xFFE5F2E9))
        } else {
            listOf(Color.White, Color(0xFFF8FAFC), Color(0xFFEEF2F5))
        }
    )
    val protectionBorder = if (protectionEnabled) {
        Brush.linearGradient(listOf(Color(0xFF278D4E), Color(0xFF8BC99D)))
    } else {
        Brush.linearGradient(listOf(Color(0xFF8B99A7), Color(0xFFD3DAE0)))
    }
    val speedThrottlingTitle = stringResource(R.string.dashboard_speed_throttling)
    val dnsChangerTitle = stringResource(R.string.dashboard_dns_changer)
    val adFilterTitle = stringResource(R.string.dashboard_ad_filter)
    val blockedDomainsTitle = stringResource(R.string.dashboard_blocked_domains)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // A soft halo surrounds a white, outlined power button, matching the
        // lighter visual language of the reference dashboard.
        Box(
            modifier = Modifier
                .size(152.dp)
                .background(protectionGlow, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(136.dp)
                    .shadow(elevation = 8.dp, shape = CircleShape)
                    .clip(CircleShape)
                    .background(protectionFill)
                    .border(2.dp, protectionBorder, CircleShape)
                    .toggleable(
                        value = protectionEnabled,
                        role = Role.Switch,
                        onValueChange = callbacks::onProtectionToggle
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = protectionDescription
                    }
                    .testTag("dashboard-protection-toggle"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_power_settings_new),
                    contentDescription = null,
                    tint = protectionAccent,
                    modifier = Modifier.size(52.dp)
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DashboardFeatureCard(
                    title = speedThrottlingTitle,
                    iconRes = R.drawable.ic_speedometer_dashboard,
                    iconTint = Color(0xFF3288D8),
                    testTag = "dashboard-card-speed-throttling",
                    modifier = Modifier.weight(1f),
                    onClick = { callbacks.onOpenFeaturePage(speedThrottlingTitle) }
                )
                DashboardFeatureCard(
                    title = dnsChangerTitle,
                    iconRes = R.drawable.ic_dns_dashboard,
                    iconTint = Color(0xFF16A6A1),
                    testTag = "dashboard-card-dns-changer",
                    modifier = Modifier.weight(1f),
                    onClick = { callbacks.onOpenFeaturePage(dnsChangerTitle) }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DashboardFeatureCard(
                    title = adFilterTitle,
                    iconRes = R.drawable.ic_filter_dashboard,
                    iconTint = Color(0xFF8B5CF6),
                    testTag = "dashboard-card-ad-filter",
                    modifier = Modifier.weight(1f),
                    onClick = { callbacks.onOpenFeaturePage(adFilterTitle) }
                )
                DashboardFeatureCard(
                    title = blockedDomainsTitle,
                    iconRes = R.drawable.ic_block_dashboard,
                    iconTint = Color(0xFFE15B64),
                    testTag = "dashboard-card-blocked-domains",
                    modifier = Modifier.weight(1f),
                    onClick = { callbacks.onOpenFeaturePage(blockedDomainsTitle) }
                )
            }
        }
    }
}

@Composable
private fun DashboardFeatureCard(
    title: String,
    iconRes: Int,
    iconTint: Color,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Card(
        modifier = modifier
            .height(84.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(testTag),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = Color.White,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, Color(0xFFE9EEF1))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Empty by design for Phase 6: a title and Back affordance, with no fake controls. */
@Composable
private fun FeatureDestinationPage(
    title: String,
    callbacks: TimelineScreenCallbacks
) {
    val backDescription = stringResource(R.string.dashboard_back)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Intercept taps/drags on the blank destination background without
            // adding a no-op clickable semantics node that would merge the title.
            .pointerInput(Unit) {
                detectTapGestures(onTap = {})
            }
            .testTag("dashboard-feature-page")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = callbacks::onCloseFeaturePage,
                modifier = Modifier
                    .testTag("dashboard-feature-back")
                    .semantics { contentDescription = backDescription }
            ) {
                Text(
                    text = "←",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = title,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
private fun TimelineProgressBar(
    progress: Float,
    indicatorColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier
) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(clampedProgress, 0f..1f)
            }
            .clip(RoundedCornerShape(3.dp))
            .background(trackColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clampedProgress)
                .fillMaxHeight()
                .background(indicatorColor)
        )
    }
}

@Composable
private fun TimelineEmptyContent(
    state: TimelineEmptyState,
    callbacks: TimelineScreenCallbacks
) {
    val (title, subtitle) = when (state) {
        TimelineEmptyState.TRACKER_CONTROL_OFF ->
            stringResource(R.string.timeline_empty_disabled_title) to
                stringResource(R.string.timeline_empty_disabled_subtitle)
        TimelineEmptyState.RECORDING_OFF ->
            stringResource(R.string.timeline_empty_recording_off_title) to
                stringResource(R.string.timeline_empty_recording_off_subtitle)
        TimelineEmptyState.RECORDING_UNAVAILABLE ->
            stringResource(R.string.timeline_empty_recording_unavailable_title) to
                stringResource(R.string.timeline_empty_recording_unavailable_subtitle)
        TimelineEmptyState.WATCHING ->
            stringResource(R.string.timeline_empty_enabled_title) to
                stringResource(R.string.timeline_empty_enabled_subtitle)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 28.dp)
            .semantics { contentDescription = "$title. $subtitle" },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = subtitle,
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        when (state) {
            TimelineEmptyState.RECORDING_OFF -> Button(
                onClick = callbacks::onOpenSettings,
                modifier = Modifier
                    .padding(top = 20.dp)
                    .testTag("timeline-empty-open-settings")
            ) {
                Text(stringResource(R.string.timeline_open_settings))
            }
            TimelineEmptyState.WATCHING -> Button(
                onClick = callbacks::onOpenApp,
                modifier = Modifier
                    .padding(top = 20.dp)
                    .testTag("timeline-empty-open-app")
            ) {
                Text(stringResource(R.string.timeline_open_app))
            }
            else -> Unit
        }
    }
}

@Composable
private fun TimelineHintContent(callbacks: TimelineScreenCallbacks) {
    val hintText = stringResource(R.string.timeline_hint_tap_entry)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .semantics { contentDescription = hintText },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.timeline_hint_tap_entry),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            OutlinedButton(
                onClick = callbacks::onDismissHint,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 8.dp)
                    .testTag("timeline-hint-dismiss")
            ) {
                Text(stringResource(R.string.timeline_hint_dismiss))
            }
        }
    }
}

@Composable
private fun TimelineAppRow(
    row: TimelineRow.App,
    showDivider: Boolean,
    onClick: () -> Unit
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .then(
            if (row.clickable) {
                Modifier.clickable(role = Role.Button, onClick = onClick)
            } else {
                Modifier
            }
        )
        .semantics(mergeDescendants = true) {
            if (row.clickable) role = Role.Button
        }

    Column(
        modifier = rowModifier
            .testTag("timeline-app-${row.uid}")
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TimelineAppIcon(row.packageName)
            Text(
                text = row.appName,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = row.relativeTime,
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = row.summary,
            modifier = Modifier.padding(start = 48.dp, top = 2.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(
            modifier = Modifier.padding(start = 48.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            row.contacts.forEach { contact ->
                TimelineTrackerRow(contact)
            }
            if (row.overflowCount > 0) {
                Text(
                    text = stringResource(R.string.timeline_more_trackers, row.overflowCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .testTag("timeline-divider"),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun TimelineTrackerRow(contact: TimelineTrackerContact) {
    val statusColour = if (contact.blocked) {
        colorResource(R.color.timeline_blocked)
    } else {
        colorResource(R.color.timeline_allowed)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = contact.statusLabel,
            modifier = Modifier.padding(end = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = statusColour,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = contact.displayName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Per-screen icon cache. The Timeline re-renders every 30 seconds, and without
 * this every visible row would hit the PackageManager again on each tick.
 * Scoped to the composition (rather than a process-wide map) so the drawables
 * are released with the screen.
 */
private val LocalAppIconCache = staticCompositionLocalOf<MutableMap<String, Drawable?>> {
    mutableMapOf()
}

@Composable
private fun TimelineAppIcon(packageName: String?) {
    val iconCache = LocalAppIconCache.current
    AndroidView(
        factory = { viewContext ->
            ImageView(viewContext).apply {
                importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        },
        update = { imageView ->
            val icon = packageName?.let { name ->
                if (iconCache.containsKey(name)) {
                    iconCache[name]
                } else {
                    findAppIcon(imageView.context, name).also { iconCache[name] = it }
                }
            }
            if (icon != null) {
                imageView.setImageDrawable(icon)
            } else {
                imageView.setImageResource(android.R.drawable.sym_def_app_icon)
            }
        },
        modifier = Modifier.size(36.dp)
    )
}

private fun findAppIcon(context: Context, packageName: String): Drawable? = try {
    val applicationInfo = context.packageManager.getApplicationInfo(packageName, 0)
    context.packageManager.getApplicationIcon(applicationInfo)
} catch (_: PackageManager.NameNotFoundException) {
    null
}

private fun timelineRows(entries: List<TimelineEntry>, context: Context): List<TimelineRow> {
    if (entries.isEmpty()) return emptyList()

    val now = System.currentTimeMillis()
    val oneHourAgo = now - 60 * 60 * 1000L
    val startOfToday = startOfDay(0)
    val startOfYesterday = startOfDay(1)
    val rows = mutableListOf<TimelineRow>()
    var currentSection: String? = null
    var currentSectionKey: String? = null

    entries.forEach { entry ->
        val sectionTitle = when {
            entry.mostRecentTime >= oneHourAgo -> context.getString(R.string.timeline_section_last_hour)
            entry.mostRecentTime >= startOfToday -> context.getString(R.string.timeline_section_today)
            entry.mostRecentTime >= startOfYesterday -> context.getString(R.string.timeline_section_yesterday)
            else -> context.getString(R.string.timeline_section_this_week)
        }
        if (sectionTitle != currentSection) {
            currentSection = sectionTitle
            currentSectionKey = "section:$sectionTitle"
            rows += TimelineRow.Section(currentSectionKey!!, sectionTitle)
        }

        val blockedCount = entry.getBlockedCount()
        val allowedCount = entry.getAllowedCount()
        val summary = when {
            blockedCount > 0 && allowedCount > 0 ->
                context.getString(R.string.timeline_summary_mixed, blockedCount, allowedCount)
            blockedCount > 0 ->
                context.resources.getQuantityString(
                    R.plurals.timeline_trackers_blocked,
                    blockedCount,
                    blockedCount
                )
            else ->
                context.resources.getQuantityString(
                    R.plurals.timeline_trackers_allowed,
                    allowedCount,
                    allowedCount
                )
        }
        val contacts = entry.trackers.take(3).map { tracker ->
            val statusLabel = context.getString(
                if (tracker.blocked) R.string.timeline_tracker_blocked
                else R.string.timeline_tracker_allowed
            )
            TimelineTrackerContact(
                key = "contact:${entry.uid}:${tracker.companyName}:${tracker.blocked}",
                companyName = tracker.companyName,
                category = TrackerCategory.getDisplayName(context, tracker.category),
                blocked = tracker.blocked,
                statusLabel = statusLabel
            )
        }
        val relativeTime = DateUtils.getRelativeTimeSpanString(
            entry.mostRecentTime,
            now,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
        rows += TimelineRow.App(
            key = "entry:${entry.uid}",
            sectionKey = currentSectionKey!!,
            uid = entry.uid,
            appName = entry.appName,
            packageName = entry.packageName,
            relativeTime = relativeTime,
            summary = summary,
            blockedCount = blockedCount,
            allowedCount = allowedCount,
            contacts = contacts,
            overflowCount = (entry.trackers.size - 3).coerceAtLeast(0)
        )
    }
    return rows
}

private fun startOfDay(daysAgo: Int): Long {
    val calendar = java.util.Calendar.getInstance()
    calendar.add(java.util.Calendar.DAY_OF_YEAR, -daysAgo)
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
    calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0)
    calendar.set(java.util.Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

@Preview(showBackground = true)
@Composable
private fun TimelineScreenPreview() {
    TrackerControlTheme {
        TimelineScreenContent(
            model = TimelineScreenModel(
                insights = TimelineInsightsState.Populated(
                    TimelineInsightsData(12, 9, 3, 4, 75, 25)
                ),
                emptyState = null,
                showHint = true,
                rows = listOf(
                    TimelineRow.Section("section-preview", "Today"),
                    TimelineRow.App(
                        key = "app-preview",
                        sectionKey = "section-preview",
                        uid = 10001,
                        appName = "Example app",
                        packageName = "com.example.app",
                        relativeTime = "3 min ago",
                        summary = "2 trackers blocked · 1 allowed",
                        blockedCount = 2,
                        allowedCount = 1,
                        contacts = listOf(
                            TimelineTrackerContact(
                                key = "contact-preview",
                                companyName = "Example Analytics",
                                category = "Advertising",
                                blocked = true,
                                statusLabel = "Blocked"
                            )
                        ),
                        overflowCount = 0
                    )
                )
            ),
            callbacks = PreviewTimelineCallbacks
        )
    }
}

private object PreviewTimelineCallbacks : TimelineScreenCallbacks {
    override fun onEntryClick(uid: Int, appName: String, packageName: String) = Unit
    override fun onOpenApp() = Unit
    override fun onOpenSettings() = Unit
    override fun onOpenInsights() = Unit
    override fun onShareInsights() = Unit
    override fun onDismissHint() = Unit
    override fun onRefresh() = Unit
    override fun onProtectionToggle(enabled: Boolean) = Unit
    override fun onOpenFeaturePage(title: String) = Unit
    override fun onCloseFeaturePage() = Unit
}
