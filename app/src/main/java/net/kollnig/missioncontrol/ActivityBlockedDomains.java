package net.kollnig.missioncontrol;

import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.kollnig.missioncontrol.ui.compose.BlockedDomainRow;
import net.kollnig.missioncontrol.ui.compose.BlockedDomainsScreen;
import net.kollnig.missioncontrol.ui.compose.BlockedDomainsScreenCallbacks;
import net.kollnig.missioncontrol.ui.compose.BlockedDomainsScreenController;
import net.kollnig.missioncontrol.ui.compose.BlockedDomainsScreenModel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import eu.faircode.netguard.BlockedDomainLogic;
import eu.faircode.netguard.DatabaseHelper;
import eu.faircode.netguard.DomainWhitelist;
import eu.faircode.netguard.Util;

public class ActivityBlockedDomains extends AppCompatActivity {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger requestId = new AtomicInteger();
    private ExecutorService executor;
    private DatabaseHelper database;
    private BlockedDomainsScreenController screenController;
    private boolean showingWhitelist;
    private boolean destroyed;

    private final DatabaseHelper.BlockedDomainChangedListener blockedDomainListener =
            () -> mainHandler.post(this::refresh);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Util.setTheme(this);
        super.onCreate(savedInstanceState);

        ComposeView composeView = new ComposeView(this);
        setContentView(composeView);
        updateActionBarTitle();
        if (getSupportActionBar() != null)
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        database = DatabaseHelper.getInstance(this);
        executor = Executors.newSingleThreadExecutor();
        BlockedDomainsScreenCallbacks callbacks = new BlockedDomainsScreenCallbacks() {
            @Override
            public void onBlockedDomainClick(String hostname) {
                showWhitelistConfirmation(hostname);
            }

            @Override
            public void onWhitelistedDomainClick(String hostname) {
                showRemoveConfirmation(hostname);
            }
        };
        screenController = BlockedDomainsScreen.install(
                composeView, new BlockedDomainsScreenModel(new ArrayList<>(), new ArrayList<>(), false), callbacks);
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
        final boolean showWhitelist = showingWhitelist;
        final DomainWhitelist whitelist = DomainWhitelist.getInstance(getApplicationContext());
        executor.execute(() -> {
            List<BlockedDomainRow> rows = new ArrayList<>();
            List<String> whitelistRows = new ArrayList<>();
            if (showWhitelist) {
                whitelistRows.addAll(whitelist.getDomains());
            } else {
                try (Cursor cursor = database.getRecentBlockedDomains(500)) {
                    while (cursor.moveToNext()) {
                        String hostname = cursor.getString(
                                cursor.getColumnIndexOrThrow("hostname"));
                        long time = cursor.getLong(
                                cursor.getColumnIndexOrThrow("last_blocked"));
                        CharSequence relative = DateUtils.getRelativeTimeSpanString(
                                time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
                        rows.add(new BlockedDomainRow(
                                hostname, relative.toString(), whitelist.isWhitelisted(hostname)));
                    }
                } catch (Exception ex) {
                    android.util.Log.e(
                            "BlockedDomains", "Loading blocked-domain history failed", ex);
                }
            }
            mainHandler.post(() -> {
                if (!destroyed && request == requestId.get() && screenController != null)
                    screenController.update(new BlockedDomainsScreenModel(
                            rows, whitelistRows, showWhitelist));
            });
        });
    }

    private void updateActionBarTitle() {
        if (getSupportActionBar() != null)
            getSupportActionBar().setTitle(showingWhitelist
                    ? R.string.whitelisted_domains_title
                    : R.string.dashboard_blocked_domains);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_blocked_domains, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem blocked = menu.findItem(R.id.menu_blocked_domains_blocked);
        MenuItem whitelisted = menu.findItem(R.id.menu_blocked_domains_whitelisted);
        if (blocked != null)
            blocked.setChecked(!showingWhitelist);
        if (whitelisted != null)
            whitelisted.setChecked(showingWhitelist);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == android.R.id.home) {
            finish();
            return true;
        } else if (itemId == R.id.menu_blocked_domains_blocked
                || itemId == R.id.menu_blocked_domains_whitelisted) {
            showingWhitelist = itemId == R.id.menu_blocked_domains_whitelisted;
            updateActionBarTitle();
            invalidateOptionsMenu();
            refresh();
            return true;
        } else if (itemId == R.id.menu_blocked_domains_add) {
            showAddDomainDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showAddDomainDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.domain_whitelist_hint);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.domain_whitelist_add_title)
                .setMessage(R.string.domain_whitelist_add_message)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.domain_whitelist_add_action, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String normalized = BlockedDomainLogic.normalize(
                            input.getText() == null ? null : input.getText().toString());
                    if (normalized == null) {
                        input.setError(getString(R.string.domain_whitelist_invalid));
                        return;
                    }
                    if (!DomainWhitelist.getInstance(this).addDomain(normalized)) {
                        input.setError(getString(R.string.domain_whitelist_exists));
                        return;
                    }
                    Toast.makeText(this, R.string.domain_whitelist_added,
                            Toast.LENGTH_SHORT).show();
                    refresh();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void showWhitelistConfirmation(String hostname) {
        DomainWhitelist whitelist = DomainWhitelist.getInstance(this);
        if (whitelist.isWhitelisted(hostname))
            return;

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.domain_whitelist_confirm_title)
                .setMessage(getString(
                        R.string.domain_whitelist_confirm_message, hostname))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.domain_whitelist_action, (dialog, which) -> {
                    if (whitelist.addDomain(hostname)) {
                        Toast.makeText(this, R.string.domain_whitelist_added,
                                Toast.LENGTH_SHORT).show();
                        refresh();
                    }
                })
                .show();
    }

    private void showRemoveConfirmation(String hostname) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.domain_whitelist_remove_title)
                .setMessage(R.string.domain_whitelist_remove_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.domain_whitelist_remove_action, (dialog, which) -> {
                    if (DomainWhitelist.getInstance(this).removeDomain(hostname)) {
                        Toast.makeText(this, R.string.domain_whitelist_removed,
                                Toast.LENGTH_SHORT).show();
                        refresh();
                    }
                })
                .show();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        requestId.incrementAndGet();
        if (executor != null)
            executor.shutdownNow();
        super.onDestroy();
    }
}
