/*
 * Copyright (C) 2017-2023 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infinity.updater;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemProperties;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONException;
import com.infinity.updater.controller.UpdaterController;
import com.infinity.updater.controller.UpdaterService;
import com.infinity.updater.download.DownloadClient;
import com.infinity.updater.misc.BuildInfoUtils;
import com.infinity.updater.misc.Constants;
import com.infinity.updater.misc.StringGenerator;
import com.infinity.updater.misc.Utils;
import com.infinity.updater.model.Update;
import com.infinity.updater.model.UpdateInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class UpdatesActivity extends UpdatesListActivity implements UpdateImporter.Callbacks {

    private static final String TAG = "UpdatesActivity";
    private UpdaterService mUpdaterService;
    private BroadcastReceiver mBroadcastReceiver;

    private UpdatesListAdapter mAdapter;

    private View mRefreshIconView;
    private RotateAnimation mRefreshAnimation;

    private boolean mIsTV;
    private boolean mIsRefreshing;
    private boolean mHasUpdates;
    private int mChangelogRequestId;

    private RecyclerView mRecyclerView;
    private View mNoNewUpdatesView;
    private View mUpdatesSectionHeader;
    private View mChangelogCard;
    private TextView mChangelogText;
    private ProgressBar mChangelogProgress;
    private ProgressBar mCheckingProgress;
    private MaterialButton mCheckUpdatesButton;

    private UpdateInfo mToBeExported = null;
    private final ActivityResultLauncher<Intent> mExportUpdate = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Intent intent = result.getData();
                    if (intent != null) {
                        Uri uri = intent.getData();
                        exportUpdate(uri);
                    }
                }
            });

    private UpdateImporter mUpdateImporter;
    private AlertDialog importDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        UiModeManager uiModeManager = getSystemService(UiModeManager.class);
        mIsTV = uiModeManager != null
                && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;

        if (!mIsTV) {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        }

        setContentView(R.layout.activity_updates);

        mUpdateImporter = new UpdateImporter(this, this);

        mRecyclerView = findViewById(R.id.recycler_view);
        mNoNewUpdatesView = findViewById(R.id.no_new_updates_view);
        mUpdatesSectionHeader = findViewById(R.id.updates_section_header);
        mChangelogCard = findViewById(R.id.changelog_card);
        mChangelogText = findViewById(R.id.changelog_text);
        mChangelogProgress = findViewById(R.id.changelog_progress);
        mCheckingProgress = findViewById(R.id.checking_progress);
        mCheckUpdatesButton = findViewById(R.id.check_updates_button);

        mAdapter = new UpdatesListAdapter(this);
        mRecyclerView.setAdapter(mAdapter);
        RecyclerView.LayoutManager layoutManager = new LinearLayoutManager(this);
        mRecyclerView.setLayoutManager(layoutManager);
        RecyclerView.ItemAnimator animator = mRecyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }

        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (UpdaterController.ACTION_UPDATE_STATUS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    handleDownloadStatusChange(downloadId);
                    mAdapter.notifyItemChanged(downloadId);
                } else if (UpdaterController.ACTION_DOWNLOAD_PROGRESS.equals(intent.getAction()) ||
                        UpdaterController.ACTION_INSTALL_PROGRESS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    mAdapter.notifyProgressChanged(downloadId);
                } else if (UpdaterController.ACTION_UPDATE_REMOVED.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    mAdapter.removeItem(downloadId);
                    updateUpdatesVisibility(mAdapter.getItemCount() > 0);
                }
            }
        };

        if (!mIsTV) {
            Toolbar toolbar = findViewById(R.id.toolbar);
            setSupportActionBar(toolbar);
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.setDisplayShowTitleEnabled(false);
                actionBar.setDisplayHomeAsUpEnabled(true);
            }

            View mainContainer = findViewById(R.id.main_container);
            AppBarLayout appBar = findViewById(R.id.app_bar);
            View contentContainer = findViewById(R.id.content_container);
            View headerContainer = findViewById(R.id.header_container);
            View toolbarTitle = findViewById(R.id.toolbar_title);
            final int extraBottomPx = (int) (24 * getResources().getDisplayMetrics().density);

            ViewCompat.setOnApplyWindowInsetsListener(mainContainer, (view, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                if (appBar != null) {
                    appBar.setPadding(appBar.getPaddingLeft(), bars.top,
                            appBar.getPaddingRight(), appBar.getPaddingBottom());
                }
                if (contentContainer != null) {
                    contentContainer.setPadding(contentContainer.getPaddingLeft(),
                            contentContainer.getPaddingTop(),
                            contentContainer.getPaddingRight(),
                            bars.bottom + extraBottomPx);
                }
                return windowInsets;
            });

            if (appBar != null) {
                appBar.addOnOffsetChangedListener((appBarLayout, verticalOffset) -> {
                    int totalScrollRange = appBarLayout.getTotalScrollRange();
                    if (totalScrollRange <= 0) {
                        return;
                    }
                    float fraction = Math.abs(verticalOffset) / (float) totalScrollRange;
                    if (headerContainer != null) {
                        headerContainer.setAlpha(Math.max(0f, 1f - (fraction * 1.15f)));
                    }
                    if (toolbarTitle != null) {
                        float titleAlpha = Math.max(0f, Math.min(1f, (fraction - 0.65f) / 0.35f));
                        toolbarTitle.setAlpha(titleAlpha);
                    }
                });

                if (!Utils.hasTouchscreen(this)) {
                    // This can't be collapsed without a touchscreen
                    appBar.setExpanded(false);
                }
            }

            mRefreshAnimation = new RotateAnimation(0, 360, Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f);
            mRefreshAnimation.setInterpolator(new LinearInterpolator());
            mRefreshAnimation.setDuration(1000);

            if (mCheckUpdatesButton != null) {
                mCheckUpdatesButton.setOnClickListener(v -> triggerManualRefresh());
            }
        } else {
            findViewById(R.id.refresh).setOnClickListener(v -> triggerManualRefresh());
            findViewById(R.id.preferences).setOnClickListener(v -> showPreferencesDialog());
        }

        updateHeaderBuildInfo();
        fetchChangelog();
        setupResourceLinks();
    }

    private void triggerManualRefresh() {
        downloadUpdatesList(true);
        fetchChangelog();
    }

    private void setupResourceLinks() {
        View playGameLink = findViewById(R.id.play_game_link);
        if (playGameLink != null) {
            playGameLink.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(UpdatesActivity.this, GameActivity.class);
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "Game not available", Toast.LENGTH_SHORT).show();
                }
            });
        }

        View sourceChangelogLink = findViewById(R.id.source_changelog_link);
        if (sourceChangelogLink != null) {
            sourceChangelogLink.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://projectinfinity-x.com/changelog/"));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "Unable to open link", Toast.LENGTH_SHORT).show();
                }
            });
        }

        View reportIssueLink = findViewById(R.id.report_issue_link);
        if (reportIssueLink != null) {
            reportIssueLink.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://projectinfinity-x.com/bug-report/"));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "Unable to open link", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void updateHeaderBuildInfo() {
        TextView headerBuildVersion = findViewById(R.id.header_build_version);
        if (headerBuildVersion != null) {
            String infinityVersion = BuildInfoUtils.getInfinityVersion();
            String device = SystemProperties.get(Constants.PROP_DEVICE, "");
            StringBuilder versionBuilder = new StringBuilder();
            if (infinityVersion != null && !infinityVersion.isEmpty()) {
                versionBuilder.append("Infinity-X v").append(infinityVersion)
                        .append("  •  ").append(getString(R.string.header_android_version,
                                Build.VERSION.RELEASE));
            } else {
                versionBuilder.append(getString(R.string.header_android_version,
                        Build.VERSION.RELEASE));
            }
            if (device != null && !device.isEmpty()) {
                versionBuilder.append("  •  ").append(device);
            }
            headerBuildVersion.setText(versionBuilder.toString());
        }

        TextView headerLastCheck = findViewById(R.id.header_last_check);
        if (headerLastCheck != null) {
            long buildTimestamp = BuildInfoUtils.getBuildDateTimestamp();
            long lastCheckMillis = PreferenceManager.getDefaultSharedPreferences(this)
                    .getLong(Constants.PREF_LAST_UPDATE_CHECK, 0);

            String lastCheckText;
            if (lastCheckMillis > 0) {
                long lastCheckSec = lastCheckMillis / 1000;
                lastCheckText = getString(R.string.header_last_updates_check,
                        StringGenerator.getDateLocalized(this, DateFormat.MEDIUM, lastCheckSec),
                        StringGenerator.getTimeLocalized(this, lastCheckSec));
            } else {
                lastCheckText = getString(R.string.last_checked_never);
            }

            if (buildTimestamp > 0) {
                String buildDateText = getString(R.string.header_build_date_short,
                        StringGenerator.getDateLocalizedUTC(this, DateFormat.MEDIUM,
                                buildTimestamp));
                headerLastCheck.setText(buildDateText + "  •  " + lastCheckText);
            } else {
                headerLastCheck.setText(lastCheckText);
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Intent intent = new Intent(this, UpdaterService.class);
        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_STATUS);
        intentFilter.addAction(UpdaterController.ACTION_DOWNLOAD_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_INSTALL_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_REMOVED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, intentFilter);
    }

    @Override
    protected void onPause() {
        if (importDialog != null) {
            importDialog.dismiss();
            importDialog = null;
            mUpdateImporter.stopImport();
        }

        super.onPause();
    }

    @Override
    public void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);
        if (mUpdaterService != null) {
            unbindService(mConnection);
        }
        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_toolbar, menu);
        if (!mIsTV && mIsRefreshing) {
            View refreshAction = findViewById(R.id.menu_refresh);
            if (refreshAction != null && mRefreshAnimation != null) {
                mRefreshIconView = refreshAction;
                mRefreshAnimation.setRepeatCount(Animation.INFINITE);
                mRefreshIconView.startAnimation(mRefreshAnimation);
                mRefreshIconView.setEnabled(false);
            }
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_refresh) {
            triggerManualRefresh();
            return true;
        } else if (itemId == R.id.menu_preferences) {
            showPreferencesDialog();
            return true;
        } else if (itemId == R.id.menu_local_update) {
            mUpdateImporter.openImportPicker();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (!mUpdateImporter.onResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onImportStarted() {
        if (importDialog != null && importDialog.isShowing()) {
            importDialog.dismiss();
        }

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_import_progress, null);
        importDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.local_update_import)
                .setView(dialogView)
                .setCancelable(false)
                .show();
    }

    @Override
    public void onImportCompleted(Update update) {
        if (importDialog != null) {
            importDialog.dismiss();
            importDialog = null;
        }

        if (isFinishing() || isDestroyed()) {
            return;
        }

        if (update == null) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.local_update_import)
                    .setMessage(R.string.local_update_import_failure)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        mAdapter.notifyDataSetChanged();

        final Runnable deleteUpdate = () -> UpdaterController.getInstance(this)
                .deleteUpdate(update.getDownloadId());

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.local_update_import)
                .setMessage(getString(R.string.local_update_import_success, update.getVersion()))
                .setPositiveButton(R.string.local_update_import_install, (dialog, which) -> {
                    mAdapter.addItem(update.getDownloadId());
                    // Update UI
                    getUpdatesList();
                    Utils.triggerUpdate(this, update.getDownloadId());
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> deleteUpdate.run())
                .setOnCancelListener((dialog) -> deleteUpdate.run())
                .show();
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                IBinder service) {
            UpdaterService.LocalBinder binder = (UpdaterService.LocalBinder) service;
            mUpdaterService = binder.getService();
            mAdapter.setUpdaterController(mUpdaterService.getUpdaterController());
            getUpdatesList();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mAdapter.setUpdaterController(null);
            mUpdaterService = null;
            mAdapter.notifyDataSetChanged();
        }
    };

    private void updateUpdatesVisibility(boolean hasUpdates) {
        mHasUpdates = hasUpdates;
        if (hasUpdates) {
            mNoNewUpdatesView.setVisibility(View.GONE);
            mRecyclerView.setVisibility(View.VISIBLE);
            if (mUpdatesSectionHeader != null) {
                mUpdatesSectionHeader.setVisibility(View.VISIBLE);
            }
            mChangelogCard.setVisibility(View.VISIBLE);
        } else {
            mNoNewUpdatesView.setVisibility(View.VISIBLE);
            mRecyclerView.setVisibility(View.GONE);
            if (mUpdatesSectionHeader != null) {
                mUpdatesSectionHeader.setVisibility(View.GONE);
            }
            mChangelogCard.setVisibility(View.GONE);
        }
    }

    private void loadUpdatesList(File jsonFile, boolean manualRefresh)
            throws IOException, JSONException {
        if (mUpdaterService == null) {
            return;
        }
        Log.d(TAG, "Adding remote updates");
        UpdaterController controller = mUpdaterService.getUpdaterController();
        boolean newUpdates = false;

        List<UpdateInfo> updates = Utils.parseJson(jsonFile, true);
        List<String> updatesOnline = new ArrayList<>();
        for (UpdateInfo update : updates) {
            newUpdates |= controller.addUpdate(update);
            updatesOnline.add(update.getDownloadId());
        }
        controller.setUpdatesAvailableOnline(updatesOnline, true);

        if (manualRefresh) {
            showSnackbar(
                    newUpdates ? R.string.snack_updates_found : R.string.snack_no_updates_found,
                    Snackbar.LENGTH_SHORT);
        }

        List<String> updateIds = new ArrayList<>();
        List<UpdateInfo> sortedUpdates = controller.getUpdates();
        if (sortedUpdates.isEmpty()) {
            updateUpdatesVisibility(false);
        } else {
            sortedUpdates.sort((u1, u2) -> Long.compare(u2.getTimestamp(), u1.getTimestamp()));
            for (UpdateInfo update : sortedUpdates) {
                updateIds.add(update.getDownloadId());
            }
            mAdapter.setData(updateIds);
            mAdapter.notifyDataSetChanged();
            updateUpdatesVisibility(true);
        }
    }

    private void getUpdatesList() {
        File jsonFile = Utils.getCachedUpdateList(this);
        if (jsonFile.exists()) {
            try {
                loadUpdatesList(jsonFile, false);
                Log.d(TAG, "Cached list parsed");
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Error while parsing json list", e);
            }
        } else {
            downloadUpdatesList(false);
        }
    }

    private void processNewJson(File json, File jsonNew, boolean manualRefresh) {
        try {
            loadUpdatesList(jsonNew, manualRefresh);
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            long millis = System.currentTimeMillis();
            preferences.edit().putLong(Constants.PREF_LAST_UPDATE_CHECK, millis).apply();
            updateHeaderBuildInfo();
            if (json.exists() && Utils.isUpdateCheckEnabled(this) &&
                    Utils.checkForNewUpdates(json, jsonNew)) {
                UpdatesCheckReceiver.updateRepeatingUpdatesCheck(this);
            }
            // In case we set a one-shot check because of a previous failure
            UpdatesCheckReceiver.cancelUpdatesCheck(this);
            //noinspection ResultOfMethodCallIgnored
            jsonNew.renameTo(json);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Could not read json", e);
            showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
        }
    }

    private void downloadUpdatesList(final boolean manualRefresh) {
        final File jsonFile = Utils.getCachedUpdateList(this);
        final File jsonFileTmp = new File(jsonFile.getAbsolutePath() + UUID.randomUUID());
        String url = Utils.getServerURL(this);
        Log.d(TAG, "Checking " + url);

        DownloadClient.DownloadCallback callback = new DownloadClient.DownloadCallback() {
            @Override
            public void onFailure(final boolean cancelled) {
                Log.e(TAG, "Could not download updates list");
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (!cancelled) {
                        showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
                    }
                    refreshAnimationStop();
                });
            }

            @Override
            public void onResponse(DownloadClient.Headers headers) {
            }

            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    Log.d(TAG, "List downloaded");
                    processNewJson(jsonFile, jsonFileTmp, manualRefresh);
                    refreshAnimationStop();
                });
            }
        };

        final DownloadClient downloadClient;
        try {
            downloadClient = new DownloadClient.Builder()
                    .setUrl(url)
                    .setDestination(jsonFileTmp)
                    .setDownloadCallback(callback)
                    .build();
        } catch (IOException exception) {
            Log.e(TAG, "Could not build download client");
            showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
            return;
        }

        refreshAnimationStart();
        downloadClient.start();
    }

    private void handleDownloadStatusChange(String downloadId) {
        if (Update.LOCAL_ID.equals(downloadId) || mUpdaterService == null) {
            return;
        }

        UpdateInfo update = mUpdaterService.getUpdaterController().getUpdate(downloadId);
        if (update == null) {
            return;
        }
        switch (update.getStatus()) {
            case PAUSED_ERROR:
                showSnackbar(R.string.snack_download_failed, Snackbar.LENGTH_LONG);
                break;
            case VERIFICATION_FAILED:
                showSnackbar(R.string.snack_download_verification_failed, Snackbar.LENGTH_LONG);
                break;
            case VERIFIED:
                showSnackbar(R.string.snack_download_verified, Snackbar.LENGTH_LONG);
                break;
        }
    }

    @Override
    public void exportUpdate(UpdateInfo update) {
        mToBeExported = update;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, update.getName());

        mExportUpdate.launch(intent);
    }

    private void exportUpdate(Uri uri) {
        Intent intent = new Intent(this, ExportUpdateService.class);
        intent.setAction(ExportUpdateService.ACTION_START_EXPORTING);
        intent.putExtra(ExportUpdateService.EXTRA_SOURCE_FILE, mToBeExported.getFile());
        intent.putExtra(ExportUpdateService.EXTRA_DEST_URI, uri);
        startService(intent);
    }

    @Override
    public void showSnackbar(int stringId, int duration) {
        Snackbar.make(findViewById(R.id.main_container), stringId, duration).show();
    }

    private void refreshAnimationStart() {
        mIsRefreshing = true;
        if (mCheckingProgress != null) {
            mCheckingProgress.setVisibility(View.VISIBLE);
        }
        if (mCheckUpdatesButton != null) {
            mCheckUpdatesButton.setEnabled(false);
        }

        if (!mIsTV) {
            if (mRefreshIconView == null) {
                mRefreshIconView = findViewById(R.id.menu_refresh);
            }
            if (mRefreshIconView != null) {
                mRefreshAnimation.setRepeatCount(Animation.INFINITE);
                mRefreshIconView.startAnimation(mRefreshAnimation);
                mRefreshIconView.setEnabled(false);
            }
        } else {
            mRecyclerView.setVisibility(View.GONE);
            mNoNewUpdatesView.setVisibility(View.GONE);
            findViewById(R.id.refresh_progress).setVisibility(View.VISIBLE);
        }
    }

    private void refreshAnimationStop() {
        mIsRefreshing = false;
        if (mCheckingProgress != null) {
            mCheckingProgress.setVisibility(View.GONE);
        }
        if (mCheckUpdatesButton != null) {
            mCheckUpdatesButton.setEnabled(true);
        }

        if (!mIsTV) {
            if (mRefreshIconView != null) {
                mRefreshAnimation.setRepeatCount(0);
                mRefreshIconView.setEnabled(true);
            }
        } else {
            findViewById(R.id.refresh_progress).setVisibility(View.GONE);
            updateUpdatesVisibility(mAdapter.getItemCount() > 0);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showPreferencesDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.preferences_dialog, null);
        Spinner autoCheckInterval = view.findViewById(R.id.preferences_auto_updates_check_interval);
        SwitchCompat autoDelete = view.findViewById(R.id.preferences_auto_delete_updates);
        SwitchCompat meteredNetworkWarning = view.findViewById(
                R.id.preferences_metered_network_warning);
        SwitchCompat abPerfMode = view.findViewById(R.id.preferences_ab_perf_mode);
        View abPerfModeRow = view.findViewById(R.id.preferences_ab_perf_mode_row);
        SwitchCompat updateRecovery = view.findViewById(R.id.preferences_update_recovery);
        View updateRecoveryRow = view.findViewById(R.id.preferences_update_recovery_row);

        if (!Utils.isABDevice()) {
            if (abPerfModeRow != null) {
                abPerfModeRow.setVisibility(View.GONE);
            } else {
                abPerfMode.setVisibility(View.GONE);
            }
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        autoCheckInterval.setSelection(Utils.getUpdateCheckSetting(this));
        autoDelete.setChecked(prefs.getBoolean(Constants.PREF_AUTO_DELETE_UPDATES, true));
        meteredNetworkWarning.setChecked(prefs.getBoolean(Constants.PREF_METERED_NETWORK_WARNING,
                prefs.getBoolean(Constants.PREF_MOBILE_DATA_WARNING, false)));
        abPerfMode.setChecked(prefs.getBoolean(Constants.PREF_AB_PERF_MODE, true));

        if (getResources().getBoolean(R.bool.config_hideRecoveryUpdate)) {
            // Hide the update feature if explicitly requested.
            // Might be the case of A-only devices using prebuilt vendor images.
            if (updateRecoveryRow != null) {
                updateRecoveryRow.setVisibility(View.GONE);
            } else {
                updateRecovery.setVisibility(View.GONE);
            }
        } else if (Utils.isRecoveryUpdateExecPresent()) {
            updateRecovery.setChecked(
                    SystemProperties.getBoolean(Constants.UPDATE_RECOVERY_PROPERTY, false));
        } else {
            // There is no recovery updater script in the device, so the feature is considered
            // forcefully enabled, just to avoid users to be confused and complain that
            // recovery gets overwritten. That's the case of A/B and recovery-in-boot devices.
            updateRecovery.setChecked(true);
            updateRecovery.setOnTouchListener(new View.OnTouchListener() {
                private Toast forcedUpdateToast = null;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_UP) {
                        if (forcedUpdateToast != null) {
                            forcedUpdateToast.cancel();
                        }
                        forcedUpdateToast = Toast.makeText(getApplicationContext(),
                                getString(R.string.toast_forced_update_recovery),
                                Toast.LENGTH_SHORT);
                        forcedUpdateToast.show();
                    }
                    return true;
                }
            });
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.menu_preferences)
                .setView(view)
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener(dialogInterface -> {
                    prefs.edit()
                            .putInt(Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL,
                                    autoCheckInterval.getSelectedItemPosition())
                            .putBoolean(Constants.PREF_AUTO_DELETE_UPDATES, autoDelete.isChecked())
                            .putBoolean(Constants.PREF_METERED_NETWORK_WARNING,
                                    meteredNetworkWarning.isChecked())
                            .putBoolean(Constants.PREF_AB_PERF_MODE, abPerfMode.isChecked())
                            .apply();

                    if (Utils.isUpdateCheckEnabled(this)) {
                        UpdatesCheckReceiver.scheduleRepeatingUpdatesCheck(this);
                    } else {
                        UpdatesCheckReceiver.cancelRepeatingUpdatesCheck(this);
                        UpdatesCheckReceiver.cancelUpdatesCheck(this);
                    }

                    if (Utils.isABDevice() && mUpdaterService != null) {
                        boolean enableABPerfMode = abPerfMode.isChecked();
                        mUpdaterService.getUpdaterController().setPerformanceMode(enableABPerfMode);
                    }
                    if (Utils.isRecoveryUpdateExecPresent()) {
                        boolean enableRecoveryUpdate = updateRecovery.isChecked();
                        SystemProperties.set(Constants.UPDATE_RECOVERY_PROPERTY,
                                String.valueOf(enableRecoveryUpdate));
                    }
                })
                .show();
    }

    private void fetchChangelog() {
        final int requestId = ++mChangelogRequestId;
        mChangelogCard.setVisibility(mHasUpdates ? View.VISIBLE : View.GONE);
        mChangelogProgress.setVisibility(View.VISIBLE);
        mChangelogText.setVisibility(View.GONE);

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                String changelogUrl = Utils.getChangelogURL(this);
                URL url = new URL(changelogUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    StringBuilder changelog = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            changelog.append(line).append("\n");
                        }
                    }

                    final String changelogText = changelog.toString().trim();
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed() || requestId != mChangelogRequestId) {
                            return;
                        }
                        mChangelogProgress.setVisibility(View.GONE);
                        if (!changelogText.isEmpty()) {
                            mChangelogText.setText(changelogText);
                        } else {
                            mChangelogText.setText(R.string.changelog_empty);
                        }
                        mChangelogText.setVisibility(View.VISIBLE);
                    });
                } else {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed() || requestId != mChangelogRequestId) {
                            return;
                        }
                        mChangelogProgress.setVisibility(View.GONE);
                        mChangelogText.setText(R.string.changelog_error);
                        mChangelogText.setVisibility(View.VISIBLE);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching changelog", e);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || requestId != mChangelogRequestId) {
                        return;
                    }
                    mChangelogProgress.setVisibility(View.GONE);
                    mChangelogText.setText(R.string.changelog_network_error);
                    mChangelogText.setVisibility(View.VISIBLE);
                });
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }
}
