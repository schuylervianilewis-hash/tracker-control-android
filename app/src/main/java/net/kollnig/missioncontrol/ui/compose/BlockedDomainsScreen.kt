/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.kollnig.missioncontrol.R

@Immutable
data class BlockedDomainRow(
    val hostname: String,
    val lastBlocked: String,
    val whitelisted: Boolean = false
)

@Immutable
data class BlockedDomainsScreenModel(
    val rows: List<BlockedDomainRow> = emptyList(),
    val whitelistedDomains: List<String> = emptyList(),
    val showingWhitelist: Boolean = false
)

interface BlockedDomainsScreenCallbacks {
    fun onBlockedDomainClick(hostname: String)
    fun onWhitelistedDomainClick(hostname: String)
}

class BlockedDomainsScreenController internal constructor(
    private val state: MutableState<BlockedDomainsScreenModel>
) {
    fun update(model: BlockedDomainsScreenModel) {
        state.value = model
    }
}

object BlockedDomainsScreen {
    @JvmStatic
    fun install(
        composeView: ComposeView,
        initialModel: BlockedDomainsScreenModel,
        callbacks: BlockedDomainsScreenCallbacks
    ): BlockedDomainsScreenController {
        val state = mutableStateOf(initialModel)
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        composeView.setContent {
            TrackerControlTheme {
                BlockedDomainsScreenContent(state.value, callbacks)
            }
        }
        return BlockedDomainsScreenController(state)
    }
}

@Composable
internal fun BlockedDomainsScreenContent(
    model: BlockedDomainsScreenModel,
    callbacks: BlockedDomainsScreenCallbacks
) {
    if (model.showingWhitelist) {
        if (model.whitelistedDomains.isEmpty()) {
            EmptyDomainList(
                message = stringResource(R.string.whitelisted_domains_empty),
                tag = "whitelisted-domains-empty"
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .testTag("whitelisted-domains-list"),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(model.whitelistedDomains, key = { it }) { hostname ->
                    ListItem(
                        headlineContent = {
                            Text(hostname, fontWeight = FontWeight.Medium)
                        },
                        supportingContent = {
                            Text(
                                stringResource(R.string.whitelisted_domain_remove_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                            .clickable { callbacks.onWhitelistedDomainClick(hostname) }
                            .testTag("whitelisted-domain-$hostname")
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    } else if (model.rows.isEmpty()) {
        EmptyDomainList(
            message = stringResource(R.string.blocked_domains_empty),
            tag = "blocked-domains-empty"
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .testTag("blocked-domains-list"),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(model.rows, key = { it.hostname }) { row ->
                ListItem(
                    headlineContent = {
                        Text(row.hostname, fontWeight = FontWeight.Medium)
                    },
                    supportingContent = {
                        Text(
                            if (row.whitelisted)
                                stringResource(
                                    R.string.blocked_domain_whitelisted_last_blocked,
                                    row.lastBlocked
                                )
                            else
                                row.lastBlocked,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = !row.whitelisted) {
                            callbacks.onBlockedDomainClick(row.hostname)
                        }
                        .testTag("blocked-domain-${row.hostname}")
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun EmptyDomainList(message: String, tag: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(32.dp).testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
