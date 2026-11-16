package net.kollnig.missioncontrol;

import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.MenuItem;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;

import net.kollnig.missioncontrol.ui.compose.BlockedDomainRow;
import net.kollnig.missioncontrol.ui.compose.BlockedDomainsScreen;
import net.kollnig.missioncontrol.ui.compose.BlockedDomainsScreenController;
import net.kollnig.missioncontrol.ui.compose.BlockedDomainsScreenModel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import eu.faircode.netguard.DatabaseHelper;
import eu.faircode.netguard.Util;

public class ActivityBlockedDomains extends AppCompatActivity {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger requestId = new AtomicInteger();
    private ExecutorService executor;
    private DatabaseHelper database;
    private BlockedDomainsScreenController screenController;
    private boolean destroyed;

    private final DatabaseHelper.BlockedDomainChangedListener blockedDomainListener =
            () -> mainHandler.post(this::refresh);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Util.setTheme(this);
        super.onCreate(savedInstanceState);

        ComposeView composeView = new ComposeView(this);
        setContentView(composeView);
        getSupportActionBar().setTitle(R.string.dashboard_blocked_domains);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        database = DatabaseHelper.getInstance(this);
        executor = Executors.newSingleThreadExecutor();
        screenController = BlockedDomainsScreen.install(
                composeView, new BlockedDomainsScreenModel(new ArrayList<>()));
    }

    @Override
    protected void onStart() {
        super.onStart();
        database.addBlockedDomainChangedListener(blockedDomainListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onStop() {
        database.removeBlockedDomainChangedListener(blockedDomainListener);
        super.onStop();
    }

    private void refresh() {
        if (destroyed || executor == null || screenController == null)
            return;
        final int request = requestId.incrementAndGet();
        executor.execute(() -> {
            List<BlockedDomainRow> rows = new ArrayList<>();
            try (Cursor cursor = database.getRecentBlockedDomains(500)) {
                while (cursor.moveToNext()) {
                    String hostname = cursor.getString(cursor.getColumnIndexOrThrow("hostname"));
                    long time = cursor.getLong(cursor.getColumnIndexOrThrow("last_blocked"));
                    CharSequence relative = DateUtils.getRelativeTimeSpanString(
                            time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
                    rows.add(new BlockedDomainRow(hostname,
                            getString(R.string.blocked_domain_last_blocked, relative)));
                }
            } catch (Exception ex) {
                android.util.Log.e("BlockedDomains", "Loading blocked-domain history failed", ex);
            }
            mainHandler.post(() -> {
                if (!destroyed && request == requestId.get() && screenController != null)
                    screenController.update(new BlockedDomainsScreenModel(rows));
            });
        });
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        requestId.incrementAndGet();
        if (executor != null)
            executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
