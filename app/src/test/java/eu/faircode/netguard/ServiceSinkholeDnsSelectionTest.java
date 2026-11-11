package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.wg.WgConfig;
import net.kollnig.missioncontrol.wg.WgConfigParser;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.net.InetAddress;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ServiceSinkholeDnsSelectionTest {
    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String WG_CONFIG =
            "[Interface]\n" +
            "PrivateKey = " + KEY + "\n" +
            "Address = 10.64.0.2/32\n" +
            "%s" +
            "\n[Peer]\n" +
            "PublicKey = " + KEY + "\n" +
            "AllowedIPs = 0.0.0.0/0\n" +
            "Endpoint = 198.51.100.1:51820\n";

    private SharedPreferences prefs;
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        prefs.edit()
                .putBoolean("wg_enabled", true)
                .putString("wg_config", String.format(WG_CONFIG, "DNS = 10.64.0.1\n"))
                .commit();
    }

    private WgConfig parse(String dnsLine) throws Exception {
        return WgConfigParser.INSTANCE.parse(String.format(WG_CONFIG, dnsLine));
    }

    @Test
    public void missingDnsModeKeepsUsingWireGuardProfileDns() throws Exception {
        List<InetAddress> result = ServiceSinkhole.getWireGuardDnsForSelection(
                context, prefs, parse("DNS = 10.64.0.1\n"));

        assertEquals(1, result.size());
        assertEquals("10.64.0.1", result.get(0).getHostAddress());
    }

    @Test
    public void explicitProviderOverridesWireGuardProfileDns() throws Exception {
        prefs.edit().putString(
                DnsChangerConfig.PREF_DNS_MODE,
                DnsChangerConfig.MODE_CLOUDFLARE).commit();

        List<InetAddress> result = ServiceSinkhole.getWireGuardDnsForSelection(
                context, prefs, parse("DNS = 10.64.0.1\n"));

        assertEquals(2, result.size());
        assertEquals("1.1.1.1", result.get(0).getHostAddress());
        assertEquals("1.0.0.1", result.get(1).getHostAddress());
    }

    @Test
    public void explicitCustomAddressesOverrideWireGuardProfileDns() throws Exception {
        prefs.edit()
                .putString(DnsChangerConfig.PREF_DNS_MODE, DnsChangerConfig.MODE_CUSTOM)
                .putString("dns", "192.0.2.53")
                .putString("dns2", "192.0.2.54")
                .commit();

        List<InetAddress> result = ServiceSinkhole.getWireGuardDnsForSelection(
                context, prefs, parse("DNS = 10.64.0.1\n"));

        assertEquals(2, result.size());
        assertEquals("192.0.2.53", result.get(0).getHostAddress());
        assertEquals("192.0.2.54", result.get(1).getHostAddress());
    }

    @Test
    public void profileModeUsesQuad9FallbackWhenProfileHasNoUsableDns() throws Exception {
        prefs.edit().putString(
                DnsChangerConfig.PREF_DNS_MODE,
                DnsChangerConfig.MODE_WIREGUARD).commit();

        List<InetAddress> result = ServiceSinkhole.getWireGuardDnsForSelection(
                context, prefs, parse(""));

        assertTrue(result.size() >= 2);
        assertTrue(result.stream().anyMatch(dns -> "9.9.9.9".equals(dns.getHostAddress())));
        assertTrue(result.stream().anyMatch(dns -> "149.112.112.112".equals(dns.getHostAddress())));
    }

    @Test
    public void automaticModeKeepsUsingWireGuardProfileDns() throws Exception {
        prefs.edit().putString(
                DnsChangerConfig.PREF_DNS_MODE,
                DnsChangerConfig.MODE_AUTOMATIC).commit();

        List<InetAddress> result = ServiceSinkhole.getWireGuardDnsForSelection(
                context, prefs, parse("DNS = 10.64.0.1\n"));

        assertEquals(1, result.size());
        assertEquals("10.64.0.1", result.get(0).getHostAddress());
    }

    @Test
    public void automaticModeUsesProtectedFallbackWhenWireGuardProfileHasNoDns()
            throws Exception {
        prefs.edit().putString(
                DnsChangerConfig.PREF_DNS_MODE,
                DnsChangerConfig.MODE_AUTOMATIC).commit();

        List<InetAddress> result = ServiceSinkhole.getWireGuardDnsForSelection(
                context, prefs, parse(""));

        assertTrue(result.size() >= 2);
        assertTrue(result.stream().anyMatch(dns -> "9.9.9.9".equals(dns.getHostAddress())));
        assertTrue(result.stream().anyMatch(dns -> "149.112.112.112".equals(dns.getHostAddress())));
    }
}
