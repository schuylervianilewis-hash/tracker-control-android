package net.kollnig.missioncontrol.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackageInfo;

import org.junit.Test;

public class InternetPermissionPolicyTest {
    @Test
    public void onlyUniqueUidWithoutPermissionAndWithoutPriorDecisionIsAutoBlocked() {
        assertTrue(InternetPermissionPolicy.shouldAutoBlock(false, false, false, false));
        assertFalse(InternetPermissionPolicy.shouldAutoBlock(true, false, false, false));
        assertFalse(InternetPermissionPolicy.shouldAutoBlock(false, true, false, false));
        assertFalse(InternetPermissionPolicy.shouldAutoBlock(false, false, true, false));
        assertFalse(InternetPermissionPolicy.shouldAutoBlock(false, false, false, true));
    }

    @Test
    public void packageIdentitySurvivesUpdateButChangesOnReinstall() {
        PackageInfo first = new PackageInfo();
        first.packageName = "com.example.app";
        first.firstInstallTime = 1000L;
        PackageInfo updated = new PackageInfo();
        updated.packageName = "com.example.app";
        updated.firstInstallTime = 1000L;
        PackageInfo reinstalled = new PackageInfo();
        reinstalled.packageName = "com.example.app";
        reinstalled.firstInstallTime = 2000L;

        assertEquals(InternetPermissionPolicy.packageIdentity(first),
                InternetPermissionPolicy.packageIdentity(updated));
        assertFalse(InternetPermissionPolicy.packageIdentity(first)
                .equals(InternetPermissionPolicy.packageIdentity(reinstalled)));
    }

    @Test
    public void manualAllowSignatureIncludesPackageUidAndInstallIdentity() {
        String expected = "com.example.app|10001|1000";
        assertEquals(expected, InternetPermissionPolicy.manualAllowSignature(
                "com.example.app", 10001, 1000L));
        assertFalse(expected.equals(InternetPermissionPolicy.manualAllowSignature(
                "com.example.app", 10002, 1000L)));
        assertFalse(expected.equals(InternetPermissionPolicy.manualAllowSignature(
                "com.example.app", 10001, 2000L)));
    }
}
