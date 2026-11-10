package net.kollnig.missioncontrol.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class FirewallPolicyLogicTest {
    @Test
    public void policyPriorityPrecedesEverySecondarySort() {
        List<FirewallPolicyLogic.Entry> apps = new ArrayList<>(Arrays.asList(
                entry(3, "Excluded", "excluded", 100, 1, 99, 99, 999),
                entry(2, "Zulu Direct", "direct", 80, 2, 0, 0, 0),
                entry(1, "Aardvark VPN", "vpn", 70, 3, 50, 60, 800),
                entry(0, "Zebra Blocked", "blocked", 60, 4, 0, 0, 0)));
        apps.sort(FirewallPolicyLogic.comparator(FirewallPolicyLogic.SORT_TRACKERS_WEEK));
        assertPackages(apps, "blocked", "vpn", "direct", "excluded");
    }

    @Test
    public void everyTrackerSortUsesDescendingActivityWithinPolicy() {
        List<FirewallPolicyLogic.Entry> apps = new ArrayList<>(Arrays.asList(
                entry(1, "Week low", "week-low", 10, 7, 1, 8, 20),
                entry(1, "Week high", "week-high", 20, 8, 9, 2, 10),
                entry(1, "All high", "all-high", 30, 4, 0, 20, 30),
                entry(1, "Recent high", "recent-high", 40, 3, 0, 0, 90)));

        apps.sort(FirewallPolicyLogic.comparator(FirewallPolicyLogic.SORT_TRACKERS_WEEK));
        assertPackages(apps, "week-high", "all-high", "recent-high", "week-low");
        apps.sort(FirewallPolicyLogic.comparator(FirewallPolicyLogic.SORT_TRACKERS_ALL));
        assertPackages(apps, "all-high", "week-low", "week-high", "recent-high");
        apps.sort(FirewallPolicyLogic.comparator(FirewallPolicyLogic.SORT_TRACKERS_RECENT));
        assertPackages(apps, "recent-high", "all-high", "week-low", "week-high");
    }

    @Test
    public void installDateAndUidSortAreSecondaryWithinPolicy() {
        List<FirewallPolicyLogic.Entry> apps = new ArrayList<>(Arrays.asList(
                entry(2, "Old Direct", "direct-old", 10, 50, 0, 0, 0),
                entry(2, "New Direct", "direct-new", 30, 60, 0, 0, 0),
                entry(1, "Low UID VPN", "vpn-low", 10, 7, 0, 0, 0),
                entry(1, "High UID VPN", "vpn-high", 30, 90, 0, 0, 0)));
        apps.sort(FirewallPolicyLogic.comparator(FirewallPolicyLogic.SORT_INSTALL_DATE));
        assertPackages(apps, "vpn-high", "vpn-low", "direct-new", "direct-old");
        apps.sort(FirewallPolicyLogic.comparator(FirewallPolicyLogic.SORT_UID));
        assertPackages(apps, "vpn-low", "vpn-high", "direct-old", "direct-new");
    }

    @Test
    public void excludedIsNotDirectAndBlockPrecedesRouting() {
        assertEquals(FirewallPolicyLogic.PRIORITY_BLOCKED,
                FirewallPolicyLogic.priority(true, true, false));
        assertEquals(FirewallPolicyLogic.PRIORITY_BLOCKED,
                FirewallPolicyLogic.priority(true, true, true));
        assertEquals(FirewallPolicyLogic.PRIORITY_EXCLUDED,
                FirewallPolicyLogic.priority(false, true, true));
        assertEquals(FirewallPolicyLogic.PRIORITY_VPN,
                FirewallPolicyLogic.priority(true, false, true));
        assertEquals(FirewallPolicyLogic.PRIORITY_DIRECT,
                FirewallPolicyLogic.priority(true, false, false));
    }

    @Test
    public void exposesAllLiveTrackerSortModesAndInstallDate() {
        assertTrue(FirewallPolicyLogic.isSupportedSort(FirewallPolicyLogic.SORT_NAME));
        assertTrue(FirewallPolicyLogic.isSupportedSort(FirewallPolicyLogic.SORT_INSTALL_DATE));
        assertTrue(FirewallPolicyLogic.isSupportedSort(FirewallPolicyLogic.SORT_TRACKERS_WEEK));
        assertTrue(FirewallPolicyLogic.isSupportedSort(FirewallPolicyLogic.SORT_TRACKERS_ALL));
        assertTrue(FirewallPolicyLogic.isSupportedSort(FirewallPolicyLogic.SORT_TRACKERS_RECENT));
        assertTrue(FirewallPolicyLogic.isSupportedSort(FirewallPolicyLogic.SORT_UID));
        assertFalse(FirewallPolicyLogic.isSupportedSort("unknown"));
    }

    @Test
    public void quickActionIsExclusiveAndExcludedIsNotDirect() {
        assertEquals(FirewallPolicyLogic.QUICK_ACTION_NONE,
                FirewallPolicyLogic.quickAction(false, false, true, true));
        assertEquals(FirewallPolicyLogic.QUICK_ACTION_BLOCK,
                FirewallPolicyLogic.quickAction(true, true, true, true));
        assertEquals(FirewallPolicyLogic.QUICK_ACTION_VPN,
                FirewallPolicyLogic.quickAction(true, false, true, true));
        assertEquals(FirewallPolicyLogic.QUICK_ACTION_DIRECT,
                FirewallPolicyLogic.quickAction(true, false, true, false));
        assertEquals(FirewallPolicyLogic.QUICK_ACTION_DIRECT,
                FirewallPolicyLogic.quickAction(true, false, false, true));
    }

    private static FirewallPolicyLogic.Entry entry(int priority, String name,
            String packageName, long installTime, int uid, int week, int all, long recent) {
        return new FirewallPolicyLogic.Entry(priority, name, packageName, installTime,
                uid, week, all, recent);
    }

    private static void assertPackages(List<FirewallPolicyLogic.Entry> entries,
            String... expected) {
        List<String> actual = new ArrayList<>();
        for (FirewallPolicyLogic.Entry entry : entries)
            actual.add(entry.packageName);
        assertEquals(Arrays.asList(expected), actual);
    }
}
