/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.data;

import java.util.Comparator;
import java.util.Locale;

/** Pure policy grouping and secondary sorting rules for the Firewall app list. */
public final class FirewallPolicyLogic {
    public static final String SORT_NAME = "name";
    public static final String SORT_INSTALL_DATE = "install_date";
    public static final String SORT_TRACKERS_WEEK = "trackers_week";
    public static final String SORT_TRACKERS_ALL = "trackers_all";
    public static final String SORT_TRACKERS_RECENT = "trackers_recent";
    public static final String SORT_UID = "uid";

    public static final int PRIORITY_BLOCKED = 0;
    public static final int PRIORITY_VPN = 1;
    public static final int PRIORITY_DIRECT = 2;
    public static final int PRIORITY_EXCLUDED = 3;

    public static final int QUICK_ACTION_NONE = 0;
    public static final int QUICK_ACTION_BLOCK = 1;
    public static final int QUICK_ACTION_VPN = 2;
    public static final int QUICK_ACTION_DIRECT = 3;

    private FirewallPolicyLogic() {
    }

    /**
     * Apply Firewall's independent app-list visibility preferences.
     *
     * This is presentation-only filtering. It must not inherit Tracker's
     * show_user/show_system/show_nointernet/show_frozen/show_unprotected
     * preferences or change any enforcement policy.
     */
    public static boolean isVisible(boolean systemApp, boolean hasInternet, boolean enabled,
            boolean unprotected, boolean showUserApps, boolean showSystemApps,
            boolean showAppsWithoutInternet, boolean showFrozenApps,
            boolean showUnprotectedApps) {
        return (systemApp ? showSystemApps : showUserApps)
                && (showAppsWithoutInternet || hasInternet)
                && (showFrozenApps || enabled)
                && (!showUnprotectedApps || unprotected);
    }

    /** Resolve priority from effective policy. Excluded apps are not Direct. */
    public static int priority(boolean apply, boolean internetBlocked, boolean tunnelled) {
        if (!apply)
            return PRIORITY_EXCLUDED;
        if (internetBlocked)
            return PRIORITY_BLOCKED;
        return tunnelled ? PRIORITY_VPN : PRIORITY_DIRECT;
    }

    /**
     * Resolve the collapsed Firewall row's single selected quick action.
     * Excluded is deliberately unselected: Exclude from TrackerControl is not
     * Direct. Block takes precedence over remote routing.
     */
    public static int quickAction(boolean apply, boolean internetBlocked,
            boolean remoteVpnAvailable, boolean tunnelled) {
        if (!apply)
            return QUICK_ACTION_NONE;
        if (internetBlocked)
            return QUICK_ACTION_BLOCK;
        return remoteVpnAvailable && tunnelled
                ? QUICK_ACTION_VPN : QUICK_ACTION_DIRECT;
    }

    public static Comparator<Entry> comparator(String sortMode) {
        final String sort = sortMode == null ? SORT_NAME : sortMode;
        return (left, right) -> {
            int policy = Integer.compare(left.priority, right.priority);
            if (policy != 0)
                return policy;

            int secondary;
            switch (sort) {
                case SORT_INSTALL_DATE:
                    secondary = Long.compare(right.firstInstallTime, left.firstInstallTime);
                    break;
                case SORT_TRACKERS_WEEK:
                    secondary = Integer.compare(right.trackerCountWeek, left.trackerCountWeek);
                    break;
                case SORT_TRACKERS_ALL:
                    secondary = Integer.compare(right.trackerCountAll, left.trackerCountAll);
                    break;
                case SORT_TRACKERS_RECENT:
                    secondary = Long.compare(right.lastTrackerTime, left.lastTrackerTime);
                    break;
                case SORT_UID:
                    secondary = Integer.compare(left.uid, right.uid);
                    break;
                case SORT_NAME:
                default:
                    secondary = left.name.toLowerCase(Locale.ROOT)
                            .compareTo(right.name.toLowerCase(Locale.ROOT));
                    break;
            }
            if (secondary != 0)
                return secondary;

            int name = left.name.compareToIgnoreCase(right.name);
            if (name != 0)
                return name;
            int packageName = left.packageName.compareTo(right.packageName);
            if (packageName != 0)
                return packageName;
            return Integer.compare(left.uid, right.uid);
        };
    }

    public static boolean isSupportedSort(String sortMode) {
        return SORT_NAME.equals(sortMode)
                || SORT_INSTALL_DATE.equals(sortMode)
                || SORT_TRACKERS_WEEK.equals(sortMode)
                || SORT_TRACKERS_ALL.equals(sortMode)
                || SORT_TRACKERS_RECENT.equals(sortMode)
                || SORT_UID.equals(sortMode);
    }

    public static final class Entry {
        public final int priority;
        public final String name;
        public final String packageName;
        public final long firstInstallTime;
        public final int uid;
        public final int trackerCountWeek;
        public final int trackerCountAll;
        public final long lastTrackerTime;

        public Entry(int priority, String name, String packageName, long firstInstallTime,
                int uid, int trackerCountWeek, int trackerCountAll, long lastTrackerTime) {
            this.priority = priority;
            this.name = name == null ? "" : name;
            this.packageName = packageName == null ? "" : packageName;
            this.firstInstallTime = firstInstallTime;
            this.uid = uid;
            this.trackerCountWeek = trackerCountWeek;
            this.trackerCountAll = trackerCountAll;
            this.lastTrackerTime = lastTrackerTime;
        }
    }
}
