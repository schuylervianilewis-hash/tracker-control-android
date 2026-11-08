package net.kollnig.missioncontrol.ui.compose

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import eu.faircode.netguard.Util
import net.kollnig.missioncontrol.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class TrackerControlThemeTest {
    @Test
    @Config(qualifiers = "notnight")
    fun lightSystemUsesVianshieldPalette() {
        assertVianshieldPalette(themedContext())
    }

    @Test
    @Config(qualifiers = "night")
    fun darkSystemStillUsesVianshieldLightPalette() {
        assertVianshieldPalette(themedContext())
    }

    @Test
    fun appThemeSelectionForcesLightMode() {
        Util.setTheme(RuntimeEnvironment.getApplication())

        assertEquals(
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.getDefaultNightMode()
        )
    }

    private fun themedContext(): Context {
        return ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            R.style.AppThemeRed
        )
    }

    private fun assertVianshieldPalette(context: Context) {
        val scheme = VianshieldLightColorScheme

        // Compose and legacy Views must resolve the same canonical resource palette.
        assertEquals(ContextCompat.getColor(context, R.color.vian_primary), scheme.primary.toArgb())
        assertEquals(ContextCompat.getColor(context, R.color.vian_on_primary), scheme.onPrimary.toArgb())
        assertEquals(ContextCompat.getColor(context, R.color.vian_primary_container), scheme.primaryContainer.toArgb())
        assertEquals(ContextCompat.getColor(context, R.color.vian_on_primary_container), scheme.onPrimaryContainer.toArgb())
        assertEquals(ContextCompat.getColor(context, R.color.vian_primary_dark), scheme.secondary.toArgb())
        assertEquals(ContextCompat.getColor(context, R.color.vian_on_secondary), scheme.onSecondary.toArgb())
        assertEquals(ContextCompat.getColor(context, R.color.vian_primary_container), scheme.secondaryContainer.toArgb())
        assertEquals(ContextCompat.getColor(context, R.color.vian_on_primary_container), scheme.onSecondaryContainer.toArgb())
        assertEquals(ContextCompat.getColor(context, R.color.vian_primary_light), scheme.tertiary.toArgb())
        assertEquals(ContextCompat.getColor(context, R.color.vian_text_primary), scheme.onTertiary.toArgb())
        assertEquals(ContextCompat.getColor(context, R.color.vian_background), scheme.background.toArgb())
        assertEquals(ContextCompat.getColor(context, R.color.vian_text_primary), scheme.onBackground.toArgb())
        assertEquals(ContextCompat.getColor(context, R.color.vian_surface), scheme.surface.toArgb())
        assertEquals(ContextCompat.getColor(context, R.color.vian_text_primary), scheme.onSurface.toArgb())
        assertEquals(ContextCompat.getColor(context, R.color.vian_surface_variant), scheme.surfaceVariant.toArgb())
        assertEquals(ContextCompat.getColor(context, R.color.vian_text_secondary), scheme.onSurfaceVariant.toArgb())
        assertEquals(ContextCompat.getColor(context, R.color.vian_border), scheme.outline.toArgb())
        assertEquals(ContextCompat.getColor(context, R.color.vian_outline_variant), scheme.outlineVariant.toArgb())
        assertEquals(ContextCompat.getColor(context, R.color.status_error), scheme.error.toArgb())
        assertEquals(ContextCompat.getColor(context, R.color.status_error_container), scheme.errorContainer.toArgb())
        assertEquals(ContextCompat.getColor(context, R.color.status_on_error_container), scheme.onErrorContainer.toArgb())

        assertEquals(0xFF0284C7.toInt(), scheme.primary.toArgb())
        assertEquals(0xFF06111D.toInt(), scheme.onPrimary.toArgb())
        assertEquals(0xFFE0F2FE.toInt(), scheme.primaryContainer.toArgb())
        assertEquals(0xFF0369A1.toInt(), scheme.secondary.toArgb())
        assertEquals(0xFF38BDF8.toInt(), scheme.tertiary.toArgb())
        assertEquals(0xFFF6FAFD.toInt(), scheme.background.toArgb())
        assertEquals(0xFFFFFFFF.toInt(), scheme.surface.toArgb())
        assertEquals(0xFFEFF6FC.toInt(), scheme.surfaceVariant.toArgb())
        assertEquals(0xFF0F172A.toInt(), scheme.onSurface.toArgb())
        assertEquals(0xFF475569.toInt(), scheme.onSurfaceVariant.toArgb())
        assertEquals(0xFFD6E6F5.toInt(), scheme.outline.toArgb())
        assertEquals(0xFFDC2626.toInt(), scheme.error.toArgb())
        assertEquals(0xFFFEE2E2.toInt(), scheme.errorContainer.toArgb())
        assertEquals(0xFF991B1B.toInt(), scheme.onErrorContainer.toArgb())

        assertEquals(0xFF0284C7.toInt(), ContextCompat.getColor(context, R.color.vian_primary))
        assertEquals(0xFF0369A1.toInt(), ContextCompat.getColor(context, R.color.vian_primary_dark))
        assertEquals(0xFF38BDF8.toInt(), ContextCompat.getColor(context, R.color.vian_primary_light))
        assertEquals(0xFFE0F2FE.toInt(), ContextCompat.getColor(context, R.color.vian_primary_container))
        assertEquals(0xFFF6FAFD.toInt(), ContextCompat.getColor(context, R.color.vian_background))
        assertEquals(0xFFFFFFFF.toInt(), ContextCompat.getColor(context, R.color.vian_surface))
        assertEquals(0xFFEFF6FC.toInt(), ContextCompat.getColor(context, R.color.vian_surface_variant))
        assertEquals(0xFFD6E6F5.toInt(), ContextCompat.getColor(context, R.color.vian_border))
        assertEquals(0xFF059669.toInt(), ContextCompat.getColor(context, R.color.status_connected))
        assertEquals(0xFFD97706.toInt(), ContextCompat.getColor(context, R.color.status_starting))
        assertEquals(0xFF64748B.toInt(), ContextCompat.getColor(context, R.color.status_stopped))
        assertEquals(0xFFDC2626.toInt(), ContextCompat.getColor(context, R.color.status_error))
        assertEquals(
            0xFF0284C7.toInt(),
            ContextCompat.getColor(context, R.color.colorPrimary)
        )
        assertEquals(
            0xFF0369A1.toInt(),
            ContextCompat.getColor(context, R.color.colorAccent)
        )
        assertEquals(
            0xFFFFFFFF.toInt(),
            ContextCompat.getColor(context, R.color.bottom_nav_background)
        )
        assertEquals(
            0xFFE0F2FE.toInt(),
            ContextCompat.getColor(context, R.color.bottom_nav_active_indicator)
        )
    }
}
