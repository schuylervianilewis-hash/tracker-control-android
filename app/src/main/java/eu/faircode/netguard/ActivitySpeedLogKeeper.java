package eu.faircode.netguard;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import net.kollnig.missioncontrol.R;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ActivitySpeedLogKeeper extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
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
        SpeedLogKeeperStore store = SpeedLogKeeperStore.getInstance(this);
        List<SpeedLogKeeperStore.Sample> samples = store.getRecentSamples(30);
        List<SpeedLogKeeperStore.Event> events = store.getRecentEvents(12);

        TextView status = findViewById(R.id.speed_status);
        TextView upload = findViewById(R.id.speed_upload);
        TextView download = findViewById(R.id.speed_download);
        TextView limits = findViewById(R.id.speed_limits);
        TextView queue = findViewById(R.id.speed_queue);
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
            limits.setText(R.string.speed_log_no_limits);
            queue.setText(R.string.speed_log_queue_empty);
            delayed.setText(getString(R.string.speed_log_delayed, "0 B"));
            bypassed.setText(getString(R.string.speed_log_bypassed, "0 B"));
            drops.setText(getString(R.string.speed_log_drops, "0"));
            errors.setText(getString(R.string.speed_log_errors, "0"));
            updated.setText(R.string.speed_log_no_samples);
            history.setText(R.string.speed_log_no_history);
        } else {
            SpeedLogKeeperStore.Sample latest = samples.get(0);
            status.setText(latest.vpnActive
                    ? (latest.shapingEnabled ? R.string.speed_log_active : R.string.speed_log_unlimited)
                    : R.string.speed_log_vpn_stopped);
            upload.setText(getString(R.string.speed_log_upload, formatRate(latest.uploadBytesPerSecond)));
            download.setText(getString(R.string.speed_log_download, formatRate(latest.downloadBytesPerSecond)));
            limits.setText(getString(R.string.speed_log_limits,
                    formatLimit(latest.uploadLimit), formatLimit(latest.downloadLimit)));
            queue.setText(getString(R.string.speed_log_queue,
                    latest.queuePackets, formatBytes(latest.queueBytes)));
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

    private String formatHistory(List<SpeedLogKeeperStore.Sample> samples) {
        StringBuilder out = new StringBuilder();
        for (SpeedLogKeeperStore.Sample sample : samples) {
            out.append(new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(new Date(sample.timestamp)))
                    .append("  ↑ ").append(formatRate(sample.uploadBytesPerSecond))
                    .append("  ↓ ").append(formatRate(sample.downloadBytesPerSecond))
                    .append("  Q ").append(sample.queuePackets)
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
