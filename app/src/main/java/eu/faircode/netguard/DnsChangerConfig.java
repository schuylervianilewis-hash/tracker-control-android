/*
 * Vianshield DNS selection configuration.
 *
 * This file is part of TrackerControl and is licensed under the GNU General
 * Public License, version 3 or (at your option) any later version.
 */
package eu.faircode.netguard;

import android.content.SharedPreferences;

import net.kollnig.missioncontrol.wg.WgConfig;
import net.kollnig.missioncontrol.wg.WgConfigParser;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/** Preference and preset helper for the DNS Changer. */
public final class DnsChangerConfig {
    public static final String PREF_DNS_MODE = "dns_mode";
    public static final String MODE_AUTOMATIC = "automatic";
    public static final String MODE_CLOUDFLARE = "cloudflare";
    public static final String MODE_GOOGLE = "google";
    public static final String MODE_QUAD9 = "quad9";
    public static final String MODE_WIREGUARD = "wireguard";
    public static final String MODE_CUSTOM = "custom";

    private DnsChangerConfig() {
    }

    /**
     * A missing preference deliberately preserves pre-DNS-Changer behaviour:
     * profile DNS while WireGuard is active, otherwise legacy dns/dns2 values
     * (or network DNS when no custom values exist).
     */
    public static String getSelectedMode(SharedPreferences prefs, boolean wireGuardActive) {
        if (prefs.contains(PREF_DNS_MODE)) {
            try {
                String mode = prefs.getString(PREF_DNS_MODE, MODE_AUTOMATIC);
                return isKnownMode(mode) ? mode : MODE_AUTOMATIC;
            } catch (ClassCastException ignored) {
                return MODE_AUTOMATIC;
            }
        }
        if (wireGuardActive)
            return MODE_WIREGUARD;
        if (hasText(prefs.getString("dns", null)) ||
                hasText(prefs.getString("dns2", null)))
            return MODE_CUSTOM;
        return MODE_AUTOMATIC;
    }


    /**
     * Keep the old Advanced Settings fields and the DNS Changer selector in
     * sync without allowing cleared legacy fields to erase another selection.
     */
    public static void syncLegacyDnsSettings(SharedPreferences prefs) {
        boolean hasCustomDns = hasText(prefs.getString("dns", null)) ||
                hasText(prefs.getString("dns2", null));
        String currentMode;
        try {
            currentMode = prefs.getString(PREF_DNS_MODE, null);
        } catch (ClassCastException ignored) {
            currentMode = null;
        }

        SharedPreferences.Editor editor = prefs.edit();
        if (hasCustomDns) {
            editor.putString(PREF_DNS_MODE, MODE_CUSTOM);
            editor.apply();
        } else if (MODE_CUSTOM.equals(currentMode)) {
            editor.remove(PREF_DNS_MODE);
            editor.apply();
        }
    }

    public static boolean isKnownMode(String mode) {
        return MODE_AUTOMATIC.equals(mode) ||
                MODE_CLOUDFLARE.equals(mode) ||
                MODE_GOOGLE.equals(mode) ||
                MODE_QUAD9.equals(mode) ||
                MODE_WIREGUARD.equals(mode) ||
                MODE_CUSTOM.equals(mode);
    }

    public static boolean isProviderMode(String mode) {
        return MODE_CLOUDFLARE.equals(mode) ||
                MODE_GOOGLE.equals(mode) ||
                MODE_QUAD9.equals(mode);
    }

