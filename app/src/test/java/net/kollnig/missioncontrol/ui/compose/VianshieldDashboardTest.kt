package net.kollnig.missioncontrol.ui.compose

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import eu.faircode.netguard.DnsChangerConfig
import net.kollnig.missioncontrol.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
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

    private val prefs
        get() = PreferenceManager.getDefaultSharedPreferences(context)

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

    @Before
    fun clearPreferences() {
        prefs.edit().clear().commit()
        requestedProtection = null
        openedFeature = null
        closeRequests = 0
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
        compose.onNodeWithTag("dashboard-protection-toggle").assertContentDescriptionEquals(
            context.getString(R.string.dashboard_protection_off_description)
        )
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
    fun dnsFeatureDestinationShowsFunctionalOptionsAndBackNavigation() {
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
        compose.onNodeWithTag("dns-mode-automatic").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dns-mode-cloudflare").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dns-mode-google").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dns-mode-quad9").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dns-mode-wireguard").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dns-mode-custom").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dashboard-feature-back").performClick()
        assertEquals(1, closeRequests)
    }

    @Test
    fun selectingProviderPersistsMode() {
        val title = context.getString(R.string.dashboard_dns_changer)
        compose.setContent {
            TrackerControlTheme {
                TimelineScreenContent(
                    TimelineScreenModel.initial().copy(featurePageTitle = title),
                    callbacks
                )
            }
        }

        compose.onNodeWithTag("dns-mode-cloudflare").performScrollTo().performClick()
        assertEquals(
            DnsChangerConfig.MODE_CLOUDFLARE,
            prefs.getString(DnsChangerConfig.PREF_DNS_MODE, null)
        )
    }

    @Test
    fun customDnsRequiresValidPrimaryAndPersistsBothAddresses() {
        val title = context.getString(R.string.dashboard_dns_changer)
        compose.setContent {
            TrackerControlTheme {
                TimelineScreenContent(
                    TimelineScreenModel.initial().copy(featurePageTitle = title),
                    callbacks
                )
            }
        }

        compose.onNodeWithTag("dns-mode-custom").performScrollTo().performClick()
        compose.onNodeWithTag("dns-custom-save").performScrollTo().performClick()
        compose.onNodeWithTag("dns-custom-error").assertIsDisplayed()
        assertFalse(prefs.contains(DnsChangerConfig.PREF_DNS_MODE))

        compose.onNodeWithTag("dns-custom-primary").performScrollTo().performTextInput("192.0.2.53")
        compose.onNodeWithTag("dns-custom-secondary").performScrollTo().performTextInput("192.0.2.54")
        compose.onNodeWithTag("dns-custom-save").performClick()

        assertEquals(
            DnsChangerConfig.MODE_CUSTOM,
            prefs.getString(DnsChangerConfig.PREF_DNS_MODE, null)
        )
        assertEquals("192.0.2.53", prefs.getString("dns", null))
        assertEquals("192.0.2.54", prefs.getString("dns2", null))
    }
}
