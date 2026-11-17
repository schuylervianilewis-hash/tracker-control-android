/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package eu.faircode.netguard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * User-managed exceptions to ad/tracker domain filtering.
 *
 * <p>Entries are stored as normalized DNS names. A saved name also matches its
 * subdomains, but never a different name that merely shares a suffix.</p>
 */
public final class DomainWhitelist {
    static final String PREFERENCES_NAME = "domain_whitelist";
    static final String PREFERENCES_KEY = "domains";

    private static volatile DomainWhitelist instance;

    private final SharedPreferences preferences;
    private final Object lock = new Object();
    private volatile Set<String> domains;

    private DomainWhitelist(Context context) {
        Context appContext = context.getApplicationContext();
        preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        Set<String> saved = preferences.getStringSet(PREFERENCES_KEY, Collections.emptySet());
        domains = immutableNormalizedCopy(saved);
    }

    public static DomainWhitelist getInstance(Context context) {
        DomainWhitelist current = instance;
        if (current != null)
            return current;

        synchronized (DomainWhitelist.class) {
            current = instance;
            if (current == null) {
                current = new DomainWhitelist(context);
                instance = current;
            }
            return current;
        }
    }

    /** Returns an immutable, alphabetically ordered snapshot for UI display. */
    public Set<String> getDomains() {
        return domains;
    }

    /** Adds a normalized domain. Returns false for invalid or duplicate input. */
    public boolean addDomain(String hostname) {
        String normalized = BlockedDomainLogic.normalize(hostname);
        if (normalized == null)
            return false;

        synchronized (lock) {
            if (domains.contains(normalized))
                return false;

            Set<String> updated = new TreeSet<>(domains);
            updated.add(normalized);
            publish(updated);
            return true;
        }
    }

    /** Removes an exact saved entry. Returns false if it was not present. */
    public boolean removeDomain(String hostname) {
        String normalized = BlockedDomainLogic.normalize(hostname);
        if (normalized == null)
            return false;

        synchronized (lock) {
            if (!domains.contains(normalized))
                return false;

            Set<String> updated = new TreeSet<>(domains);
            updated.remove(normalized);
            publish(updated);
            return true;
        }
    }

    /**
     * Checks a confidently resolved hostname against the saved domains.
     * Matching walks DNS label boundaries, so example.com matches
     * ads.example.com but not notexample.com.
     */
    public boolean isWhitelisted(String hostname) {
        return matches(domains, hostname);
    }

    static boolean matches(Set<String> whitelist, String hostname) {
        String normalized = BlockedDomainLogic.normalize(hostname);
        if (normalized == null || whitelist == null || whitelist.isEmpty())
            return false;

        String candidate = normalized;
        while (candidate != null) {
            if (whitelist.contains(candidate))
                return true;
            int dot = candidate.indexOf('.');
            candidate = dot < 0 ? null : candidate.substring(dot + 1);
        }
        return false;
    }

    private void publish(Set<String> updated) {
        Set<String> snapshot = Collections.unmodifiableSet(new TreeSet<>(updated));
        // SharedPreferences copies the set on write; never expose the mutable copy.
        preferences.edit().putStringSet(PREFERENCES_KEY, new HashSet<>(snapshot)).apply();
        domains = snapshot;
    }

    private static Set<String> immutableNormalizedCopy(Set<String> source) {
        Set<String> normalized = new TreeSet<>();
        if (source != null) {
            for (String value : source) {
                String domain = BlockedDomainLogic.normalize(value);
                if (domain != null)
                    normalized.add(domain);
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    static synchronized void resetForTests() {
        instance = null;
    }
}
