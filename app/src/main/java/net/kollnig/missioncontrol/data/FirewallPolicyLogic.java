/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.data;

import java.util.Comparator;
import java.util.Locale;

/**
 * Pure presentation rules for the Firewall app list.
 *
 * Policy priority is always the primary key (like folders-first sorting in a
 * file manager). The user's chosen sort is only applied inside each priority.
 */
public final class FirewallPolicyLogic {
    public static final String SORT_NAME = "name";
    public static final String SORT_INSTALL_DATE = "install_date";

    public static final int PRIORITY_BYPASS = 0;
    public static final int PRIORITY_VPN = 1;
    public static final int PRIORITY_BLOCKED = 2;

    private FirewallPolicyLogic() {
    }

    /**
     * Resolve the visible list priority from effective policy state.
     * An app excluded from TrackerControl is direct but is not a firewall
     * Bypass; the UI must additionally identify that exceptional state.
     */
    public static int priority(boolean apply, boolean internetBlocked, boolean tunnelled) {
        if (!apply)
            return PRIORITY_BYPASS;
        if (internetBlocked)
            return PRIORITY_BLOCKED;
        return tunnelled ? PRIORITY_VPN : PRIORITY_BYPASS;
    }

    public static Comparator<Entry> comparator(String sortMode) {
        final boolean byInstallDate = SORT_INSTALL_DATE.equals(sortMode);
        return (left, right) -> {
            int policy = Integer.compare(left.priority, right.priority);
            if (policy != 0)
                return policy;

            int secondary;
            if (byInstallDate) {
                // Most recently installed first, like newest-first file sorting.
                secondary = Long.compare(right.firstInstallTime, left.firstInstallTime);
            } else {
                secondary = left.name.toLowerCase(Locale.ROOT)
                        .compareTo(right.name.toLowerCase(Locale.ROOT));
            }
            if (secondary != 0)
                return secondary;

            int name = left.name.compareToIgnoreCase(right.name);
            if (name != 0)
                return name;
            return left.packageName.compareTo(right.packageName);
        };
    }

    public static final class Entry {
        public final int priority;
        public final String name;
        public final String packageName;
        public final long firstInstallTime;

        public Entry(int priority, String name, String packageName, long firstInstallTime) {
            this.priority = priority;
            this.name = name == null ? "" : name;
            this.packageName = packageName == null ? "" : packageName;
            this.firstInstallTime = firstInstallTime;
        }
    }
}
