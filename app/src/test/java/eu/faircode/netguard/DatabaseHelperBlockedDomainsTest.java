package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = [35])
public class DatabaseHelperBlockedDomainsTest {
    @Test
    public void repeatedDomainHitsProduceOneUpdatedRow() {
        Context context = ApplicationProvider.getApplicationContext();
        DatabaseHelper database = DatabaseHelper.getInstance(context);
        database.recordBlockedDomain("Ads.Example.COM.");
        database.recordBlockedDomain("ads.example.com");

        try (Cursor cursor = database.getRecentBlockedDomains(10)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(1, cursor.getCount());
            assertEquals("ads.example.com", cursor.getString(cursor.getColumnIndexOrThrow("hostname")));
            assertTrue(cursor.getLong(cursor.getColumnIndexOrThrow("last_blocked")) > 0);
        }
    }
}
