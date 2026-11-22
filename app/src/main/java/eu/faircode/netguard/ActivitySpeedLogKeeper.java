package eu.faircode.netguard;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.AppBarLayout;

import com.google.android.material.appbar.MaterialToolbar;

import net.kollnig.missioncontrol.R;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ActivitySpeedLogKeeper extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 5_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.AppThemeRed_NoActionBar);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_speed_log_keeper);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null)
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());
        findViewById(R.id.speed_log_copy_all).setOnClickListener(v -> copyFullLog());

        AppBarLayout appBar = findViewById(R.id.appbar);
        final int appBarInitialTop = appBar.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(appBar, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), appBarInitialTop + systemBars.top,
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        View scrollContainer = findViewById(R.id.speed_log_scroll);
        final int containerInitialBottom = scrollContainer.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(scrollContainer, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    containerInitialBottom + systemBars.bottom);
            return insets;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresh);
        render();
        handler.postDelayed(refresh, 5_000L);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void render() {
        ioExecutor.execute(() -> {
            SpeedLogKeeperStore store = SpeedLogKeeperStore.getInstance(getApplicationContext());
            List<SpeedLogKeeperStore.Sample> samples = store.getRecentSamples(60);
            List<SpeedLogKeeperStore.Event> events = store.getRecentEvents(12);
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed())
                    renderData(samples, events);
            });
        });
    }

    private void renderData(List<SpeedLogKeeperStore.Sample> samples,
                            List<SpeedLogKeeperStore.Event> events) {
        TextView status = findViewById(R.id.speed_status);
        TextView upload = findViewById(R.id.speed_upload);
        TextView download = findViewById(R.id.speed_download);
        TextView limits = findViewById(R.id.speed_limits);
        TextView queue = findViewById(R.id.speed_queue);
        TextView directQueue = findViewById(R.id.speed_log_direct_queue);
        TextView wireguardQueue = findViewById(R.id.speed_log_wireguard_queue);
        TextView pathDrops = findViewById(R.id.speed_log_path_drops);
        TextView pathErrors = findViewById(R.id.speed_log_path_errors);
        TextView delayed = findViewById(R.id.speed_delayed);
        TextView bypassed = findViewById(R.id.speed_bypassed);
        TextView drops = findViewById(R.id.speed_drops);
        TextView errors = findViewById(R.id.speed_errors);
        TextView history = findViewById(R.id.speed_history);
        TextView eventView = findViewById(R.id.speed_events);
        TextView updated = findViewById(R.id.speed_updated);

        if (samples.isEmpty()) {
            status.setText(R.string.speed_log_waiting);
            upload.setText(getString(R.string.speed_log_upload, "—"));
            download.setText(getString(R.string.speed_log_download, "—"));
            limits.setText(R.string.speed_log_limits_unknown);
            queue.setText(R.string.speed_log_queue_unknown);
            directQueue.setText(R.string.speed_log_path_queue_unknown);
            wireguardQueue.setText(R.string.speed_log_path_queue_unknown);
            pathDrops.setText(R.string.speed_log_path_drops_unknown);
            pathErrors.setText(R.string.speed_log_path_errors_unknown);
            delayed.setText(getString(R.string.speed_log_delayed, "—"));
            bypassed.setText(getString(R.string.speed_log_bypassed, "—"));
            drops.setText(getString(R.string.speed_log_drops, "—"));
            errors.setText(getString(R.string.speed_log_errors, "—"));
            updated.setText(R.string.speed_log_no_samples);
            history.setText(R.string.speed_log_no_history);
        } else {
            SpeedLogKeeperStore.Sample latest = samples.get(0);
            if (!latest.vpnActive)
                status.setText(R.string.speed_log_vpn_stopped);
            else if (System.currentTimeMillis() - latest.timestamp > 15_000L)
                status.setText(R.string.speed_log_stale);
            else
                status.setText(latest.shapingEnabled
                        ? R.string.speed_log_active : R.string.speed_log_vpn_unlimited);
            upload.setText(getString(R.string.speed_log_upload, formatRate(latest.uploadBytesPerSecond)));
            download.setText(getString(R.string.speed_log_download, formatRate(latest.downloadBytesPerSecond)));
            limits.setText(getString(R.string.speed_log_limits,
                    formatLimit(latest.uploadLimit), formatLimit(latest.downloadLimit)));
            queue.setText(getString(R.string.speed_log_queue,
                    latest.queuePackets, formatBytes(latest.queueBytes)));
            if (latest.directQueuePackets < 0 || latest.wireguardQueuePackets < 0) {
                directQueue.setText(R.string.speed_log_path_queue_unknown);
                wireguardQueue.setText(R.string.speed_log_path_queue_unknown);
            } else {
                directQueue.setText(getString(R.string.speed_log_direct_queue,
                        formatOptionalCount(latest.directQueuePackets), formatOptionalBytes(latest.directQueueBytes),
                        formatOptionalCount(latest.directQueuePeakPackets), formatOptionalBytes(latest.directQueuePeakBytes)));
                wireguardQueue.setText(getString(R.string.speed_log_wireguard_queue,
                        formatOptionalCount(latest.wireguardQueuePackets), formatOptionalBytes(latest.wireguardQueueBytes),
                        formatOptionalCount(latest.wireguardQueuePeakPackets), formatOptionalBytes(latest.wireguardQueuePeakBytes)));
            }
            if (latest.directDroppedPackets < 0 || latest.wireguardDroppedPackets < 0)
                pathDrops.setText(R.string.speed_log_path_drops_unknown);
            else
                pathDrops.setText(getString(R.string.speed_log_path_drops,
                        formatOptionalCount(latest.directDroppedPackets), formatOptionalCount(latest.wireguardDroppedPackets)));
            if (latest.directSchedulerErrors < 0 || latest.wireguardSchedulerErrors < 0)
                pathErrors.setText(R.string.speed_log_path_errors_unknown);
            else
                pathErrors.setText(getString(R.string.speed_log_path_errors,
                        formatOptionalCount(latest.directSchedulerErrors), formatOptionalCount(latest.wireguardSchedulerErrors)));
            delayed.setText(getString(R.string.speed_log_delayed, formatBytes(latest.delayedBytes)));
            bypassed.setText(getString(R.string.speed_log_bypassed, formatBytes(latest.bypassedBytes)));
            drops.setText(getString(R.string.speed_log_drops, Long.toString(latest.droppedPackets)));
            errors.setText(getString(R.string.speed_log_errors, Long.toString(latest.schedulerErrors)));
            updated.setText(getString(R.string.speed_log_updated,
                    new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(latest.timestamp))));
            history.setText(formatHistory(samples));
        }
        eventView.setText(formatEvents(events));
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refresh);
        ioExecutor.shutdownNow();
        super.onDestroy();
    }


    private void copyFullLog() {
        View copyButton = findViewById(R.id.speed_log_copy_all);
        copyButton.setEnabled(false);
        ioExecutor.execute(() -> {
            SpeedLogKeeperStore store = SpeedLogKeeperStore.getInstance(getApplicationContext());
            List<SpeedLogKeeperStore.Sample> samples = store.getRecentSamples(20_000);
            List<SpeedLogKeeperStore.Event> events = store.getRecentEvents(1_000);
            StringBuilder out = new StringBuilder(16_384);
            out.append("Vianshield Speed Log Keeper\n")
                    .append("Exported: ")
                    .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .format(new Date()))
                    .append("\nRetention: up to 24 hours\n")
                    .append("Samples: ").append(samples.size())
                    .append(" | Events: ").append(events.size()).append("\n\n")
                    .append("SAMPLES (newest first)\n")
                    .append("Timestamp | Upload B/s | Download B/s | Upload limit B/s | Download limit B/s")
                    .append(" | Delayed bytes | Bypassed bytes | Total queue packets | Total queue bytes")
                    .append(" | Direct packets | Direct bytes | Direct peak packets | Direct peak bytes")
                    .append(" | WireGuard packets | WireGuard bytes | WireGuard peak packets | WireGuard peak bytes")
                    .append(" | Direct drops | Direct scheduler errors | WireGuard drops | WireGuard scheduler errors")
                    .append(" | Total drops | Total scheduler errors | Shaping enabled | VPN active\n");
            for (SpeedLogKeeperStore.Sample sample : samples) {
                out.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                                .format(new Date(sample.timestamp))).append(" | ")
                        .append(sample.uploadBytesPerSecond).append(" | ")
                        .append(sample.downloadBytesPerSecond).append(" | ")
                        .append(sample.uploadLimit).append(" | ")
                        .append(sample.downloadLimit).append(" | ")
                        .append(sample.delayedBytes).append(" | ")
                        .append(sample.bypassedBytes).append(" | ")
                        .append(sample.queuePackets).append(" | ")
                        .append(sample.queueBytes).append(" | ")
                        .append(formatOptionalCount(sample.directQueuePackets)).append(" | ")
                        .append(formatOptionalBytes(sample.directQueueBytes)).append(" | ")
                        .append(formatOptionalCount(sample.directQueuePeakPackets)).append(" | ")
                        .append(formatOptionalBytes(sample.directQueuePeakBytes)).append(" | ")
                        .append(formatOptionalCount(sample.wireguardQueuePackets)).append(" | ")
                        .append(formatOptionalBytes(sample.wireguardQueueBytes)).append(" | ")
                        .append(formatOptionalCount(sample.wireguardQueuePeakPackets)).append(" | ")
                        .append(formatOptionalBytes(sample.wireguardQueuePeakBytes)).append(" | ")
                        .append(formatOptionalCount(sample.directDroppedPackets)).append(" | ")
                        .append(formatOptionalCount(sample.directSchedulerErrors)).append(" | ")
                        .append(formatOptionalCount(sample.wireguardDroppedPackets)).append(" | ")
                        .append(formatOptionalCount(sample.wireguardSchedulerErrors)).append(" | ")
                        .append(sample.droppedPackets).append(" | ")
                        .append(sample.schedulerErrors).append(" | ")
                        .append(sample.shapingEnabled).append(" | ")
                        .append(sample.vpnActive).append('\n');
            }
            out.append("\nEVENTS (newest first)\n");
            for (SpeedLogKeeperStore.Event event : events) {
                String label;
                switch (event.code) {
                    case SpeedLogKeeperStore.EVENT_VPN_STARTED: label = "VPN_STARTED"; break;
                    case SpeedLogKeeperStore.EVENT_VPN_STOPPED: label = "VPN_STOPPED"; break;
                    case SpeedLogKeeperStore.EVENT_WIREGUARD_STARTED: label = "WIREGUARD_STARTED"; break;
                    case SpeedLogKeeperStore.EVENT_WIREGUARD_STOPPED: label = "WIREGUARD_STOPPED"; break;
                    case SpeedLogKeeperStore.EVENT_WIREGUARD_ERROR: label = "WIREGUARD_ERROR"; break;
                    default: label = "UNKNOWN_" + event.code; break;
                }
                out.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                                .format(new Date(event.timestamp)))
                        .append(" | ").append(label).append('\n');
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed())
                    return;
                ClipboardManager clipboard =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("Vianshield speed log", out.toString()));
                copyButton.setEnabled(true);
                Toast.makeText(this, R.string.speed_log_copied, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private String formatOptionalCount(long value) {
        return value < 0 ? "n/a" : Long.toString(value);
    }

    private String formatOptionalBytes(long value) {
        return value < 0 ? "n/a" : formatBytes(value);
    }

    private String formatHistory(List<SpeedLogKeeperStore.Sample> samples) {
        StringBuilder out = new StringBuilder();
        for (SpeedLogKeeperStore.Sample sample : samples) {
            out.append(new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(new Date(sample.timestamp)))
                    .append("  ↑ ").append(formatRate(sample.uploadBytesPerSecond))
                    .append("  ↓ ").append(formatRate(sample.downloadBytesPerSecond))
                    .append("  Q ").append(sample.queuePackets)
                    .append("  Direct ").append(formatOptionalCount(sample.directQueuePackets))
                    .append("  WireGuard ").append(formatOptionalCount(sample.wireguardQueuePackets))
                    .append('\n');
        }
        return out.length() == 0 ? getString(R.string.speed_log_no_history) : out.toString().trim();
    }

    private String formatEvents(List<SpeedLogKeeperStore.Event> events) {
        if (events.isEmpty())
            return getString(R.string.speed_log_no_events);
        StringBuilder out = new StringBuilder();
        for (SpeedLogKeeperStore.Event event : events) {
            String label;
            switch (event.code) {
                case SpeedLogKeeperStore.EVENT_VPN_STARTED: label = getString(R.string.speed_event_vpn_started); break;
                case SpeedLogKeeperStore.EVENT_VPN_STOPPED: label = getString(R.string.speed_event_vpn_stopped); break;
                case SpeedLogKeeperStore.EVENT_WIREGUARD_STARTED: label = getString(R.string.speed_event_wg_started); break;
                case SpeedLogKeeperStore.EVENT_WIREGUARD_STOPPED: label = getString(R.string.speed_event_wg_stopped); break;
                case SpeedLogKeeperStore.EVENT_WIREGUARD_ERROR: label = getString(R.string.speed_event_wg_error); break;
                default: continue;
            }
            out.append(new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(new Date(event.timestamp))).append("  ").append(label).append('\n');
        }
        return out.length() == 0 ? getString(R.string.speed_log_no_events) : out.toString().trim();
    }

    private String formatRate(double bytesPerSecond) {
        return new DecimalFormat("0.0").format(bytesPerSecond / 1000.0) + " KB/s";
    }

    private String formatLimit(long bytesPerSecond) {
        return bytesPerSecond <= 0 ? getString(R.string.speed_log_unlimited)
                : new DecimalFormat("0.#").format(bytesPerSecond / 1000.0) + " KB/s";
    }

    private String formatBytes(long bytes) {
        if (bytes < 1000) return bytes + " B";
        if (bytes < 1_000_000) return new DecimalFormat("0.0").format(bytes / 1000.0) + " KB";
        return new DecimalFormat("0.00").format(bytes / 1_000_000.0) + " MB";
    }
}
