package eu.faircode.netguard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Locale;
import java.util.Map;

public final class HostsBlocklistLogic {
    interface Logger {
        void log(String message);
    }

    private static final Logger NOOP_LOGGER = message -> {
    };

    /**
     * Strip a single trailing root-zone dot (as in {@code ads.example.}), so a
     * hosts entry written in that fully-qualified form still matches the qname
     * the DNS parser hands back, which never carries one. A bare "." is left
     * alone rather than reduced to an empty string.
     */
    public static String stripTrailingDot(String hostname) {
        int length = hostname.length();
        return (length > 1 && hostname.charAt(length - 1) == '.')
                ? hostname.substring(0, length - 1) : hostname;
    }

    static final class State {
        private final Map<String, Boolean> mapHostsBlocked;
        private final Logger logger;
        private long lastModified;

        State(Map<String, Boolean> mapHostsBlocked, long lastModified) {
            this(mapHostsBlocked, lastModified, NOOP_LOGGER);
        }

        State(Map<String, Boolean> mapHostsBlocked, long lastModified, Logger logger) {
            this.mapHostsBlocked = mapHostsBlocked;
            this.lastModified = lastModified;
            this.logger = logger;
        }

        boolean shouldReload(long modified) {
            return modified != lastModified || mapHostsBlocked.size() == 0;
        }

        boolean load(Reader reader, long modified) throws IOException {
            if (!shouldReload(modified))
                return false;

            parse(reader);
            lastModified = modified;
            return true;
        }

        void parse(Reader reader) throws IOException {
            mapHostsBlocked.clear();
            BufferedReader br = reader instanceof BufferedReader
                    ? (BufferedReader) reader : new BufferedReader(reader);
            int count = 0;
            String line;
            while ((line = br.readLine()) != null) {
                int hash = line.indexOf('#');
                if (hash >= 0)
                    line = line.substring(0, hash);
                line = line.trim();
                if (line.length() > 0) {
                    String[] words = line.split("\\s+");
                    // hosts(5) allows several hostnames after the address on one
                    // line, which merged blocklists rely on; only a bare address
                    // with no hostname at all is invalid.
                    if (words.length >= 2) {
                        for (int i = 1; i < words.length; i++) {
                            count++;
                            // Keyed lowercase to match TrackerList.findTracker(),
                            // which normalises qnames before the hosts lookup.
                            mapHostsBlocked.put(
                                    stripTrailingDot(words[i].toLowerCase(Locale.ROOT)), true);
                        }
                    } else
                        logger.log("Invalid hosts file line: " + line);
                }
            }
            mapHostsBlocked.put("test.netguard.me", true);
            logger.log(count + " hosts read");
        }

        long getLastModified() {
            return lastModified;
        }
    }

    /**
     * Validate a local hosts file without resolving names or modifying the active map.
     */
    public static boolean hasUsableEntries(Reader reader) throws IOException {
        BufferedReader br = reader instanceof BufferedReader ? (BufferedReader) reader : new BufferedReader(reader);
        String line;
        while ((line = br.readLine()) != null) {
            int hash = line.indexOf('#');
            if (hash >= 0)
                line = line.substring(0, hash);
            String[] words = line.trim().split("\\s+");
            if (words.length < 2 || !looksLikeIpLiteral(words[0]))
                continue;
            for (int i = 1; i < words.length; i++) {
                String host = stripTrailingDot(words[i].toLowerCase(Locale.ROOT));
                if (isValidHostname(host))
                    return true;
            }
        }
        return false;
    }

    private static boolean looksLikeIpLiteral(String value) {
        if (value.indexOf(':') >= 0)
            return value.matches("[0-9a-fA-F:.]+");
        return value.matches("(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])(?:\\.(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])){3}");
    }

    private static boolean isValidHostname(String host) {
        if (host.length() == 0 || host.length() > 253 || host.indexOf(':') >= 0 || host.matches("[0-9.]+"))
            return false;
        for (String label : host.split("\\.", -1))
            if (label.length() == 0 || label.length() > 63
                    || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))
                return false;
        return true;
    }

    private HostsBlocklistLogic() {
    }
}
