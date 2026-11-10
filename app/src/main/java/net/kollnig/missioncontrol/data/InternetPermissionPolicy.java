/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import eu.faircode.netguard.Util;

/**
 * Applies default-deny to eligible apps first detected without INTERNET
 * permission. The UID blocklist stores enforcement; this class records the
 * origin of automatic blocks so reconciliation never removes a manual decision.
 */
public final class InternetPermissionPolicy {
    private static final String PREFS = "internet_permission_policy";
    private static final String KEY_AUTO_BLOCKS = "auto_blocked_uid_signatures";
    private static final String KEY_MANUAL_ALLOWS = "manual_allow_signatures";
    private static final Object LOCK = new Object();

    // Core platform/network identities are never auto-denied and cannot be
    // changed from Firewall's quick controls. These supplement system-app and
    // own-UID safeguards below.
    private static final Set<String> CRITICAL_SYSTEM_PACKAGES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "android",
                    "com.android.systemui",
                    "com.android.providers.settings",
                    "com.android.providers.media",
                    "com.android.networkstack",
                    "com.android.networkstack.tethering",
                    "com.google.android.networkstack",
                    "com.google.android.networkstack.tethering",
                    "com.android.permissioncontroller",
                    "com.google.android.permissioncontroller",
                    "com.android.vpndialogs",
                    "com.android.phone",
                    "com.android.server.telecom",
                    "com.android.shell"
            )));

    private InternetPermissionPolicy() {
    }

    /**
     * Reconcile installed packages. A failed/unknown inventory makes no
     * changes. Missing automatic entries are retained: PackageManager can
     * report a temporary gap during PACKAGE_REMOVED/PACKAGE_ADDED replacement.
     * Confirmed PACKAGE_FULLY_REMOVED broadcasts own permanent cleanup.
     */
    public static boolean reconcile(Context context) {
        if (context == null)
            return false;
        Context appContext = context.getApplicationContext();
        final List<PackageInfo> installed;
        try {
            installed = appContext.getPackageManager()
                    .getInstalledPackages(PackageManager.GET_PERMISSIONS);
        } catch (Throwable ex) {
            return false;
        }
        return reconcile(appContext, installed);
    }

    static boolean reconcile(Context context, List<PackageInfo> installed) {
        if (context == null || installed == null)
            return false;
        Context appContext = context.getApplicationContext();
        synchronized (LOCK) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            Set<String> automatic = new HashSet<>(
                    prefs.getStringSet(KEY_AUTO_BLOCKS, Collections.emptySet()));
            Set<String> manualAllows = new HashSet<>(
                    prefs.getStringSet(KEY_MANUAL_ALLOWS, Collections.emptySet()));

            Map<Integer, List<PackageInfo>> byUid = new HashMap<>();
            for (PackageInfo info : installed) {
                if (info == null || info.applicationInfo == null
                        || TextUtils.isEmpty(info.packageName)
                        || info.applicationInfo.uid < 0)
                    continue;
                byUid.computeIfAbsent(info.applicationInfo.uid,
                        ignored -> new ArrayList<>()).add(info);
            }

            InternetBlocklist blocklist = InternetBlocklist.getInstance(appContext);
            boolean blocklistChanged = false;
            Map<Integer, String> autoByUid = decodeAutomaticEntries(automatic);

            // Release only when a package is positively present with a changed
            // identity (UID reuse/reinstall), or the UID is now shared. A
            // missing package is NOT evidence of uninstall during an update.
            for (Map.Entry<Integer, String> entry : autoByUid.entrySet()) {
                int uid = entry.getKey();
                List<PackageInfo> members = byUid.get(uid);
                if (members == null || members.isEmpty())
                    continue;
                boolean sharedUid = members.size() > 1;
                boolean sameIdentity = !sharedUid
                        && entry.getValue().equals(packageIdentity(members.get(0)));
                if (shouldReleaseAutomaticBlock(true, sharedUid, sameIdentity)) {
                    if (blocklist.blockedInternet(uid)) {
                        blocklist.unblock(uid);
                        blocklistChanged = true;
                    }
                    automatic.remove(automaticEntry(uid, entry.getValue()));
                }
            }

            for (Map.Entry<Integer, List<PackageInfo>> entry : byUid.entrySet()) {
                int uid = entry.getKey();
                List<PackageInfo> members = entry.getValue();
                if (members.size() != 1)
                    continue;
                PackageInfo info = members.get(0);
                boolean allowedByUser = manualAllows.contains(manualAllowSignature(
                        info.packageName, uid, info.firstInstallTime));
                boolean exempt = isAutomaticDenyExempt(info, uid);
                if (!shouldAutoBlock(false, declaresInternet(info), allowedByUser,
                        blocklist.blockedInternet(uid), exempt))
                    continue;

                String identity = packageIdentity(info);
                if (automatic.contains(automaticEntry(uid, identity)))
                    continue;
                blocklist.block(uid);
                automatic.add(automaticEntry(uid, identity));
                blocklistChanged = true;
            }

            // Do not prune manual allows from a possibly incomplete or
            // temporarily stale package inventory. Full uninstall cleanup below
            // removes the exact package+UID signature.
            prefs.edit()
                    .putStringSet(KEY_AUTO_BLOCKS, automatic)
                    .putStringSet(KEY_MANUAL_ALLOWS, manualAllows)
                    .apply();
            if (blocklistChanged)
                blocklist.saveSettings(appContext);
            return blocklistChanged;
        }
    }

    /** Mark a deliberately selected Block/Allow action as user-owned policy. */
    public static void recordManualDecision(Context context, String packageName, int uid,
            Boolean internetBlocked) {
        if (internetBlocked == null || TextUtils.isEmpty(packageName) || uid < 0)
            return;
        Context appContext = context.getApplicationContext();
        synchronized (LOCK) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            Set<String> automatic = new HashSet<>(
                    prefs.getStringSet(KEY_AUTO_BLOCKS, Collections.emptySet()));
            Set<String> manualAllows = new HashSet<>(
                    prefs.getStringSet(KEY_MANUAL_ALLOWS, Collections.emptySet()));
            // A deliberate user decision takes ownership away from automatic
            // reconciliation before the shared blocklist is changed.
            automatic.removeIf(value -> automaticUid(value) == uid);
            if (internetBlocked)
                removeManualAllowsForUid(manualAllows, appContext, uid, packageName);
            else
                addManualAllowsForUid(manualAllows, appContext, uid, packageName);
            prefs.edit()
                    .putStringSet(KEY_AUTO_BLOCKS, automatic)
                    .putStringSet(KEY_MANUAL_ALLOWS, manualAllows)
                    .apply();
        }
    }

    /**
     * Called only for PACKAGE_FULLY_REMOVED, never the intermediate
     * PACKAGE_REMOVED used by package replacement. Removes ownership metadata
     * for the exact package and UID; only a recorded automatic deny is released.
     */
    public static void onPackageFullyRemoved(Context context, String packageName, int uid) {
        if (context == null || TextUtils.isEmpty(packageName) || uid < 0)
            return;
        Context appContext = context.getApplicationContext();
        synchronized (LOCK) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            Set<String> automatic = new HashSet<>(
                    prefs.getStringSet(KEY_AUTO_BLOCKS, Collections.emptySet()));
            Set<String> manualAllows = new HashSet<>(
                    prefs.getStringSet(KEY_MANUAL_ALLOWS, Collections.emptySet()));
            String automaticPrefix = uid + "|" + packageName + "|";
            String allowPrefix = packageName + "|" + uid + "|";
            boolean wasAutomatic = automatic.removeIf(value -> value.startsWith(automaticPrefix));
            manualAllows.removeIf(value -> value.startsWith(allowPrefix));
            prefs.edit()
                    .putStringSet(KEY_AUTO_BLOCKS, automatic)
                    .putStringSet(KEY_MANUAL_ALLOWS, manualAllows)
                    .apply();

            if (wasAutomatic) {
                InternetBlocklist blocklist = InternetBlocklist.getInstance(appContext);
                if (blocklist.blockedInternet(uid)) {
                    blocklist.unblock(uid);
                    blocklist.saveSettings(appContext);
                }
            }
        }
    }

    /** Protect the app's own service UID and core Android/network identities. */
    public static boolean isProtectedIdentity(Context context, String packageName, int uid) {
        return uid == Process.myUid() || isCriticalSystemPackage(packageName);
    }

    static boolean isCriticalSystemPackage(String packageName) {
        // Keep this pure helper safe for the plain JVM unit-test source set;
        // Android's TextUtils methods are not mocked there.
        return packageName != null && !packageName.isEmpty()
                && CRITICAL_SYSTEM_PACKAGES.contains(packageName);
    }

    private static boolean isAutomaticDenyExempt(PackageInfo info, int uid) {
        int flags = info.applicationInfo.flags;
        boolean systemPackage = (flags & (ApplicationInfo.FLAG_SYSTEM
                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        return systemPackage || uid == Process.myUid()
                || isCriticalSystemPackage(info.packageName);
    }

    static boolean shouldAutoBlock(boolean sharedUid, boolean hasInternetPermission,
            boolean manuallyAllowed, boolean alreadyBlocked) {
        return shouldAutoBlock(sharedUid, hasInternetPermission, manuallyAllowed,
                alreadyBlocked, false);
    }

    static boolean shouldAutoBlock(boolean sharedUid, boolean hasInternetPermission,
            boolean manuallyAllowed, boolean alreadyBlocked, boolean exempt) {
        return !sharedUid && !hasInternetPermission && !manuallyAllowed
                && !alreadyBlocked && !exempt;
    }

    static boolean shouldReleaseAutomaticBlock(boolean packagePresent, boolean sharedUid,
            boolean identityMatches) {
        // Absence can be an update/replacement gap; retain until a positive
        // package inventory or the explicit full-uninstall receiver confirms it.
        return packagePresent && (sharedUid || !identityMatches);
    }

    static String packageIdentity(PackageInfo info) {
        return info.packageName + "|" + info.firstInstallTime;
    }

    static String manualAllowSignature(String packageName, int uid, long firstInstallTime) {
        return packageName + "|" + uid + "|" + firstInstallTime;
    }

    private static boolean declaresInternet(PackageInfo info) {
        return info.requestedPermissions != null
                && Arrays.asList(info.requestedPermissions)
                        .contains(android.Manifest.permission.INTERNET);
    }

    private static String automaticEntry(int uid, String identity) {
        return uid + "|" + identity;
    }

    private static int automaticUid(String value) {
        if (value == null)
            return -1;
        int separator = value.indexOf('|');
        if (separator <= 0)
            return -1;
        try {
            return Integer.parseInt(value.substring(0, separator));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static Map<Integer, String> decodeAutomaticEntries(Set<String> values) {
        Map<Integer, String> result = new HashMap<>();
        for (String value : values) {
            int separator = value.indexOf('|');
            if (separator <= 0 || separator == value.length() - 1)
                continue;
            try {
                int uid = Integer.parseInt(value.substring(0, separator));
                result.put(uid, value.substring(separator + 1));
            } catch (NumberFormatException ignored) {
                // Keep malformed metadata inert; it cannot prove ownership.
            }
        }
        return result;
    }

    private static void addManualAllowsForUid(Set<String> signatures, Context context,
            int uid, String fallbackPackage) {
        String[] packages = Util.getPackagesForUid(context.getPackageManager(), uid);
        if (packages == null || packages.length == 0)
            packages = new String[] { fallbackPackage };
        for (String packageName : packages) {
            if (TextUtils.isEmpty(packageName))
                continue;
            try {
                PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
                signatures.add(manualAllowSignature(packageName, uid, info.firstInstallTime));
            } catch (PackageManager.NameNotFoundException ignored) {
                signatures.add(manualAllowSignature(packageName, uid, 0L));
            }
        }
    }

    private static void removeManualAllowsForUid(Set<String> signatures, Context context,
            int uid, String fallbackPackage) {
        signatures.removeIf(value -> value.startsWith(fallbackPackage + "|" + uid + "|"));
        String[] packages = Util.getPackagesForUid(context.getPackageManager(), uid);
        if (packages != null) {
            for (String packageName : packages) {
                if (!TextUtils.isEmpty(packageName))
                    signatures.removeIf(value -> value.startsWith(packageName + "|" + uid + "|"));
            }
        }
    }
}
