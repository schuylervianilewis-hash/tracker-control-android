package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import androidx.preference.PreferenceScreen;

import net.kollnig.missioncontrol.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SettingsHostNavigationTest {
    public static class SettingsHostTestActivity extends SettingsHostActivity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            setTheme(R.style.AppTheme_NoActionBar);
            super.onCreate(savedInstanceState);
            setContentView(R.layout.main);
            initializeSettingsHost(R.id.settingsContainer, savedInstanceState);
        }
    }

    @Test
    public void embeddedSettingsFragmentKeepsNestedPreferenceBackStack() {
        ActivityController<SettingsHostTestActivity> controller =
                Robolectric.buildActivity(SettingsHostTestActivity.class).setup();
        try {
            SettingsHostTestActivity host = controller.get();
            FragmentSettings settings = (FragmentSettings) host.getSupportFragmentManager()
                    .findFragmentById(R.id.settingsContainer);
            assertNotNull(settings);
            assertNotNull(settings.getPreferenceScreen());

            PreferenceScreen advanced = (PreferenceScreen) settings.findPreference("screen_advanced_options");
            assertNotNull(advanced);
            assertTrue(settings.onPreferenceStartScreen(settings, advanced));
            host.getSupportFragmentManager().executePendingTransactions();
            assertEquals(1, host.getSupportFragmentManager().getBackStackEntryCount());

            host.getSupportFragmentManager().popBackStackImmediate();
            assertEquals(0, host.getSupportFragmentManager().getBackStackEntryCount());
        } finally {
            controller.pause().stop().destroy();
        }
    }
}