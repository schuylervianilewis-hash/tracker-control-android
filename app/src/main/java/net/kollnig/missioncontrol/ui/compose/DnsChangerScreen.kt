/*
 * Vianshield DNS Changer screen.
 *
 * This file is part of TrackerControl and is licensed under the GNU General
 * Public License, version 3 or (at your option) any later version.
 */
package net.kollnig.missioncontrol.ui.compose

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import eu.faircode.netguard.DnsChangerConfig
import eu.faircode.netguard.ServiceSinkhole
import net.kollnig.missioncontrol.R

@Composable
internal fun DnsChangerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) {
        PreferenceManager.getDefaultSharedPreferences(context)
    }
    val wireGuardActive = remember(prefs) {
        DnsChangerConfig.isWireGuardActive(prefs)
    }
    val dohActive = remember(prefs) {
        prefs.getBoolean("doh_enabled", false) && !wireGuardActive
    }
    val profileDns = remember(prefs, wireGuardActive) {
        DnsChangerConfig.getWireGuardProfileDns(prefs)
    }
    val initialMode = remember(prefs, wireGuardActive) {
        DnsChangerConfig.getSelectedMode(prefs, wireGuardActive)
    }

    var currentMode by remember(prefs, wireGuardActive) { mutableStateOf(initialMode) }
    var selectedMode by remember(prefs, wireGuardActive) { mutableStateOf(initialMode) }
    var primaryDns by remember(prefs) {
        mutableStateOf(prefs.getString("dns", "") ?: "")
    }
    var secondaryDns by remember(prefs) {
        mutableStateOf(prefs.getString("dns2", "") ?: "")
    }
    var customError by remember { mutableStateOf<String?>(null) }

    fun applyMode(mode: String) {
        prefs.edit().putString(DnsChangerConfig.PREF_DNS_MODE, mode).apply()
        currentMode = mode
        selectedMode = mode
        customError = null
        ServiceSinkhole.reload(
            "changed " + DnsChangerConfig.PREF_DNS_MODE,
            context,
            true
        )
        Toast.makeText(context, R.string.dns_changer_saved, Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("dashboard-feature-page")
            .testTag("dns-changer-screen")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("dashboard-feature-back")
            ) {
                Text(
                    text = "←",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.dashboard_dns_changer),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.dns_changer_current),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    val status = when {
                        dohActive -> androidx.compose.ui.res.stringResource(
                            R.string.dns_changer_status_doh
                        )
                        wireGuardActive &&
                                (currentMode == DnsChangerConfig.MODE_WIREGUARD ||
                                        currentMode == DnsChangerConfig.MODE_AUTOMATIC) -> {
                            if (profileDns.isEmpty())
                                androidx.compose.ui.res.stringResource(
                                    R.string.dns_changer_status_wireguard_fallback
                                )
                            else
                                androidx.compose.ui.res.stringResource(
                                    R.string.dns_changer_status_wireguard_profile,
                                    profileDns.joinToString()
                                )
                        }
                        wireGuardActive -> androidx.compose.ui.res.stringResource(
                            R.string.dns_changer_status_wireguard_selected
                        )
                        currentMode == DnsChangerConfig.MODE_WIREGUARD ->
                            androidx.compose.ui.res.stringResource(
                                R.string.dns_changer_status_wireguard_off
                            )
                        else -> androidx.compose.ui.res.stringResource(
                            R.string.dns_changer_status_normal
                        )
                    }
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = androidx.compose.ui.res.stringResource(R.string.dns_changer_choose),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            DnsModeOption(
                title = androidx.compose.ui.res.stringResource(R.string.dns_mode_automatic),
                summary = androidx.compose.ui.res.stringResource(R.string.dns_mode_automatic_summary),
                selected = selectedMode == DnsChangerConfig.MODE_AUTOMATIC,
                testTag = "dns-mode-automatic",
                onClick = { applyMode(DnsChangerConfig.MODE_AUTOMATIC) }
            )
            DnsModeOption(
                title = androidx.compose.ui.res.stringResource(R.string.dns_mode_cloudflare),
                summary = androidx.compose.ui.res.stringResource(R.string.dns_mode_cloudflare_summary),
                selected = selectedMode == DnsChangerConfig.MODE_CLOUDFLARE,
                testTag = "dns-mode-cloudflare",
                onClick = { applyMode(DnsChangerConfig.MODE_CLOUDFLARE) }
            )
            DnsModeOption(
                title = androidx.compose.ui.res.stringResource(R.string.dns_mode_google),
                summary = androidx.compose.ui.res.stringResource(R.string.dns_mode_google_summary),
                selected = selectedMode == DnsChangerConfig.MODE_GOOGLE,
                testTag = "dns-mode-google",
                onClick = { applyMode(DnsChangerConfig.MODE_GOOGLE) }
            )
            DnsModeOption(
                title = androidx.compose.ui.res.stringResource(R.string.dns_mode_quad9),
                summary = androidx.compose.ui.res.stringResource(R.string.dns_mode_quad9_summary),
                selected = selectedMode == DnsChangerConfig.MODE_QUAD9,
                testTag = "dns-mode-quad9",
                onClick = { applyMode(DnsChangerConfig.MODE_QUAD9) }
            )
            DnsModeOption(
                title = androidx.compose.ui.res.stringResource(R.string.dns_mode_wireguard),
                summary = androidx.compose.ui.res.stringResource(R.string.dns_mode_wireguard_summary),
                selected = selectedMode == DnsChangerConfig.MODE_WIREGUARD,
                testTag = "dns-mode-wireguard",
                onClick = { applyMode(DnsChangerConfig.MODE_WIREGUARD) }
            )
            DnsModeOption(
                title = androidx.compose.ui.res.stringResource(R.string.dns_mode_custom),
                summary = androidx.compose.ui.res.stringResource(R.string.dns_mode_custom_summary),
                selected = selectedMode == DnsChangerConfig.MODE_CUSTOM,
                testTag = "dns-mode-custom",
                onClick = {
                    selectedMode = DnsChangerConfig.MODE_CUSTOM
                    customError = null
                }
            )

            if (selectedMode == DnsChangerConfig.MODE_CUSTOM) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = primaryDns,
                            onValueChange = {
                                primaryDns = it
                                customError = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("dns-custom-primary"),
                            label = {
                                Text(androidx.compose.ui.res.stringResource(R.string.dns_custom_primary))
                            },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = secondaryDns,
                            onValueChange = {
                                secondaryDns = it
                                customError = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("dns-custom-secondary"),
                            label = {
                                Text(androidx.compose.ui.res.stringResource(R.string.dns_custom_secondary))
                            },
                            singleLine = true
                        )
                        customError?.let { message ->
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("dns-custom-error")
                            )
                        }
                        Button(
                            onClick = {
                                val error = DnsChangerConfig.validateCustomDns(
                                    primaryDns,
                                    secondaryDns,
                                    prefs.getBoolean("ip6", true)
                                )
                                if (error != null) {
                                    customError = error
                                } else {
                                    prefs.edit().apply {
                                        putString(
                                            DnsChangerConfig.PREF_DNS_MODE,
                                            DnsChangerConfig.MODE_CUSTOM
                                        )
                                        putString("dns", primaryDns.trim())
                                        if (secondaryDns.isBlank())
                                            remove("dns2")
                                        else
                                            putString("dns2", secondaryDns.trim())
                                    }.apply()
                                    currentMode = DnsChangerConfig.MODE_CUSTOM
                                    selectedMode = DnsChangerConfig.MODE_CUSTOM
                                    customError = null
                                    ServiceSinkhole.reload(
                                        "changed " + DnsChangerConfig.PREF_DNS_MODE,
                                        context,
                                        true
                                    )
                                    Toast.makeText(
                                        context,
                                        R.string.dns_changer_saved,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("dns-custom-save")
                        ) {
                            Text(androidx.compose.ui.res.stringResource(R.string.dns_custom_save))
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.dns_changer_standard_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.dns_changer_direct_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (wireGuardActive) {
                Text(
                    text = androidx.compose.ui.res.stringResource(
                        R.string.dns_changer_wireguard_warning
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DnsModeOption(
    title: String,
    summary: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
