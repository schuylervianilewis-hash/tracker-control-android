package net.kollnig.missioncontrol.data;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class FirewallPolicyLogicTest {
    @Test
    public void policyPriorityAlwaysPrecedesNameSort() {
        List<FirewallPolicyLogic.Entry> apps = new ArrayList<>(Arrays.asList(
                entry(2, "Aardvark", "blocked", 30),
                entry(1, "Zulu", "vpn", 20),
                entry(0, "Zebra", "bypass-z", 10),
                entry(0, "Alpha", "bypass-a", 5)));
        apps.sort(FirewallPolicyLogic.comparator(FirewallPolicyLogic.SORT_NAME));

        assertPackages(apps, "bypass-a", "bypass-z", "vpn", "blocked");
    }

    @Test
    public void installDateSortIsSecondaryWithinEachPolicy() {
        List<FirewallPolicyLogic.Entry> apps = new ArrayList<>(Arrays.asList(
                entry(1, "Older VPN", "vpn-old", 10),
                entry(0, "Older Bypass", "bypass-old", 10),
                entry(1, "Newer VPN", "vpn-new", 20),
                entry(0, "Newer Bypass", "bypass-new", 30)));
        apps.sort(FirewallPolicyLogic.comparator(FirewallPolicyLogic.SORT_INSTALL_DATE));

        assertPackages(apps, "bypass-new", "bypass-old", "vpn-new", "vpn-old");
    }

    @Test
    public void blockIsEffectiveOnlyWhenTrackerControlStillApplies() {
        assertEquals(FirewallPolicyLogic.PRIORITY_BLOCKED,
                FirewallPolicyLogic.priority(true, true, false));
        assertEquals(FirewallPolicyLogic.PRIORITY_BLOCKED,
                FirewallPolicyLogic.priority(true, true, true));
        assertEquals(FirewallPolicyLogic.PRIORITY_BYPASS,
                FirewallPolicyLogic.priority(false, true, true));
        assertEquals(FirewallPolicyLogic.PRIORITY_VPN,
                FirewallPolicyLogic.priority(true, false, true));
        assertEquals(FirewallPolicyLogic.PRIORITY_BYPASS,
                FirewallPolicyLogic.priority(true, false, false));
    }

    private static FirewallPolicyLogic.Entry entry(int priority, String name,
            String packageName, long installTime) {
        return new FirewallPolicyLogic.Entry(priority, name, packageName, installTime);
    }

    private static void assertPackages(List<FirewallPolicyLogic.Entry> entries,
            String... expected) {
        List<String> actual = new ArrayList<>();
        for (FirewallPolicyLogic.Entry entry : entries)
            actual.add(entry.packageName);
        assertEquals(Arrays.asList(expected), actual);
    }
}
