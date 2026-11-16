package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class BlockedDomainLogicTest {
    @Test
    public void normalizesCaseAndRootDot() {
        assertEquals("ads.example.com", BlockedDomainLogic.normalize("Ads.Example.COM."));
    }

    @Test
    public void rejectsIpLiteralsAndMalformedHostnames() {
        assertNull(BlockedDomainLogic.normalize("192.0.2.10"));
        assertNull(BlockedDomainLogic.normalize("2001:db8::1"));
        assertNull(BlockedDomainLogic.normalize("bad..example.com"));
        assertNull(BlockedDomainLogic.normalize("not a hostname"));
        assertNull(BlockedDomainLogic.normalize(null));
    }
}
