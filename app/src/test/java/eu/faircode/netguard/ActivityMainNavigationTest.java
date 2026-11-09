package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import net.kollnig.missioncontrol.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ActivityMainNavigationTest {
    private ActivityMain activity;

    @Before
    public void setUp() throws Exception {
        activity = Robolectric.buildActivity(ActivityMain.class).get();
        activity.setTheme(R.style.AppTheme_NoActionBar);
        activity.getDelegate().installViewFactory();
        activity.setContentView(R.layout.main);

        setField("llAppsContent", activity.findViewById(R.id.llApps));
        setField("timelineContainer", activity.findViewById(R.id.timelineContainer));
        setField("vpnContainer", activity.findViewById(R.id.vpnContainer));
        setField("firewallContainer", activity.findViewById(R.id.firewallContainer));
        setField("settingsContainer", activity.findViewById(R.id.settingsContainer));

        // Avoid creating the VPN fragment in this layout-routing test. The VPN
        // destination remains the existing, lazily-created fragment in production.
        setField("vpnFragmentRequested", true);
    }

    @Test
    public void bottomNavigationUsesTheRequiredFiveTabOrderAndNames() {
        BottomNavigationView navigation = activity.findViewById(R.id.bottomNav);
        assertNotNull(navigation);
        assertEquals(5, navigation.getMenu().size());

        assertEquals(R.id.nav_timeline, navigation.getMenu().getItem(0).getItemId());
        assertEquals("Main", navigation.getMenu().getItem(0).getTitle().toString());

        assertEquals(R.id.nav_firewall, navigation.getMenu().getItem(1).getItemId());
        assertEquals("Firewall", navigation.getMenu().getItem(1).getTitle().toString());

        assertEquals(R.id.nav_vpn, navigation.getMenu().getItem(2).getItemId());
        assertEquals("VPN", navigation.getMenu().getItem(2).getTitle().toString());

        assertEquals(R.id.nav_apps, navigation.getMenu().getItem(3).getItemId());
        assertEquals("Tracker", navigation.getMenu().getItem(3).getTitle().toString());

        assertEquals(R.id.nav_settings, navigation.getMenu().getItem(4).getItemId());
        assertEquals("Settings", navigation.getMenu().getItem(4).getTitle().toString());
    }

    @Test
    public void eachTabShowsOnlyItsOwnContentContainer() throws Exception {
        assertTab(R.id.nav_timeline, View.VISIBLE, View.GONE, View.GONE, View.GONE, View.GONE);
        assertTab(R.id.nav_firewall, View.GONE, View.GONE, View.GONE, View.VISIBLE, View.GONE);
        assertTab(R.id.nav_vpn, View.GONE, View.GONE, View.VISIBLE, View.GONE, View.GONE);
        assertTab(R.id.nav_apps, View.GONE, View.VISIBLE, View.GONE, View.GONE, View.GONE);
        assertTab(R.id.nav_settings, View.GONE, View.GONE, View.GONE, View.GONE, View.VISIBLE);

        ViewGroup firewall = activity.findViewById(R.id.firewallContainer);
        assertEquals("Firewall is intentionally an empty placeholder", 0, firewall.getChildCount());

        TextView settingsPlaceholder = activity.findViewById(R.id.tvSettingsTabPlaceholder);
        assertNotNull(settingsPlaceholder);
        assertEquals(activity.getString(R.string.settings_tab_placeholder),
                settingsPlaceholder.getText().toString());
    }

    private void assertTab(int tabId, int mainVisibility, int trackerVisibility,
                           int vpnVisibility, int firewallVisibility, int settingsVisibility)
            throws Exception {
        Method selectTab = ActivityMain.class.getDeclaredMethod("selectTab", int.class);
        selectTab.setAccessible(true);
        selectTab.invoke(activity, tabId);

        assertEquals("Main container", mainVisibility,
                activity.findViewById(R.id.timelineContainer).getVisibility());
        assertEquals("Tracker app list", trackerVisibility,
                activity.findViewById(R.id.llApps).getVisibility());
        assertEquals("VPN container", vpnVisibility,
                activity.findViewById(R.id.vpnContainer).getVisibility());
        assertEquals("Firewall container", firewallVisibility,
                activity.findViewById(R.id.firewallContainer).getVisibility());
        assertEquals("Settings container", settingsVisibility,
                activity.findViewById(R.id.settingsContainer).getVisibility());
    }

    private void setField(String name, Object value) throws Exception {
        Field field = ActivityMain.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(activity, value);
    }
}
