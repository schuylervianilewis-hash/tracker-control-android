package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class DnsChangerConfigTest {
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        prefs = PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication());
        prefs.edit().clear().commit();
    }

    @Test
    public void missingModePreservesLegacyAndWireGuardBehaviour() {
        assertEquals(DnsChangerConfig.MODE_AUTOMATIC,
                DnsChangerConfig.getSelectedMode(prefs, false));

        prefs.edit().putString("dns", "192.0.2.53").commit();
        assertEquals(DnsChangerConfig.MODE_CUSTOM,
                DnsChangerConfig.getSelectedMode(prefs, false));

        assertEquals(DnsChangerConfig.MODE_WIREGUARD,
                DnsChangerConfig.getSelectedMode(prefs, true));
    }

    @Test
    public void storedModeOverridesInferredMode() {
        prefs.edit()
                .putString("dns", "192.0.2.53")
                .putString(DnsChangerConfig.PREF_DNS_MODE, DnsChangerConfig.MODE_QUAD9)
                .commit();

        assertEquals(DnsChangerConfig.MODE_QUAD9,
                DnsChangerConfig.getSelectedMode(prefs, true));
    }

    @Test
    public void cloudflareUsesIpv4WhenUnderlyingNetworkHasIpv4Dns() {
        List<InetAddress> result = DnsChangerConfig.getProviderDns(
                DnsChangerConfig.MODE_CLOUDFLARE, true,
                Arrays.asList("192.168.1.1", "2001:4860:4860::8888"));

        assertEquals(2, result.size());
        assertEquals("1.1.1.1", result.get(0).getHostAddress());
        assertEquals("1.0.0.1", result.get(1).getHostAddress());
    }

    @Test
    public void googleUsesIpv6OnIpv6OnlyNetworkWhenEnabled() {
        List<InetAddress> result = DnsChangerConfig.getProviderDns(
                DnsChangerConfig.MODE_GOOGLE, true,
                Arrays.asList("2001:4860:4860::8888"));

        assertEquals(2, result.size());
        assertTrue(result.get(0) instanceof java.net.Inet6Address);
        assertEquals("2001:4860:4860:0:0:0:0:8888", result.get(0).getHostAddress());
        assertEquals("2001:4860:4860:0:0:0:0:8844", result.get(1).getHostAddress());
    }

    @Test
    public void ipv4IsUsedWhenIpv6IsDisabled() {
        List<InetAddress> result = DnsChangerConfig.getProviderDns(
                DnsChangerConfig.MODE_QUAD9, false,
                Arrays.asList("2001:4860:4860::8888"));

        assertEquals("9.9.9.9", result.get(0).getHostAddress());
        assertEquals("149.112.112.112", result.get(1).getHostAddress());
    }

    @Test
    public void customAddressValidationRequiresDistinctUsableNumericAddresses() {
        assertEquals("Enter a primary DNS address.",
                DnsChangerConfig.validateCustomDns("", "", true));
        assertNull(DnsChangerConfig.validateCustomDns("192.0.2.53", "", true));
        assertNull(DnsChangerConfig.validateCustomDns(
                "192.0.2.53", "2001:4860:4860::8888", true));
        assertTrue(DnsChangerConfig.validateCustomDns("dns.google", "", true).contains("numeric"));
        assertTrue(DnsChangerConfig.validateCustomDns(
                "192.0.2.53", "192.0.2.53", true).contains("different"));
        assertTrue(DnsChangerConfig.validateCustomDns("127.0.0.1", "", true).contains("usable"));
        assertTrue(DnsChangerConfig.validateCustomDns(
                "2001:4860:4860::8888", "", false).contains("Enable IPv6"));
    }

    @Test
    public void customDnsKeepsOnlyEnteredUniqueUsableAddresses() {
        prefs.edit()
                .putString("dns", "192.0.2.53")
                .putString("dns2", "192.0.2.54")
                .commit();

        List<InetAddress> result = DnsChangerConfig.getCustomDns(prefs, true);
        assertEquals(2, result.size());
        assertEquals("192.0.2.53", result.get(0).getHostAddress());
        assertEquals("192.0.2.54", result.get(1).getHostAddress());
    }

    @Test
    public void advancedDnsFieldsPreserveSelectedProviderWhenCleared() {
        prefs.edit().putString(DnsChangerConfig.PREF_DNS_MODE,
                DnsChangerConfig.MODE_CLOUDFLARE).commit();

        DnsChangerConfig.syncLegacyDnsSettings(prefs);

        assertEquals(DnsChangerConfig.MODE_CLOUDFLARE,
                prefs.getString(DnsChangerConfig.PREF_DNS_MODE, null));
    }

    @Test
    public void advancedDnsFieldsSetAndClearCustomMode() {
        prefs.edit().putString("dns", "192.0.2.53").commit();

        DnsChangerConfig.syncLegacyDnsSettings(prefs);
        assertEquals(DnsChangerConfig.MODE_CUSTOM,
                prefs.getString(DnsChangerConfig.PREF_DNS_MODE, null));

        prefs.edit().remove("dns").remove("dns2").commit();
        DnsChangerConfig.syncLegacyDnsSettings(prefs);
        assertFalse(prefs.contains(DnsChangerConfig.PREF_DNS_MODE));
    }

    @Test
    public void wireGuardDnsParserAcceptsNumericAddressesAndSkipsSearchDomains() {
        List<InetAddress> result = DnsChangerConfig.parseWireGuardDns(
                Arrays.asList("10.64.0.1", "example.com", "2001:4860:4860::8888",
                        "127.0.0.1"));

        assertEquals(2, result.size());
        assertEquals("10.64.0.1", result.get(0).getHostAddress());
        assertTrue(result.get(1) instanceof java.net.Inet6Address);
    }

    @Test
    public void invalidStoredModeFallsBackToAutomatic() {
        prefs.edit().putString(DnsChangerConfig.PREF_DNS_MODE, "not-a-mode").commit();
        assertEquals(DnsChangerConfig.MODE_AUTOMATIC,
                DnsChangerConfig.getSelectedMode(prefs, false));
    }
}