    public static boolean isWireGuardActive(SharedPreferences prefs) {
        if (!prefs.getBoolean("wg_enabled", false))
            return false;
        String config = prefs.getString("wg_config", "");
        if (!hasText(config))
            return false;
        try {
            WgConfigParser.INSTANCE.parse(config);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Numeric DNS entries from the active profile, for the status line only. */
    public static List<String> getWireGuardProfileDns(SharedPreferences prefs) {
        List<String> result = new ArrayList<>();
        if (!isWireGuardActive(prefs))
            return result;
        try {
            WgConfig config = WgConfigParser.INSTANCE.parse(prefs.getString("wg_config", ""));
            for (String entry : config.getDns()) {
                String value = entry == null ? "" : entry.trim();
                InetAddress address = parseNumeric(value);
                if (address != null && isUsable(address) && !result.contains(value))
                    result.add(value);
            }
        } catch (Throwable ignored) {
            // The WireGuard screen already owns config validation and error UI.
        }
        return result;
    }

    /**
     * Return a provider's resolver pair using the address family advertised by
     * the underlying network. IPv6 is used only on IPv6-only networks when the
     * IPv6 route is enabled; otherwise retain the existing IPv4 preference.
     */
    public static List<InetAddress> getProviderDns(
            String mode, boolean ipv6Enabled, List<String> systemDns) {
        String[] ipv4;
        String[] ipv6;
        switch (mode == null ? "" : mode) {
            case MODE_CLOUDFLARE:
                ipv4 = new String[]{"1.1.1.1", "1.0.0.1"};
                ipv6 = new String[]{"2606:4700:4700::1111", "2606:4700:4700::1001"};
                break;
            case MODE_GOOGLE:
                ipv4 = new String[]{"8.8.8.8", "8.8.4.4"};
                ipv6 = new String[]{"2001:4860:4860::8888", "2001:4860:4860::8844"};
                break;
            case MODE_QUAD9:
                ipv4 = new String[]{"9.9.9.9", "149.112.112.112"};
                ipv6 = new String[]{"2620:fe::fe", "2620:fe::9"};
                break;
            default:
                return new ArrayList<>();
        }

        boolean hasIpv4 = false;
        boolean hasIpv6 = false;
        if (systemDns != null) {
            for (String candidate : systemDns) {
                InetAddress address = parseNumeric(candidate);
                if (address == null || !isUsable(address))
                    continue;
                hasIpv4 |= address instanceof Inet4Address;
                hasIpv6 |= address instanceof Inet6Address;
            }
        }

        String[] selected = (!hasIpv4 && ipv6Enabled && hasIpv6) ? ipv6 : ipv4;
        List<InetAddress> result = new ArrayList<>(selected.length);
        for (String address : selected) {
            InetAddress parsed = parseNumeric(address);
            if (parsed != null)
                result.add(parsed);
        }
        return result;
    }

    /** Return only explicitly configured custom resolvers, in entered order. */
    public static List<InetAddress> getCustomDns(SharedPreferences prefs, boolean ipv6Enabled) {
        List<InetAddress> result = new ArrayList<>(2);
        addCustomAddress(result, prefs.getString("dns", null), ipv6Enabled);
        addCustomAddress(result, prefs.getString("dns2", null), ipv6Enabled);
        return result;
    }

    /** @return null when valid, otherwise a concise validation message for UI. */
    public static String validateCustomDns(
            String primary, String secondary, boolean ipv6Enabled) {
        String first = primary == null ? "" : primary.trim();
        String second = secondary == null ? "" : secondary.trim();
        if (first.isEmpty())
            return "Enter a primary DNS address.";

        InetAddress firstAddress = parseNumeric(first);
        if (firstAddress == null)
            return "Enter a numeric IPv4 or IPv6 address for the primary DNS.";
        if (!isUsable(firstAddress))
            return "The primary DNS address must be a usable unicast address.";
        if (!ipv6Enabled && firstAddress instanceof Inet6Address)
            return "Enable IPv6 in Advanced Settings before using an IPv6 DNS address.";

        if (!second.isEmpty()) {
            InetAddress secondAddress = parseNumeric(second);
            if (secondAddress == null)
                return "Enter a numeric IPv4 or IPv6 address for the secondary DNS.";
            if (!isUsable(secondAddress))
                return "The secondary DNS address must be a usable unicast address.";
            if (!ipv6Enabled && secondAddress instanceof Inet6Address)
                return "Enable IPv6 in Advanced Settings before using an IPv6 DNS address.";
            if (firstAddress.equals(secondAddress))
                return "Primary and secondary DNS addresses must be different.";
        }
        return null;
    }

    private static void addCustomAddress(
            List<InetAddress> result, String raw, boolean ipv6Enabled) {
        InetAddress address = parseNumeric(raw);
        if (address == null || !isUsable(address))
            return;
        if (!ipv6Enabled && address instanceof Inet6Address)
            return;
        if (!result.contains(address))
            result.add(address);
    }

    private static InetAddress parseNumeric(String raw) {
        if (!hasText(raw))
            return null;
        String value = raw.trim();
        if (!isNumericAddressLiteral(value))
            return null;
        try {
            // This value has already been validated as a numeric IP literal,
            // so getByName parses it locally and never performs DNS lookup.
            return InetAddress.getByName(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isNumericAddressLiteral(String value) {
        if (value.indexOf(':') >= 0)
            return value.matches("[0-9A-Fa-f:.]+");

        String[] octets = value.split("\\.", -1);
        if (octets.length != 4)
            return false;

        for (String octet : octets) {
            if (octet.isEmpty() || (octet.length() > 1 && octet.charAt(0) == '0'))
                return false;
            int number = 0;
            for (int i = 0; i < octet.length(); i++) {
                char digit = octet.charAt(i);
                if (digit < '0' || digit > '9')
                    return false;
                number = number * 10 + (digit - '0');
                if (number > 255)
                    return false;
            }
        }
        return true;
    }

    static List<InetAddress> parseWireGuardDns(List<String> entries) {
        List<InetAddress> result = new ArrayList<>();
        if (entries == null)
            return result;

        for (String entry : entries) {
            InetAddress address = parseNumeric(entry);
            // Preserve the pre-existing WG DNS acceptance rules exactly:
            // only loopback and unspecified addresses are filtered here.
            if (address == null || address.isLoopbackAddress() ||
                    address.isAnyLocalAddress())
                continue;
            result.add(address);
        }
        return result;
    }

    private static boolean isUsable(InetAddress address) {
        return address != null &&
                !address.isLoopbackAddress() &&
                !address.isAnyLocalAddress() &&
                !address.isLinkLocalAddress() &&
                !address.isMulticastAddress();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
