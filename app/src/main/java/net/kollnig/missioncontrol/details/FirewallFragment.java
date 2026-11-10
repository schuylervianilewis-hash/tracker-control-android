/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.details;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.compose.ui.platform.ComposeView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import eu.faircode.netguard.GlideApp;
import eu.faircode.netguard.Rule;
import eu.faircode.netguard.ServiceSinkhole;
import eu.faircode.netguard.Util;
import net.kollnig.missioncontrol.Common;
import net.kollnig.missioncontrol.DetailsActivity;
import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.data.AppProtectionState;
import net.kollnig.missioncontrol.data.AppProtectionWriter;
import net.kollnig.missioncontrol.data.BlockingMode;
import net.kollnig.missioncontrol.data.FirewallPolicyLogic;
import net.kollnig.missioncontrol.data.InternetBlocklist;
import net.kollnig.missioncontrol.data.PausedApps;
import net.kollnig.missioncontrol.data.RemoteRoutingLogic;
import net.kollnig.missioncontrol.data.Tracker;
import net.kollnig.missioncontrol.data.TrackerBlocklist;
import net.kollnig.missioncontrol.data.TrackerCategory;
import net.kollnig.missioncontrol.data.TrackerList;
import net.kollnig.missioncontrol.data.TrackerStatusLogic;
import net.kollnig.missioncontrol.ui.compose.BlockedState;
import net.kollnig.missioncontrol.ui.compose.CategoryModel;
import net.kollnig.missioncontrol.ui.compose.CompanyModel;
import net.kollnig.missioncontrol.ui.compose.PauseSection;
import net.kollnig.missioncontrol.ui.compose.ProtectionScreen;
import net.kollnig.missioncontrol.ui.compose.ProtectionScreenCallbacks;
import net.kollnig.missioncontrol.ui.compose.ProtectionScreenController;
import net.kollnig.missioncontrol.ui.compose.ProtectionScreenModel;
import net.kollnig.missioncontrol.ui.compose.RouteModel;
import net.kollnig.missioncontrol.ui.compose.RouteOption;
import net.kollnig.missioncontrol.ui.compose.StateOption;

/**
 * Separate Firewall app list. It shares TrackerControl's installed-app inventory
 * and policy stores, but deliberately does not use AdapterRule or its click paths.
 */
public class FirewallFragment extends Fragment {
    private static final String PREF_SORT = "firewall_sort";
    private static final String ACTION_BLOCK = "block";
    private static final String ACTION_VPN = "vpn";
    private static final String ACTION_BYPASS = "bypass";

