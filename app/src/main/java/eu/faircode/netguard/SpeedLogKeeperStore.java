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
    private static final int DB_VERSION = 2;
    private static final String TABLE_SAMPLES = "speed_samples";
    private static final String TABLE_EVENTS = "speed_events";
    private static final long RETENTION_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_SAMPLES = 20_000;
    private static final int MAX_EVENTS = 1_000;
    private static final long MAX_DATABASE_BYTES = 8L * 1024L * 1024L;

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
    private static long previousDirectDroppedPackets = -1;
    private static long previousDirectSchedulerErrors = -1;
    private static long previousWireGuardDroppedPackets = -1;
    private static long previousWireGuardSchedulerErrors = -1;

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
        public final long directQueuePackets;
        public final long directQueueBytes;
        public final long directQueuePeakPackets;
        public final long directQueuePeakBytes;
        public final long wireguardQueuePackets;
        public final long wireguardQueueBytes;
        public final long wireguardQueuePeakPackets;
        public final long wireguardQueuePeakBytes;
        public final long directDroppedPackets;
        public final long directSchedulerErrors;
        public final long wireguardDroppedPackets;
        public final long wireguardSchedulerErrors;
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
            directQueuePackets = cursor.getLong(11);
            directQueueBytes = cursor.getLong(12);
            directQueuePeakPackets = cursor.getLong(13);
            directQueuePeakBytes = cursor.getLong(14);
            wireguardQueuePackets = cursor.getLong(15);
            wireguardQueueBytes = cursor.getLong(16);
            wireguardQueuePeakPackets = cursor.getLong(17);
            wireguardQueuePeakBytes = cursor.getLong(18);
            directDroppedPackets = cursor.getLong(19);
            directSchedulerErrors = cursor.getLong(20);
            wireguardDroppedPackets = cursor.getLong(21);
            wireguardSchedulerErrors = cursor.getLong(22);
            shapingEnabled = cursor.getInt(23) != 0;
            vpnActive = cursor.getInt(24) != 0;
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
                "direct_queue_packets INTEGER NOT NULL DEFAULT -1," +
                "direct_queue_bytes INTEGER NOT NULL DEFAULT -1," +
                "direct_queue_peak_packets INTEGER NOT NULL DEFAULT -1," +
                "direct_queue_peak_bytes INTEGER NOT NULL DEFAULT -1," +
                "wireguard_queue_packets INTEGER NOT NULL DEFAULT -1," +
                "wireguard_queue_bytes INTEGER NOT NULL DEFAULT -1," +
                "wireguard_queue_peak_packets INTEGER NOT NULL DEFAULT -1," +
                "wireguard_queue_peak_bytes INTEGER NOT NULL DEFAULT -1," +
                "direct_dropped_packets INTEGER NOT NULL DEFAULT -1," +
                "direct_scheduler_errors INTEGER NOT NULL DEFAULT -1," +
                "wireguard_dropped_packets INTEGER NOT NULL DEFAULT -1," +
                "wireguard_scheduler_errors INTEGER NOT NULL DEFAULT -1," +
                "shaping_enabled INTEGER NOT NULL," +
                "vpn_active INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX speed_samples_time ON " + TABLE_SAMPLES + "(timestamp_ms)");
        db.execSQL("CREATE TABLE " + TABLE_EVENTS + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "timestamp_ms INTEGER NOT NULL," +
                "event_code INTEGER NOT NULL)");
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        db.setMaximumSize(MAX_DATABASE_BYTES);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            String[] columns = {
                    "direct_queue_packets", "direct_queue_bytes",
                    "direct_queue_peak_packets", "direct_queue_peak_bytes",
                    "wireguard_queue_packets", "wireguard_queue_bytes",
                    "wireguard_queue_peak_packets", "wireguard_queue_peak_bytes",
                    "direct_dropped_packets", "direct_scheduler_errors",
                    "wireguard_dropped_packets", "wireguard_scheduler_errors"
            };
            for (String column : columns)
                db.execSQL("ALTER TABLE " + TABLE_SAMPLES + " ADD COLUMN " +
                        column + " INTEGER NOT NULL DEFAULT 0");
        }
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
        long directDroppedDelta = delta(v[13], previousDirectDroppedPackets);
        long directSchedulerDelta = delta(v[14], previousDirectSchedulerErrors);
        long wireguardDroppedDelta = delta(v[10], previousWireGuardDroppedPackets);
        long wireguardSchedulerDelta = delta(v[6], previousWireGuardSchedulerErrors);
        long droppedDelta = directDroppedDelta + wireguardDroppedDelta;
        long schedulerDelta = directSchedulerDelta + wireguardSchedulerDelta;
        long directPeakPackets = v.length > 15 ? v[15] : -1;
        long directPeakBytes = v.length > 16 ? v[16] : -1;
        long wireguardPeakPackets = v.length > 17 ? v[17] : -1;
        long wireguardPeakBytes = v.length > 18 ? v[18] : -1;

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
        row.put("direct_queue_packets", v[11]);
        row.put("direct_queue_bytes", v[12]);
        row.put("direct_queue_peak_packets", directPeakPackets);
        row.put("direct_queue_peak_bytes", directPeakBytes);
        row.put("wireguard_queue_packets", v[4]);
        row.put("wireguard_queue_bytes", v[5]);
        row.put("wireguard_queue_peak_packets", wireguardPeakPackets);
        row.put("wireguard_queue_peak_bytes", wireguardPeakBytes);
        row.put("direct_dropped_packets", directDroppedDelta);
        row.put("direct_scheduler_errors", directSchedulerDelta);
        row.put("wireguard_dropped_packets", wireguardDroppedDelta);
        row.put("wireguard_scheduler_errors", wireguardSchedulerDelta);
        row.put("shaping_enabled", v[9] != 0 ? 1 : 0);
        row.put("vpn_active", vpnActive ? 1 : 0);

        SpeedLogKeeperStore store = getInstance(context);
        SQLiteDatabase db;
        try {
            db = store.getWritableDatabase();
            if (db.insert(TABLE_SAMPLES, null, row) < 0)
                return;
        } catch (RuntimeException ignored) {
            return;
        }

        previousSampleAt = now;
        previousUploadBytes = v[0];
        previousDownloadBytes = v[1];
        previousDelayedBytes = v[2];
        previousBypassedBytes = v[3];
        previousDirectDroppedPackets = v[13];
        previousDirectSchedulerErrors = v[14];
        previousWireGuardDroppedPackets = v[10];
        previousWireGuardSchedulerErrors = v[6];

        try {
            db.delete(TABLE_SAMPLES, "timestamp_ms < ?", new String[]{Long.toString(now - RETENTION_MS)});
            db.execSQL("DELETE FROM " + TABLE_SAMPLES + " WHERE id NOT IN " +
                    "(SELECT id FROM " + TABLE_SAMPLES + " ORDER BY id DESC LIMIT " + MAX_SAMPLES + ")");
        } catch (RuntimeException ignored) {
            // The just-written sample remains usable; pruning retries next time.
        }
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
                        "direct_queue_packets, direct_queue_bytes, " +
                        "direct_queue_peak_packets, direct_queue_peak_bytes, " +
                        "wireguard_queue_packets, wireguard_queue_bytes, " +
                        "wireguard_queue_peak_packets, wireguard_queue_peak_bytes, " +
                        "direct_dropped_packets, direct_scheduler_errors, " +
                        "wireguard_dropped_packets, wireguard_scheduler_errors, " +
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
