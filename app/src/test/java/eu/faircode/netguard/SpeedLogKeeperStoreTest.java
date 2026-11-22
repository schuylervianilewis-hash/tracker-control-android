package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SpeedLogKeeperStoreTest {
    @Test
    public void databaseSchemaIsAggregateOnlyAndSizeBounded() {
        Context context = RuntimeEnvironment.getApplication();
        SpeedLogKeeperStore store = SpeedLogKeeperStore.getInstance(context);
        SQLiteDatabase db = store.getWritableDatabase();

        assertEquals(new HashSet<>(Arrays.asList(
                "id", "timestamp_ms", "upload_bytes_per_second", "download_bytes_per_second",
                "upload_limit", "download_limit", "delayed_bytes", "bypassed_bytes",
                "queue_packets", "queue_bytes", "dropped_packets", "scheduler_errors",
                "shaping_enabled", "vpn_active"
        )), columns(db, "speed_samples"));

        assertEquals(new HashSet<>(Arrays.asList(
                "id", "timestamp_ms", "event_code"
        )), columns(db, "speed_events"));

        assertTrue("Speed telemetry database must stay below its 8 MiB cap",
                db.getMaximumSize() <= 8L * 1024L * 1024L);
    }

    private Set<String> columns(SQLiteDatabase db, String table) {
        Set<String> names = new HashSet<>();
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (cursor.moveToNext())
                names.add(cursor.getString(1));
        }
        return names;
    }
}