    private final Handler expiryHandler = new Handler(Looper.getMainLooper());
    private final Runnable expiryRunnable = this::onPauseExpired;
    private final List<Rule> allRules = new ArrayList<>();
    @Nullable private List<Rule> searchPool;
    private boolean loadingSearchPool;
    private final List<TrackerCategory> blockedTrackerCategories = new ArrayList<>();

    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView recyclerView;
    private FirewallAdapter adapter;
    private String searchQuery = "";
    private String sortMode = FirewallPolicyLogic.SORT_NAME;
    private String expandedPackage;
    private int blockedTrackerLoadGeneration;
    private boolean blockedTrackersLoaded;
    private boolean blockedRecordingUnavailable;
    private boolean showingResume;
    private boolean remoteVpnAvailable;
    private boolean destroyed;
    private ProtectionScreenController protectionController;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        Context context = requireContext();
        sortMode = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_SORT, FirewallPolicyLogic.SORT_NAME);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(4));
        root.setBackgroundColor(MaterialColors.getColor(root,
                com.google.android.material.R.attr.colorSurface));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.addView(globalButton(context, R.string.firewall_block_all, ACTION_BLOCK),
                new LinearLayout.LayoutParams(0, dp(44), 1f));
        actions.addView(globalButton(context, R.string.firewall_vpn_all, ACTION_VPN),
                new LinearLayout.LayoutParams(0, dp(44), 1f));
        actions.addView(globalButton(context, R.string.firewall_bypass_all, ACTION_BYPASS),
                new LinearLayout.LayoutParams(0, dp(44), 1f));
        root.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        swipeRefresh = new SwipeRefreshLayout(context);
        recyclerView = new RecyclerView(context);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerView.setHasFixedSize(false);
        recyclerView.setItemViewCacheSize(6);
        adapter = new FirewallAdapter();
        recyclerView.setAdapter(adapter);
        swipeRefresh.addView(recyclerView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        swipeRefresh.setOnRefreshListener(this::refresh);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        listParams.topMargin = dp(8);
        root.addView(swipeRefresh, listParams);
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        destroyed = false;
        refresh();
        if (expandedPackage != null) {
            updateProtectionState();
            loadBlockedTrackers();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        expiryHandler.removeCallbacks(expiryRunnable);
        if (isAdded())
            DetailsActivity.savePrefs(requireContext());
    }

    @Override
    public void onDestroyView() {
        destroyed = true;
        blockedTrackerLoadGeneration++;
        protectionController = null;
        swipeRefresh = null;
        recyclerView = null;
        adapter = null;
        super.onDestroyView();
    }

    public String getSortMode() {
        return sortMode;
    }

    public void setSortMode(String value) {
        if (!FirewallPolicyLogic.SORT_INSTALL_DATE.equals(value))
            value = FirewallPolicyLogic.SORT_NAME;
        sortMode = value;
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                .putString(PREF_SORT, sortMode).apply();
        applyFilterAndSort();
    }

    public void setSearchQuery(String query) {
        searchQuery = query == null ? "" : query.trim();
        if (!TextUtils.isEmpty(searchQuery) && searchPool == null)
            loadSearchPool();
        applyFilterAndSort();
    }

    public void refresh() {
        if (!isAdded() || adapter == null)
            return;
        if (swipeRefresh != null)
            swipeRefresh.setRefreshing(true);
        final Context context = requireContext().getApplicationContext();
        androidx.fragment.app.FragmentActivity activity = getActivity();
        if (activity == null)
            return;
        AsyncTask.execute(() -> {
            List<Rule> result = Rule.getRules(false, true, context);
            boolean routeAvailable = isRemoteVpnAvailable(context);
            activity.runOnUiThread(() -> {
                if (destroyed || !isAdded() || adapter == null)
                    return;
                allRules.clear();
                if (result != null)
                    allRules.addAll(result);
                searchPool = null;
                remoteVpnAvailable = routeAvailable;
                if (!TextUtils.isEmpty(searchQuery))
                    loadSearchPool();
                applyFilterAndSort();
                if (swipeRefresh != null)
                    swipeRefresh.setRefreshing(false);
            });
        });
    }

    private void applyFilterAndSort() {
        if (adapter == null || !isAdded())
            return;
        List<Rule> source = new ArrayList<>(allRules);
        if (!TextUtils.isEmpty(searchQuery) && searchPool != null) {
            Map<String, Rule> byPackage = new HashMap<>();
            for (Rule rule : source)
                byPackage.put(rule.packageName, rule);
            for (Rule stub : searchPool)
                byPackage.putIfAbsent(stub.packageName, stub);
            source = new ArrayList<>(byPackage.values());
        }

        final String query = searchQuery.toLowerCase(Locale.ROOT);
        List<Rule> filtered = new ArrayList<>();
        for (Rule rule : source) {
            if (TextUtils.isEmpty(query)
                    || rule.name.toLowerCase(Locale.ROOT).contains(query)
                    || rule.packageName.toLowerCase(Locale.ROOT).contains(query))
                filtered.add(rule);
        }

        final InternetBlocklist internet = InternetBlocklist.getInstance(requireContext());
        final boolean vpnAvailable = remoteVpnAvailable;
        final String selectedSort = sortMode;
        final Comparator<FirewallPolicyLogic.Entry> entryComparator =
                FirewallPolicyLogic.comparator(selectedSort);
        final Map<String, FirewallPolicyLogic.Entry> sortEntries = new HashMap<>();
        for (Rule rule : filtered) {
            int priority = FirewallPolicyLogic.priority(
                    rule.apply, internet.blockedInternet(rule.uid),
                    vpnAvailable && rule.wg_route);
            sortEntries.put(rule.packageName, new FirewallPolicyLogic.Entry(
                    priority, rule.name, rule.packageName, rule.firstInstallTime));
        }
        Collections.sort(filtered, (left, right) -> {
            return entryComparator.compare(sortEntries.get(left.packageName),
                    sortEntries.get(right.packageName));
        });
        adapter.setItems(filtered);
    }

    /** Build the all-installed-app search pool off the main thread, like Tracker's adapter. */
    private void loadSearchPool() {
        if (loadingSearchPool || searchPool != null || !isAdded())
            return;
        androidx.fragment.app.FragmentActivity activity = getActivity();
        if (activity == null)
            return;
        loadingSearchPool = true;
        Context context = requireContext().getApplicationContext();
        AsyncTask.execute(() -> {
            List<Rule> result = Rule.getSearchStubs(context);
            activity.runOnUiThread(() -> {
                loadingSearchPool = false;
                if (!isAdded() || destroyed)
                    return;
                searchPool = result == null ? Collections.emptyList() : result;
                applyFilterAndSort();
            });
        });
    }

    private MaterialButton globalButton(Context context, int title, String action) {
        MaterialButton button = new MaterialButton(context);
        button.setText(title);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(dp(10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        button.setOnClickListener(view -> confirmGlobalAction(action));
        return button;
    }

    private void confirmGlobalAction(String action) {
        if (ACTION_VPN.equals(action) && !remoteVpnAvailable) {
            Toast.makeText(requireContext(), R.string.app_route_unavailable_no_vpn,
                    Toast.LENGTH_LONG).show();
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(ACTION_BLOCK.equals(action) ? R.string.firewall_block_all
                        : ACTION_VPN.equals(action) ? R.string.firewall_vpn_all
                        : R.string.firewall_bypass_all)
                .setMessage(R.string.firewall_global_action_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> applyGlobalAction(action))
                .show();
    }

    private void applyGlobalAction(String action) {
        if (!isAdded())
            return;
        List<Rule> targets = new ArrayList<>();
        for (Rule rule : allRules) {
            if (rule.internet && rule.pkg && !rule.system
                    && !TextUtils.equals(rule.packageName, requireContext().getPackageName()))
                targets.add(rule);
        }
        if (targets.isEmpty())
            return;

        SharedPreferences routePrefs = requireContext().getSharedPreferences(
                Rule.PREF_WG_ROUTE, Context.MODE_PRIVATE);
        SharedPreferences.Editor routeEditor = routePrefs.edit();
        List<AppProtectionWriter.PackageChange> changes = new ArrayList<>();
        boolean routeChanged = !ACTION_BLOCK.equals(action);
        AppProtectionState.Change change = ACTION_BLOCK.equals(action)
                ? AppProtectionState.of(AppProtectionState.NO_INTERNET)
                : AppProtectionState.allowInternetKeepingProtection();

        for (Rule rule : targets) {
            if (ACTION_VPN.equals(action))
                routeEditor.putBoolean(rule.packageName, true);
            else if (ACTION_BYPASS.equals(action))
                routeEditor.putBoolean(rule.packageName, false);
            changes.add(new AppProtectionWriter.PackageChange(rule.packageName, rule.uid, change));
        }
        if (routeChanged)
            routeEditor.apply();
        AppProtectionWriter.applyManualBatch(requireContext(), changes, routeChanged);
        refresh();
    }

    private static boolean isRemoteVpnAvailable(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("wg_enabled", false)
                && !TextUtils.isEmpty(prefs.getString("wg_config", ""))
                && RemoteRoutingHelper.hasDefaultRoutes(context, prefs);
    }

    private void setPolicy(Rule rule, String action) {
        if (rule == null || !rule.pkg || !rule.internet
                || TextUtils.equals(rule.packageName, requireContext().getPackageName()))
            return;
        if (ACTION_VPN.equals(action) && !isRemoteVpnAvailable()) {
            Toast.makeText(requireContext(), R.string.app_route_unavailable_no_vpn,
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (ACTION_VPN.equals(action) || ACTION_BYPASS.equals(action)) {
            requireContext().getSharedPreferences(Rule.PREF_WG_ROUTE, Context.MODE_PRIVATE)
                    .edit().putBoolean(rule.packageName, ACTION_VPN.equals(action)).apply();
            AppProtectionWriter.applyManualAndReload(requireContext(), rule.packageName, rule.uid,
                    AppProtectionState.allowInternetKeepingProtection());
        } else {
            AppProtectionWriter.applyManual(requireContext(), rule.packageName, rule.uid,
                    AppProtectionState.of(AppProtectionState.NO_INTERNET));
        }
        refresh();
    }

    private void toggleExpanded(Rule rule) {
        if (rule == null || !rule.pkg || !rule.internet
                || TextUtils.equals(rule.packageName, requireContext().getPackageName()))
            return;
        String next = TextUtils.equals(expandedPackage, rule.packageName) ? null : rule.packageName;
        expandedPackage = next;
        protectionController = null;
        blockedTrackerCategories.clear();
        blockedTrackersLoaded = false;
        blockedRecordingUnavailable = false;
        blockedTrackerLoadGeneration++;
        expiryHandler.removeCallbacks(expiryRunnable);
        if (next != null) {
            updateProtectionState();
            loadBlockedTrackers();
        }
        if (adapter != null)
            adapter.notifyDataSetChanged();
    }

    private boolean isExpanded(Rule rule) {
        return rule != null && TextUtils.equals(expandedPackage, rule.packageName);
    }

    private void installProtectionPanel(ComposeView composeView, Rule rule) {
        protectionController = ProtectionScreen.installInline(
                composeView,
                buildScreenModel(rule),
                protectionCallbacks(rule),
                getString(R.string.firewall_excluded_note));
    }

    private ProtectionScreenCallbacks protectionCallbacks(Rule rule) {
        return new ProtectionScreenCallbacks() {
            @Override
            public void onPauseResume() {
                pauseOrResume(rule);
            }

            @Override
            public void onStateSelected(AppProtectionState value) {
                AppProtectionWriter.applyManual(requireContext(), rule.packageName,
                        rule.uid, AppProtectionState.of(value));
                updateProtectionState();
            }

            @Override
            public void onRouteSelected(boolean tunnelled) {
                applyRoute(rule, tunnelled);
            }

            @Override
            public void onCategoryToggle(String categoryKey, boolean checked) {
                toggleCategory(rule, categoryKey, checked);
            }

            @Override
            public void onCompanyClick(String blockingKey) {
                toggleCompany(rule, blockingKey);
            }
        };
    }

    private ProtectionScreenModel buildScreenModel(Rule rule) {
        AppProtectionState state = currentState(rule);
        boolean paused = PausedApps.isPaused(requireContext(), rule.packageName);
        showingResume = paused;
        List<String> sharedPackages = PausedApps.getSharedUidPackages(
                requireContext(), rule.packageName, rule.uid);

        PauseSection pause = null;
        boolean pauseAvailable = paused || state == AppProtectionState.PROTECTED
                || state == AppProtectionState.MINIMAL_ONLY
                || state == AppProtectionState.TRACKERS_ALLOWED;
        if (pauseAvailable) {
            String status;
            String action;
            if (paused) {
                int remainingMinutes = PausedApps.getRemainingMinutes(requireContext(), rule.packageName);
                status = getResources().getQuantityString(
                        R.plurals.protection_paused_resumes, remainingMinutes, remainingMinutes);
                action = getString(R.string.protection_resume);
            } else {
                status = stateLabel(state);
                int pauseMinutes = PausedApps.getConfiguredDurationMinutes(requireContext());
                action = getResources().getQuantityString(R.plurals.pause,
                        pauseMinutes, pauseMinutes);
            }
            String sharedUidText = sharedPackages.isEmpty() ? null
                    : getString(R.string.protection_pause_shared_uid, packageLabels(sharedPackages));
            String lockdownText = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? getString(R.string.protection_lockdown_note) : null;
            pause = new PauseSection(status, sharedUidText, action, lockdownText);
        }

        List<StateOption> stateOptions = buildStateOptions(rule, state, paused, sharedPackages);
        BlockedState blocked = buildBlockedState(rule, state);
        return new ProtectionScreenModel(pause, blocked, stateOptions, buildRouteModel(rule));
    }

    private List<StateOption> buildStateOptions(Rule rule, AppProtectionState state,
            boolean paused, List<String> sharedPackages) {
        AppProtectionState shownState = paused ? AppProtectionState.BYPASSED : state;
        String noInternetExplanation = getString(R.string.app_state_no_internet_explanation);
        if (!sharedPackages.isEmpty())
            noInternetExplanation = getString(R.string.app_state_no_internet_explanation_shared,
                    noInternetExplanation,
                    getString(R.string.app_state_no_internet_shared_uid, packageLabels(sharedPackages)));

        boolean showMinimal = !BlockingMode.isMinimalMode(requireContext())
                && !Util.isPlayStoreInstall(requireContext());
        List<StateOption> options = new ArrayList<>();
        options.add(new StateOption(AppProtectionState.PROTECTED,
                getString(R.string.app_state_protected),
                getString(R.string.app_state_protected_explanation),
                shownState == AppProtectionState.PROTECTED, true));
        options.add(new StateOption(AppProtectionState.MINIMAL_ONLY,
                getString(R.string.app_state_essential_only),
                getString(R.string.app_state_essential_only_explanation),
                shownState == AppProtectionState.MINIMAL_ONLY, showMinimal));
        options.add(new StateOption(AppProtectionState.TRACKERS_ALLOWED,
                getString(R.string.app_state_trackers_allowed),
                getString(R.string.app_state_trackers_allowed_explanation),
                shownState == AppProtectionState.TRACKERS_ALLOWED, true));
        options.add(new StateOption(AppProtectionState.NO_INTERNET,
                getString(R.string.app_state_no_internet), noInternetExplanation,
                shownState == AppProtectionState.NO_INTERNET, true));
        options.add(new StateOption(AppProtectionState.BYPASSED,
                getString(paused ? R.string.firewall_paused_state_label
                        : R.string.firewall_exclude_from_trackercontrol),
                getString(paused ? R.string.firewall_paused_state_explanation
                        : R.string.firewall_exclude_from_trackercontrol_explanation),
                shownState == AppProtectionState.BYPASSED, true));
        return Collections.unmodifiableList(options);
    }

    private BlockedState buildBlockedState(Rule rule, AppProtectionState state) {
        if (!blockedTrackersLoaded)
            return new BlockedState(false, null, Collections.emptyList());
        if (blockedRecordingUnavailable)
            return new BlockedState(false, getString(R.string.protection_recording_unavailable),
                    Collections.emptyList());

        SharedPreferences trackerProtect = requireContext()
                .getSharedPreferences("tracker_protect", Context.MODE_PRIVATE);
        boolean trackerProtectionEnabled = BlockingMode.isTrackerProtectionEnabled(
                requireContext(), trackerProtect, rule.packageName);
        boolean minimal = BlockingMode.isMinimalMode(requireContext());
        boolean minimalOnly = state == AppProtectionState.MINIMAL_ONLY;
        boolean readOnlyMinimal = minimal || minimalOnly;
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(requireContext());
        List<CategoryModel> categories = new ArrayList<>();
        for (TrackerCategory category : blockedTrackerCategories)
            categories.add(buildCategoryModel(rule, category, blocklist,
                    trackerProtectionEnabled, minimal, readOnlyMinimal, state));
        String message = categories.isEmpty()
                ? getString(R.string.protection_no_blocked_trackers) : null;
        return new BlockedState(true, message, Collections.unmodifiableList(categories));
    }

    private CategoryModel buildCategoryModel(Rule rule, TrackerCategory category,
            TrackerBlocklist blocklist, boolean trackerProtectionEnabled, boolean minimal,
            boolean readOnlyMinimal, AppProtectionState state) {
        String categoryName = category.getCategoryName();
        String categoryDisplayName = category.getDisplayName(requireContext());
        boolean categoryMinimallyBlocked = readOnlyMinimal && category.getChildren().stream()
                .anyMatch(TrackerList::isMinimallyBlocked);
        boolean categoryChecked = readOnlyMinimal
                ? trackerProtectionEnabled && categoryMinimallyBlocked
                : blocklist.blocked(rule.uid, categoryName);
        boolean categoryEnabled = !readOnlyMinimal && state == AppProtectionState.PROTECTED;
        List<CompanyModel> companies = new ArrayList<>();
        for (Tracker tracker : category.getChildren()) {
            TrackerStatusLogic.Result status = TrackerStatusLogic.resolve(
                    trackerProtectionEnabled,
                    minimal,
                    BlockingMode.isStrictMode(requireContext()),
                    state,
                    TrackerList.isMinimallyBlocked(tracker),
                    TrackerList.isMinimallyKnown(tracker),
                    blocklist.blocked(rule.uid, tracker.category),
                    blocklist.blocked(rule.uid, TrackerBlocklist.getBlockingKey(tracker)),
                    tracker.isAllowedInStandardMode());
            boolean statusBlocked = status.status == TrackerStatusLogic.Status.BLOCKED;
            String lastSeen = tracker.lastSeen != null && tracker.lastSeen != 0
                    ? Util.relativeTime(tracker.lastSeen).toString() : null;
            companies.add(new CompanyModel(
                    TrackerBlocklist.getBlockingKey(tracker), tracker.getName(), lastSeen,
                    statusString(status.status), statusBlocked,
                    state == AppProtectionState.PROTECTED && !readOnlyMinimal,
                    getString(statusBlocked ? R.string.feed_allow_company_in_app
                                    : R.string.feed_block_company_in_app,
                            tracker.getName(), rule.name)));
        }
        String lastContact = category.lastSeen != null && category.lastSeen != 0
                ? getString(R.string.feed_section_last_contact, Util.relativeTime(category.lastSeen)) : null;
        String explainer = category.isUncertain() ? getString(R.string.uncertain_entry) : null;
        return new CategoryModel(categoryName, categoryName, categoryDisplayName,
                lastContact, explainer, categoryChecked, categoryEnabled,
                getString(R.string.toggle_block_category_description, categoryDisplayName),
                Collections.unmodifiableList(companies));
    }

    private RouteModel buildRouteModel(Rule rule) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        boolean wgEnabled = prefs.getBoolean("wg_enabled", false)
                && !TextUtils.isEmpty(prefs.getString("wg_config", ""));
        boolean applyApp = requireContext().getSharedPreferences("apply", Context.MODE_PRIVATE)
                .getBoolean(rule.packageName, true);
        boolean defaultRoutes = wgEnabled && RemoteRoutingHelper.hasDefaultRoutes(requireContext(), prefs);
        RemoteRoutingLogic.Unavailable unavailable =
                RemoteRoutingLogic.getUnavailableReason(wgEnabled, defaultRoutes, applyApp);
        if (unavailable != null)
            return new RouteModel.Unavailable(explainUnavailable(unavailable));
        boolean tunnelled = routesThroughTunnel(rule);
        return new RouteModel.Available(Arrays.asList(
                new RouteOption(true, getString(R.string.app_route_through),
                        getString(R.string.app_route_through_explanation), tunnelled),
                new RouteOption(false, getString(R.string.app_route_direct),
                        getString(R.string.app_route_direct_explanation), !tunnelled)));
    }

    private void applyRoute(Rule rule, boolean wantsTunnel) {
        if (wantsTunnel == routesThroughTunnel(rule))
            return;
        requireContext().getSharedPreferences(Rule.PREF_WG_ROUTE, Context.MODE_PRIVATE)
                .edit().putBoolean(rule.packageName, wantsTunnel).apply();
        Rule.clearCache(requireContext());
        ServiceSinkhole.reload("app routing changed", requireContext(), false);
        refresh();
    }

    private boolean routesThroughTunnel(Rule rule) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String mode = RemoteRoutingLogic.normalizeMode(
                prefs.getString(Rule.PREF_WG_ROUTE_MODE, RemoteRoutingLogic.getDefaultMode()));
        return RemoteRoutingLogic.routesThroughTunnel(mode,
                RemoteRoutingHelper.getRouteOverride(requireContext(), rule.packageName), rule.apply);
    }

    private String explainUnavailable(RemoteRoutingLogic.Unavailable unavailable) {
        switch (unavailable) {
            case BYPASSED:
                return getString(R.string.app_route_unavailable_bypassed);
            case PARTIAL_ROUTES:
                return getString(R.string.app_route_unavailable_partial_routes);
            case NO_REMOTE_VPN:
            default:
                return getString(R.string.app_route_unavailable_no_vpn);
        }
    }

    private void updateProtectionState() {
        if (!isAdded() || expandedPackage == null)
            return;
        boolean paused = PausedApps.isPaused(requireContext(), expandedPackage);
        expiryHandler.removeCallbacks(expiryRunnable);
        showingResume = paused;
        if (paused)
            expiryHandler.postDelayed(expiryRunnable,
                    PausedApps.remainingMillis(requireContext(), expandedPackage) + 1_000L);
        renderProtectionPanel();
    }

    private void onPauseExpired() {
        AsyncTask.execute(() -> {
            if (!isAdded())
                return;
            PausedApps.sweep(requireContext());
            if (isAdded())
                requireActivity().runOnUiThread(() -> {
                    if (!destroyed)
                        updateProtectionState();
                });
        });
    }

    private void pauseOrResume(Rule rule) {
        if (showingResume) {
            PausedApps.resume(requireContext(), rule.packageName, rule.uid);
            updateProtectionState();
            return;
        }
        AppProtectionState state = currentState(rule);
        if (state != AppProtectionState.PROTECTED
                && state != AppProtectionState.MINIMAL_ONLY
                && state != AppProtectionState.TRACKERS_ALLOWED)
            return;
        if (Util.lockdownState(requireContext()) == Util.LockdownState.ENABLED) {
            Util.areYouSure(requireActivity(), R.string.protection_pause_lockdown_warning,
                    () -> pauseNow(rule));
        } else {
            pauseNow(rule);
        }
    }

    private void pauseNow(Rule rule) {
        PausedApps.pause(requireContext(), rule.packageName, rule.uid);
        updateProtectionState();
    }

    private void loadBlockedTrackers() {
        if (expandedPackage == null || !isAdded())
            return;
        final String packageName = expandedPackage;
        Rule rule = findRule(packageName);
        if (rule == null)
            return;
        final int uid = rule.uid;
        final int generation = ++blockedTrackerLoadGeneration;
        if (!PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getBoolean("log_app", true)) {
            blockedTrackersLoaded = true;
            blockedRecordingUnavailable = true;
            renderProtectionPanel();
            return;
        }
        blockedTrackersLoaded = false;
        blockedRecordingUnavailable = false;
        renderProtectionPanel();
        Context context = requireContext().getApplicationContext();
        androidx.fragment.app.FragmentActivity activity = getActivity();
        if (activity == null)
            return;
        AsyncTask.execute(() -> {
            List<TrackerCategory> categories = TrackerList.getInstance(context)
                    .getBlockedAppTrackers(context, uid);
            activity.runOnUiThread(() -> {
                if (!isAdded())
                    return;
                if (!destroyed && generation == blockedTrackerLoadGeneration
                        && TextUtils.equals(packageName, expandedPackage)) {
                    blockedTrackerCategories.clear();
                    if (categories != null)
                        blockedTrackerCategories.addAll(categories);
                    blockedTrackersLoaded = true;
                    blockedRecordingUnavailable = false;
                    renderProtectionPanel();
                }
            });
        });
    }

    private void toggleCategory(Rule rule, String categoryKey, boolean checked) {
        TrackerCategory category = null;
        for (TrackerCategory candidate : blockedTrackerCategories)
            if (TextUtils.equals(categoryKey, candidate.getCategoryName())) {
                category = candidate;
                break;
            }
        if (category == null)
            return;
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(requireContext());
        if (checked)
            blocklist.block(rule.uid, category.getCategoryName());
        else {
            blocklist.unblock(rule.uid, category.getCategoryName());
            Toast.makeText(requireContext(), R.string.category_unblocked,
                    Toast.LENGTH_SHORT).show();
        }
        blocklist.saveSettings(requireContext());
        renderProtectionPanel();
    }

    private void toggleCompany(Rule rule, String blockingKey) {
        Tracker tracker = null;
        for (TrackerCategory category : blockedTrackerCategories) {
            for (Tracker candidate : category.getChildren())
                if (TextUtils.equals(blockingKey, TrackerBlocklist.getBlockingKey(candidate))) {
                    tracker = candidate;
                    break;
                }
            if (tracker != null)
                break;
        }
        if (tracker == null)
            return;
        if (InternetBlocklist.getInstance(requireContext()).blockedInternet(rule.uid))
            return;
        if (!BlockingMode.isStrictMode(requireContext()) && tracker.isAllowedInStandardMode()) {
            Toast.makeText(requireContext(), R.string.allowed_shared_ip, Toast.LENGTH_SHORT).show();
            return;
        }
        TrackerBlocklist blocklist = TrackerBlocklist.getInstance(requireContext());
        if (!blocklist.blocked(rule.uid, tracker.category)) {
            Toast.makeText(requireContext(), R.string.category_unblocked_warning,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (blocklist.blockedTracker(rule.uid, tracker))
            blocklist.unblock(rule.uid, tracker);
        else
            blocklist.block(rule.uid, tracker);
        blocklist.saveSettings(requireContext());
        renderProtectionPanel();
    }

    private void renderProtectionPanel() {
        if (protectionController == null || expandedPackage == null)
            return;
        Rule rule = findRule(expandedPackage);
        if (rule != null)
            protectionController.update(buildScreenModel(rule));
    }

    private Rule findRule(String packageName) {
        for (Rule rule : allRules)
            if (TextUtils.equals(rule.packageName, packageName))
                return rule;
        return null;
    }

    private AppProtectionState currentState(Rule rule) {
        SharedPreferences apply = requireContext().getSharedPreferences("apply", Context.MODE_PRIVATE);
        SharedPreferences trackerProtect = requireContext().getSharedPreferences("tracker_protect", Context.MODE_PRIVATE);
        SharedPreferences minimalOnly = requireContext().getSharedPreferences("tracker_essential", Context.MODE_PRIVATE);
        return AppProtectionState.resolve(
                apply.getBoolean(rule.packageName, true),
                BlockingMode.isTrackerProtectionEnabled(requireContext(), trackerProtect, rule.packageName),
                InternetBlocklist.getInstance(requireContext()).blockedInternet(rule.uid),
                BlockingMode.isMinimalOnlyApp(requireContext(), minimalOnly, rule.packageName));
    }

    private String statusString(TrackerStatusLogic.Status status) {
        switch (status) {
            case BLOCKED:
                return getString(R.string.timeline_tracker_blocked);
            case ALLOWED_BY_USER:
                return getString(R.string.feed_allowed_by_you);
            case ALLOWED_SHARED_IP:
                return getString(R.string.feed_allowed_shared_ip);
            case MONITORED:
                return getString(R.string.feed_monitored);
            case ALLOWED:
            default:
                return getString(R.string.timeline_tracker_allowed);
        }
    }

    private String packageLabels(List<String> packages) {
        List<String> labels = new ArrayList<>();
        PackageManager pm = requireContext().getPackageManager();
        for (String packageName : packages) {
            try {
                labels.add(pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString());
            } catch (PackageManager.NameNotFoundException ex) {
                labels.add(packageName);
            }
        }
        return TextUtils.join(", ", labels);
    }

    private String stateLabel(AppProtectionState state) {
        switch (state) {
            case TRACKERS_ALLOWED:
                return getString(R.string.app_state_trackers_allowed);
            case MINIMAL_ONLY:
                return getString(R.string.app_state_essential_only);
            case NO_INTERNET:
                return getString(R.string.app_state_no_internet);
            case BYPASSED:
                return getString(R.string.app_state_bypassed);
            case PROTECTED:
            default:
                return getString(R.string.app_state_protected);
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class FirewallAdapter extends RecyclerView.Adapter<FirewallViewHolder> {
        private final List<Rule> items = new ArrayList<>();

        void setItems(List<Rule> values) {
            items.clear();
            items.addAll(values);
            notifyDataSetChanged();
        }

        @Override
        public FirewallViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundResource(android.R.drawable.list_selector_background);

            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(6), dp(8), dp(4), dp(8));
            root.addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ImageView icon = new ImageView(context);
            header.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

            LinearLayout labels = new LinearLayout(context);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(8), 0, dp(4), 0);
            header.addView(labels, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView name = new TextView(context);
            name.setTextSize(14);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(name);
            TextView status = new TextView(context);
            status.setTextSize(10);
            labels.addView(status);

            LinearLayout buttons = new LinearLayout(context);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            header.addView(buttons, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            MaterialButton block = rowButton(context, R.string.firewall_block);
            MaterialButton vpn = rowButton(context, R.string.firewall_vpn);
            MaterialButton bypass = rowButton(context, R.string.firewall_bypass);
            buttons.addView(block, new LinearLayout.LayoutParams(dp(58), dp(38)));
            buttons.addView(vpn, new LinearLayout.LayoutParams(dp(54), dp(38)));
            buttons.addView(bypass, new LinearLayout.LayoutParams(dp(66), dp(38)));

            LinearLayout expanded = new LinearLayout(context);
            expanded.setOrientation(LinearLayout.VERTICAL);
            expanded.setVisibility(View.GONE);
            expanded.setPadding(dp(4), 0, dp(4), dp(8));
            root.addView(expanded, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new FirewallViewHolder(root, header, icon, name, status,
                    block, vpn, bypass, expanded);
        }

        @Override
        public void onBindViewHolder(FirewallViewHolder holder, int position) {
            Rule rule = items.get(position);
            Context context = holder.itemView.getContext();
            holder.name.setText(rule.name);
            holder.name.setTextColor(rule.system
                    ? MaterialColors.getColor(holder.name,
                            com.google.android.material.R.attr.colorOnSurfaceVariant)
                    : MaterialColors.getColor(holder.name,
                            com.google.android.material.R.attr.colorOnSurface));
            holder.status.setText(statusLabel(rule));
            holder.status.setTextColor(MaterialColors.getColor(holder.status,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            if (rule.icon <= 0) {
                holder.icon.setImageResource(android.R.drawable.sym_def_app_icon);
            } else {
                Uri iconUri = Uri.parse("android.resource://" + rule.packageName + "/" + rule.icon);
                GlideApp.with(context).load(iconUri)
                        .override(dp(40), dp(40)).into(holder.icon);
            }
            boolean active = rule.apply;
            boolean blocked = active
                    && InternetBlocklist.getInstance(context).blockedInternet(rule.uid);
            boolean vpn = active && !blocked && remoteVpnAvailable && rule.wg_route;
            boolean bypass = active && !blocked && !vpn;
            boolean protectedEngineApp = TextUtils.equals(rule.packageName, context.getPackageName());
            boolean canManage = rule.pkg && rule.internet && !protectedEngineApp;
            holder.block.setEnabled(canManage);
            holder.vpn.setEnabled(canManage && remoteVpnAvailable);
            holder.bypass.setEnabled(canManage);
            stylePolicyButton(holder.block, blocked);
            stylePolicyButton(holder.vpn, vpn);
            stylePolicyButton(holder.bypass, bypass);

            String expandDescription = context.getString(
                    isExpanded(rule) ? R.string.firewall_collapse_app
                            : R.string.firewall_expand_app, rule.name);
            holder.icon.setContentDescription(expandDescription);
            holder.name.setContentDescription(expandDescription);
            holder.icon.setEnabled(canManage);
            holder.name.setEnabled(canManage);
            holder.icon.setOnClickListener(view -> toggleExpanded(rule));
            holder.name.setOnClickListener(view -> toggleExpanded(rule));
            holder.block.setContentDescription(protectedEngineApp
                    ? getString(R.string.firewall_self_app_note)
                    : context.getString(R.string.firewall_action_for_app,
                            context.getString(R.string.firewall_block), rule.name));
            holder.vpn.setContentDescription(protectedEngineApp
                    ? getString(R.string.firewall_self_app_note)
                    : !remoteVpnAvailable ? getString(R.string.app_route_unavailable_no_vpn)
                    : context.getString(R.string.firewall_action_for_app,
                            context.getString(R.string.firewall_vpn), rule.name));
            holder.bypass.setContentDescription(protectedEngineApp
                    ? getString(R.string.firewall_self_app_note)
                    : context.getString(R.string.firewall_action_for_app,
                            context.getString(R.string.firewall_bypass), rule.name));
            holder.header.setOnClickListener(null);
            holder.block.setOnClickListener(view -> setPolicy(rule, ACTION_BLOCK));
            holder.vpn.setOnClickListener(view -> setPolicy(rule, ACTION_VPN));
            holder.bypass.setOnClickListener(view -> setPolicy(rule, ACTION_BYPASS));

            holder.expanded.removeAllViews();
            boolean expanded = isExpanded(rule);
            holder.expanded.setVisibility(expanded ? View.VISIBLE : View.GONE);
            if (expanded) {
                ComposeView composeView = new ComposeView(context);
                holder.expanded.addView(composeView, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(520)));
                installProtectionPanel(composeView, rule);
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        private String statusLabel(Rule rule) {
            if (TextUtils.equals(rule.packageName, requireContext().getPackageName()))
                return getString(R.string.firewall_self_app_note);
            if (!rule.internet)
                return getString(R.string.no_internet);
            if (!rule.apply)
                return getString(R.string.firewall_excluded_status);
            if (InternetBlocklist.getInstance(requireContext()).blockedInternet(rule.uid))
                return getString(R.string.firewall_block);
            return remoteVpnAvailable && rule.wg_route
                    ? getString(R.string.firewall_vpn)
                    : getString(R.string.firewall_bypass);
        }

        private MaterialButton rowButton(Context context, int label) {
            MaterialButton button = new MaterialButton(context);
            button.setText(label);
            button.setTextSize(9);
            button.setAllCaps(false);
            button.setInsetTop(0);
            button.setInsetBottom(0);
            button.setCornerRadius(dp(8));
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setPadding(dp(2), 0, dp(2), 0);
            return button;
        }

        private void stylePolicyButton(MaterialButton button, boolean selected) {
            int primary = ContextCompat.getColor(button.getContext(), R.color.colorPrimary);
            int surface = ContextCompat.getColor(button.getContext(), R.color.vian_surface_variant);
            int onSurface = ContextCompat.getColor(button.getContext(), R.color.vian_text_primary);
            button.setSelected(selected);
            button.setBackgroundTintList(ColorStateList.valueOf(selected ? primary : surface));
            button.setTextColor(selected ? Color.WHITE : onSurface);
            button.setStrokeWidth(selected ? 0 : dp(1));
        }
    }

    private static final class FirewallViewHolder extends RecyclerView.ViewHolder {
        final LinearLayout header;
        final ImageView icon;
        final TextView name;
        final TextView status;
        final MaterialButton block;
        final MaterialButton vpn;
        final MaterialButton bypass;
        final LinearLayout expanded;

        FirewallViewHolder(View itemView, LinearLayout header, ImageView icon,
                TextView name, TextView status, MaterialButton block, MaterialButton vpn,
                MaterialButton bypass, LinearLayout expanded) {
            super(itemView);
            this.header = header;
            this.icon = icon;
            this.name = name;
            this.status = status;
            this.block = block;
            this.vpn = vpn;
            this.bypass = bypass;
            this.expanded = expanded;
        }
    }
}
