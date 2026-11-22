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
        context.deleteDatabase("speed-log-keeper.db");

        // Seed the previous schema/version to verify an in-place migration
        // preserves aggregate history and marks new path fields unavailable.
        SQLiteDatabase legacy = context.openOrCreateDatabase(
                "speed-log-keeper.db", Context.MODE_PRIVATE, null);
        legacy.execSQL("CREATE TABLE speed_samples (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "timestamp_ms INTEGER NOT NULL," +
                "upload_bytes_per_second REAL NOT NULL," +
                "download_bytes_per_second REAL NOT NULL," +
                "upload_limit INTEGER NOT NULL," +
                "download_limit INTEGER NOT NULL," +
                "delayed_bytes INTEGER NOT NULL," +
                "bypassed_bytes INTEGER NOT NULL," +
                "queue_packets INTEGER NOT NULL," +
                "queue_bytes INTEGER NOT NULL," +
                "dropped_packets INTEGER NOT NULL," +
                "scheduler_errors INTEGER NOT NULL," +
                "shaping_enabled INTEGER NOT NULL," +
                "vpn_active INTEGER NOT NULL)");
        legacy.execSQL("CREATE TABLE speed_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "timestamp_ms INTEGER NOT NULL," +
                "event_code INTEGER NOT NULL)");
        legacy.execSQL("INSERT INTO speed_samples (timestamp_ms, upload_bytes_per_second, " +
                "download_bytes_per_second, upload_limit, download_limit, delayed_bytes, " +
                "bypassed_bytes, queue_packets, queue_bytes, dropped_packets, scheduler_errors, " +
                "shaping_enabled, vpn_active) VALUES (1000, 10, 20, 1000, 2000, 0, 0, 3, 300, 0, 0, 1, 1)");
        legacy.setVersion(1);
        legacy.close();

        SpeedLogKeeperStore store = SpeedLogKeeperStore.getInstance(context);
        SQLiteDatabase db = store.getWritableDatabase();

        assertEquals(new HashSet<>(Arrays.asList(
                "id", "timestamp_ms", "upload_bytes_per_second", "download_bytes_per_second",
                "upload_limit", "download_limit", "delayed_bytes", "bypassed_bytes",
                "queue_packets", "queue_bytes", "dropped_packets", "scheduler_errors",
                "direct_queue_packets", "direct_queue_bytes",
                "direct_queue_peak_packets", "direct_queue_peak_bytes",
                "wireguard_queue_packets", "wireguard_queue_bytes",
                "wireguard_queue_peak_packets", "wireguard_queue_peak_bytes",
                "direct_dropped_packets", "direct_scheduler_errors",
                "wireguard_dropped_packets", "wireguard_scheduler_errors",
                "shaping_enabled", "vpn_active"
        )), columns(db, "speed_samples"));

        assertEquals(new HashSet<>(Arrays.asList(
                "id", "timestamp_ms", "event_code"
        )), columns(db, "speed_events"));

        assertTrue("Speed telemetry database must stay below its 8 MiB cap",
                db.getMaximumSize() <= 8L * 1024L * 1024L);

        try (Cursor cursor = db.rawQuery("SELECT queue_packets, queue_bytes, " +
                "direct_queue_packets, wireguard_queue_packets " +
                "FROM speed_samples ORDER BY id ASC LIMIT 1", null)) {
            assertTrue("legacy aggregate sample survives migration", cursor.moveToFirst());
            assertEquals(3L, cursor.getLong(0));
            assertEquals(300L, cursor.getLong(1));
            assertEquals(-1L, cursor.getLong(2));
            assertEquals(-1L, cursor.getLong(3));
        }

        long[] snapshot = new long[19];
        snapshot[4] = 2; snapshot[5] = 200;
        snapshot[6] = 4; snapshot[10] = 3;
        snapshot[11] = 5; snapshot[12] = 500;
        snapshot[13] = 6; snapshot[14] = 7;
        snapshot[15] = 9; snapshot[16] = 900;
        snapshot[17] = 4; snapshot[18] = 400;
        SpeedLogKeeperStore.recordSnapshot(context, snapshot, true);
        try (Cursor cursor = db.rawQuery("SELECT queue_packets, queue_bytes, " +
                "direct_queue_packets, direct_queue_bytes, direct_queue_peak_packets, " +
                "direct_queue_peak_bytes, wireguard_queue_packets, wireguard_queue_bytes, " +
                "wireguard_queue_peak_packets, wireguard_queue_peak_bytes " +
                "FROM speed_samples ORDER BY id DESC LIMIT 1", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(7L, cursor.getLong(0));
            assertEquals(700L, cursor.getLong(1));
            assertEquals(5L, cursor.getLong(2));
            assertEquals(500L, cursor.getLong(3));
            assertEquals(9L, cursor.getLong(4));
            assertEquals(900L, cursor.getLong(5));
            assertEquals(2L, cursor.getLong(6));
            assertEquals(200L, cursor.getLong(7));
            assertEquals(4L, cursor.getLong(8));
            assertEquals(400L, cursor.getLong(9));
        }
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
