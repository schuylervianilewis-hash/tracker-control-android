package net.kollnig.missioncontrol.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
class BlockedDomainsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var blockedSelection: String? = null
    private var whitelistSelection: String? = null

    private val callbacks = object : BlockedDomainsScreenCallbacks {
        override fun onBlockedDomainClick(hostname: String) {
            blockedSelection = hostname
        }

        override fun onWhitelistedDomainClick(hostname: String) {
            whitelistSelection = hostname
        }
    }

    @Test
    fun blockedHistoryRowsCanRequestWhitelistConfirmationAndShowWhitelistedStatus() {
        compose.setContent {
            TrackerControlTheme {
                BlockedDomainsScreenContent(
                    BlockedDomainsScreenModel(
                        rows = listOf(
                            BlockedDomainRow("ads.example.com", "Last blocked 2 minutes ago"),
                            BlockedDomainRow("cdn.example.net", "Last blocked 3 minutes ago", true)
                        )
                    ),
                    callbacks
                )
            }
        }

        compose.onNodeWithTag("blocked-domain-ads.example.com")
            .assertIsDisplayed().performClick()
        assertEquals("ads.example.com", blockedSelection)
        compose.onNodeWithTag("blocked-domain-cdn.example.net").assertIsDisplayed()
    }

    @Test
    fun whitelistViewRowsCanRequestRemovalConfirmation() {
        compose.setContent {
            TrackerControlTheme {
                BlockedDomainsScreenContent(
                    BlockedDomainsScreenModel(
                        whitelistedDomains = listOf("example.com"),
                        showingWhitelist = true
                    ),
                    callbacks
                )
            }
        }

        compose.onNodeWithTag("whitelisted-domains-list").assertIsDisplayed()
        compose.onNodeWithTag("whitelisted-domain-example.com").performClick()
        assertEquals("example.com", whitelistSelection)
    }

    @Test
    fun emptyWhitelistUsesItsOwnEmptyState() {
        compose.setContent {
            TrackerControlTheme {
                BlockedDomainsScreenContent(
                    BlockedDomainsScreenModel(showingWhitelist = true),
                    callbacks
                )
            }
        }

        compose.onNodeWithTag("whitelisted-domains-empty").assertIsDisplayed()
    }
}
