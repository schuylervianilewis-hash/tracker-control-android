package eu.faircode.netguard;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Private, bounded storage for aggregate speed-shaping telemetry only.
 *
 * The schema intentionally has no columns for UID, app/package, protocol,
 * address, hostname, DNS, port, connection, or flow identity.
 */
public final class SpeedLogKeeperStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "speed-log-keeper.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE_SAMPLES = "speed_samples";
    private static final String TABLE_EVENTS = "speed_events";
    private static final long RETENTION_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_SAMPLES = 20_000;
    private static final int MAX_EVENTS = 1_000;

    public static final int EVENT_VPN_STARTED = 1;
    public static final int EVENT_VPN_STOPPED = 2;
    public static final int EVENT_WIREGUARD_STARTED = 3;
    public static final int EVENT_WIREGUARD_STOPPED = 4;
    public static final int EVENT_WIREGUARD_ERROR = 5;

    private static volatile SpeedLogKeeperStore instance;
    private static long previousSampleAt;
    private static long previousUploadBytes = -1;
    private static long previousDownloadBytes = -1;
    private static long previousDelayedBytes = -1;
    private static long previousBypassedBytes = -1;
    private static long previousDroppedPackets = -1;
    private static long previousSchedulerErrors = -1;

    public static final class Sample {
        public final long timestamp;
        public final double uploadBytesPerSecond;
        public final double downloadBytesPerSecond;
        public final long uploadLimit;
        public final long downloadLimit;
        public final long delayedBytes;
        public final long bypassedBytes;
        public final long queuePackets;
        public final long queueBytes;
        public final long droppedPackets;
        public final long schedulerErrors;
        public final boolean shapingEnabled;
        public final boolean vpnActive;

        Sample(Cursor cursor) {
            timestamp = cursor.getLong(0);
            uploadBytesPerSecond = cursor.getDouble(1);
            downloadBytesPerSecond = cursor.getDouble(2);
            uploadLimit = cursor.getLong(3);
            downloadLimit = cursor.getLong(4);
            delayedBytes = cursor.getLong(5);
            bypassedBytes = cursor.getLong(6);
            queuePackets = cursor.getLong(7);
            queueBytes = cursor.getLong(8);
            droppedPackets = cursor.getLong(9);
            schedulerErrors = cursor.getLong(10);
            shapingEnabled = cursor.getInt(11) != 0;
            vpnActive = cursor.getInt(12) != 0;
        }
    }

    public static final class Event {
        public final long timestamp;
        public final int code;

        Event(Cursor cursor) {
            timestamp = cursor.getLong(0);
            code = cursor.getInt(1);
        }
    }

    public static SpeedLogKeeperStore getInstance(Context context) {
        SpeedLogKeeperStore result = instance;
        if (result == null) {
            synchronized (SpeedLogKeeperStore.class) {
                result = instance;
                if (result == null) {
                    result = new SpeedLogKeeperStore(context.getApplicationContext());
                    instance = result;
                }
            }
        }
        return result;
    }

    private SpeedLogKeeperStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_SAMPLES + " (" +
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
        db.execSQL("CREATE INDEX speed_samples_time ON " + TABLE_SAMPLES + "(timestamp_ms)");
        db.execSQL("CREATE TABLE " + TABLE_EVENTS + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "timestamp_ms INTEGER NOT NULL," +
                "event_code INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // No historical schema exists yet. Future migrations must preserve the
        // aggregate-only contract and must not add traffic identity fields.
    }

    public static synchronized void recordSnapshot(Context context, long[] v, boolean vpnActive) {
        if (v == null || v.length < 15)
            return;

        long now = System.currentTimeMillis();
        long elapsed = previousSampleAt > 0 ? now - previousSampleAt : 0;
        long upRate = rate(v[0], previousUploadBytes, elapsed);
        long downRate = rate(v[1], previousDownloadBytes, elapsed);
        long delayedDelta = delta(v[2], previousDelayedBytes);
        long bypassedDelta = delta(v[3], previousBypassedBytes);
        long droppedDelta = delta(v[10] + v[13], previousDroppedPackets);
        long schedulerDelta = delta(v[6] + v[14], previousSchedulerErrors);

        ContentValues row = new ContentValues();
        row.put("timestamp_ms", now);
        row.put("upload_bytes_per_second", upRate);
        row.put("download_bytes_per_second", downRate);
        row.put("upload_limit", v[7]);
        row.put("download_limit", v[8]);
        row.put("delayed_bytes", delayedDelta);
        row.put("bypassed_bytes", bypassedDelta);
        row.put("queue_packets", v[4] + v[11]);
        row.put("queue_bytes", v[5] + v[12]);
        row.put("dropped_packets", droppedDelta);
        row.put("scheduler_errors", schedulerDelta);
        row.put("shaping_enabled", v[9] != 0 ? 1 : 0);
        row.put("vpn_active", vpnActive ? 1 : 0);

        SpeedLogKeeperStore store = getInstance(context);
        try {
            SQLiteDatabase db = store.getWritableDatabase();
            if (db.insert(TABLE_SAMPLES, null, row) < 0)
                return;
            db.delete(TABLE_SAMPLES, "timestamp_ms < ?", new String[]{Long.toString(now - RETENTION_MS)});
            db.execSQL("DELETE FROM " + TABLE_SAMPLES + " WHERE id NOT IN " +
                    "(SELECT id FROM " + TABLE_SAMPLES + " ORDER BY id DESC LIMIT " + MAX_SAMPLES + ")");
        } catch (RuntimeException ignored) {
            return;
        }

        previousSampleAt = now;
        previousUploadBytes = v[0];
        previousDownloadBytes = v[1];
        previousDelayedBytes = v[2];
        previousBypassedBytes = v[3];
        previousDroppedPackets = v[10] + v[13];
        previousSchedulerErrors = v[6] + v[14];
    }

    private static long rate(long current, long previous, long elapsedMs) {
        if (previous < 0 || elapsedMs <= 0)
            return 0;
        long difference = current >= previous ? current - previous : current;
        return Math.max(0, Math.round(difference * 1000.0 / elapsedMs));
    }

    private static long delta(long current, long previous) {
        if (previous < 0)
            return 0;
        return current >= previous ? current - previous : current;
    }

    public static void recordEvent(Context context, int eventCode) {
        if (eventCode < EVENT_VPN_STARTED || eventCode > EVENT_WIREGUARD_ERROR)
            return;
        try {
            SpeedLogKeeperStore store = getInstance(context);
            SQLiteDatabase db = store.getWritableDatabase();
            long now = System.currentTimeMillis();
            try (Cursor cursor = db.rawQuery("SELECT event_code, timestamp_ms FROM " +
                    TABLE_EVENTS + " ORDER BY id DESC LIMIT 1", null)) {
                if (cursor.moveToFirst() && cursor.getInt(0) == eventCode &&
                        now - cursor.getLong(1) < 5_000L)
                    return;
            }
            ContentValues row = new ContentValues();
            row.put("timestamp_ms", now);
            row.put("event_code", eventCode);
            if (db.insert(TABLE_EVENTS, null, row) >= 0)
                db.execSQL("DELETE FROM " + TABLE_EVENTS + " WHERE id NOT IN " +
                        "(SELECT id FROM " + TABLE_EVENTS + " ORDER BY id DESC LIMIT " + MAX_EVENTS + ")");
        } catch (RuntimeException ignored) {
            // Logging failure must not change VPN or WireGuard lifecycle behavior.
        }
    }

    public List<Sample> getRecentSamples(int limit) {
        List<Sample> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT timestamp_ms, upload_bytes_per_second, download_bytes_per_second, " +
                        "upload_limit, download_limit, delayed_bytes, bypassed_bytes, " +
                        "queue_packets, queue_bytes, dropped_packets, scheduler_errors, " +
                        "shaping_enabled, vpn_active FROM " + TABLE_SAMPLES +
                        " ORDER BY id DESC LIMIT ?", new String[]{Integer.toString(Math.max(1, limit))})) {
            while (cursor.moveToNext())
                result.add(new Sample(cursor));
        } catch (RuntimeException ignored) {
            result.clear();
        }
        return result;
    }

    public List<Event> getRecentEvents(int limit) {
        List<Event> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT timestamp_ms, event_code FROM " + TABLE_EVENTS +
                        " ORDER BY id DESC LIMIT ?", new String[]{Integer.toString(Math.max(1, limit))})) {
            while (cursor.moveToNext())
                result.add(new Event(cursor));
        } catch (RuntimeException ignored) {
            result.clear();
        }
        return result;
    }
}
