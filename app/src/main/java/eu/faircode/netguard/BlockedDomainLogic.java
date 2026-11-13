package eu.faircode.netguard;

import java.util.Locale;

/** Pure hostname validation shared by blocked-domain capture and storage. */
public final class BlockedDomainLogic {
    private BlockedDomainLogic() {
    }

    /** Returns a normalized DNS hostname, or null for malformed or IP-literal input. */
    public static String normalize(String hostname) {
        if (hostname == null)
            return null;
        String value = HostsBlocklistLogic.stripTrailingDot(hostname.trim().toLowerCase(Locale.ROOT));
        if (value.length() == 0 || value.length() > 253 || value.indexOf(':') >= 0)
            return null;
        if (value.matches("[0-9.]+"))
            return null;
        String[] labels = value.split("\\.", -1);
        for (String label : labels)
            if (label.length() == 0 || label.length() > 63
                    || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))
                return null;
        return value;
    }
}
