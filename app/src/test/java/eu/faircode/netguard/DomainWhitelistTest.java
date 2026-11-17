package eu.faircode.netguard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class DomainWhitelistTest {
    private Context context;
    private DomainWhitelist whitelist;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(DomainWhitelist.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
        DomainWhitelist.resetForTests();
        whitelist = DomainWhitelist.getInstance(context);
    }

    @Test
    public void addNormalizesAndMatchesSubdomainsAtLabelBoundaries() {
        assertTrue(whitelist.addDomain("Example.COM."));
        assertTrue(whitelist.isWhitelisted("ads.example.com"));
        assertTrue(whitelist.isWhitelisted("deep.ads.example.com."));
        assertTrue(whitelist.isWhitelisted("EXAMPLE.com"));
        assertFalse(whitelist.isWhitelisted("notexample.com"));
        assertFalse(whitelist.isWhitelisted("example.com.attacker.test"));
    }

    @Test
    public void invalidAndDuplicateEntriesAreRejected() {
        assertFalse(whitelist.addDomain("https://example.com"));
        assertFalse(whitelist.addDomain("bad..example.com"));
        assertTrue(whitelist.addDomain("example.com"));
        assertFalse(whitelist.addDomain("EXAMPLE.COM."));
    }

    @Test
    public void removalAndPersistenceWork() {
        assertTrue(whitelist.addDomain("example.com"));
        DomainWhitelist.resetForTests();
        whitelist = DomainWhitelist.getInstance(context);
        assertTrue(whitelist.isWhitelisted("ads.example.com"));

        assertTrue(whitelist.removeDomain("EXAMPLE.COM."));
        assertFalse(whitelist.isWhitelisted("ads.example.com"));
        assertFalse(whitelist.removeDomain("example.com"));
    }

    @Test
    public void trackerBypassRequiresBothBlockedVerdictAndConfidentHostname() {
        assertTrue(whitelist.addDomain("example.com"));
        assertTrue(whitelist.shouldBypassTrackerFilter(
                true, true, "ads.example.com"));
        assertFalse(whitelist.shouldBypassTrackerFilter(
                true, false, "ads.example.com"));
        assertFalse(whitelist.shouldBypassTrackerFilter(
                false, true, "ads.example.com"));
        assertFalse(whitelist.shouldBypassTrackerFilter(
                true, true, "ads.other.test"));
    }

    @Test
    public void matchingRejectsInvalidHostnameInput() {
        assertFalse(DomainWhitelist.matches(
                Collections.singleton("example.com"), "192.0.2.10"));
        assertFalse(DomainWhitelist.matches(
                Collections.singleton("example.com"), "bad..example.com"));
    }
}
