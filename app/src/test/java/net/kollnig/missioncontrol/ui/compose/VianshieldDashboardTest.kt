package net.kollnig.missioncontrol.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import net.kollnig.missioncontrol.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h480dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VianshieldDashboardTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private var requestedProtection: Boolean? = null
    private var openedFeature: String? = null
    private var closeRequests = 0

    private val callbacks = object : TimelineScreenCallbacks {
        override fun onEntryClick(uid: Int, appName: String, packageName: String) = Unit
        override fun onOpenApp() = Unit
        override fun onOpenSettings() = Unit
        override fun onOpenInsights() = Unit
        override fun onShareInsights() = Unit
        override fun onDismissHint() = Unit
        override fun onRefresh() = Unit
        override fun onProtectionToggle(enabled: Boolean) {
            requestedProtection = enabled
        }
        override fun onOpenFeaturePage(title: String) {
            openedFeature = title
        }
        override fun onCloseFeaturePage() {
            closeRequests++
        }
    }

    @Test
    fun dashboardShowsProtectionAndAllFourFeatureCards() {
        compose.setContent {
            TrackerControlTheme {
                TimelineScreenContent(
                    TimelineScreenModel.initial(protectionEnabled = false),
                    callbacks
                )
            }
        }

        compose.onNodeWithTag("dashboard-protection-toggle").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.dashboard_protection_off)).assertIsDisplayed()
        compose.onNodeWithTag("dashboard-card-speed-throttling").assertIsDisplayed()
        compose.onNodeWithTag("dashboard-card-dns-changer").assertIsDisplayed()
        compose.onNodeWithTag("dashboard-card-ad-filter").assertIsDisplayed()
        compose.onNodeWithTag("dashboard-card-blocked-domains").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.dashboard_timeline_heading)).assertIsDisplayed()

        compose.onNodeWithTag("dashboard-protection-toggle").performClick()
        assertEquals(true, requestedProtection)

        compose.onNodeWithTag("dashboard-card-speed-throttling").performClick()
        assertEquals(context.getString(R.string.dashboard_speed_throttling), openedFeature)
    }

    @Test
    fun featureDestinationContainsOnlyTitleAndBackNavigation() {
        val title = context.getString(R.string.dashboard_dns_changer)
        compose.setContent {
            TrackerControlTheme {
                TimelineScreenContent(
                    TimelineScreenModel.initial().copy(featurePageTitle = title),
                    callbacks
                )
            }
        }

        compose.onNodeWithTag("dashboard-feature-page").assertIsDisplayed()
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithTag("dashboard-feature-back").assertIsDisplayed()
        compose.onNodeWithTag("dashboard-feature-back").performClick()
        assertEquals(1, closeRequests)
    }
}
