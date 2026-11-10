/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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
 * Applies default-deny to apps first detected without INTERNET permission.
 * The UID blocklist stores enforcement; this class records the origin of any
 * automatically-created block so it never removes a user's manual decision.
 */
public final class InternetPermissionPolicy {
    private static final String PREFS = "internet_permission_policy";
    private static final String KEY_AUTO_BLOCKS = "auto_blocked_uid_signatures";
    private static final String KEY_MANUAL_ALLOWS = "manual_allow_signatures";
    private static final Object LOCK = new Object();

    private InternetPermissionPolicy() {
    }

    /**
     * Reconcile installed packages. The caller reloads the packet service when
     * this returns true. Permission inventory failures are treated as unknown,
     * never as proof that an app lacks INTERNET permission.
     */
    public static boolean reconcile(Context context) {
        Context appContext = context.getApplicationContext();
        List<PackageInfo> installed;
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
            Set<String> currentAllowSignatures = new HashSet<>();
            for (PackageInfo info : installed) {
                if (info == null || info.applicationInfo == null
                        || TextUtils.isEmpty(info.packageName)
                        || info.applicationInfo.uid < 0)
                    continue;
                byUid.computeIfAbsent(info.applicationInfo.uid, ignored -> new ArrayList<>()).add(info);
                currentAllowSignatures.add(manualAllowSignature(
                        info.packageName, info.applicationInfo.uid, info.firstInstallTime));
            }
            manualAllows.retainAll(currentAllowSignatures);

            InternetBlocklist blocklist = InternetBlocklist.getInstance(appContext);
            boolean blocklistChanged = false;
            Map<Integer, String> autoByUid = decodeAutomaticEntries(automatic);

            // An automatic deny is removed only if its original unique package
            // disappeared, changed identity (reinstall/UID reuse), or gained a
            // shared-UID sibling. A manual deny has no entry here and is untouched.
            for (Map.Entry<Integer, String> entry : autoByUid.entrySet()) {
                int uid = entry.getKey();
                List<PackageInfo> members = byUid.get(uid);
                if (members == null || members.isEmpty()
                        || members.size() != 1
                        || !entry.getValue().equals(packageIdentity(members.get(0)))) {
                    if (blocklist.blockedInternet(uid)) {
                        blocklist.unblock(uid);
                        blocklistChanged = true;
                    }
                    automatic.remove(automaticEntry(uid, entry.getValue()));
                }
                // Same package and first-install identity: keep the deny through
                // updates, even if the update has now declared INTERNET.
            }

            for (Map.Entry<Integer, List<PackageInfo>> entry : byUid.entrySet()) {
                int uid = entry.getKey();
                List<PackageInfo> members = entry.getValue();
                if (members.size() != 1)
                    continue;
                PackageInfo info = members.get(0);
                boolean allowedByUser = manualAllows.contains(manualAllowSignature(
                        info.packageName, uid, info.firstInstallTime));
                if (!shouldAutoBlock(false, declaresInternet(info), allowedByUser,
                        blocklist.blockedInternet(uid)))
                    continue;

                String identity = packageIdentity(info);
                if (autoByUid.containsKey(uid))
                    continue;
                blocklist.block(uid);
                automatic.add(automaticEntry(uid, identity));
                blocklistChanged = true;
            }

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

    static boolean shouldAutoBlock(boolean sharedUid, boolean hasInternetPermission,
            boolean manuallyAllowed, boolean alreadyBlocked) {
        return !sharedUid && !hasInternetPermission && !manuallyAllowed && !alreadyBlocked;
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
                // Preserve the runtime blocklist and ignore malformed origin metadata.
            }
        }
        return result;
    }
}
