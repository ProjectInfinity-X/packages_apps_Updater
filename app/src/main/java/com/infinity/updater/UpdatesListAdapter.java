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
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.text.format.Formatter;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.appcompat.view.menu.MenuPopupHelper;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import com.infinity.updater.controller.UpdaterController;
import com.infinity.updater.controller.UpdaterService;
import com.infinity.updater.misc.Constants;
import com.infinity.updater.misc.StringGenerator;
import com.infinity.updater.misc.Utils;
import com.infinity.updater.model.UpdateInfo;
import com.infinity.updater.model.UpdateStatus;

import java.io.IOException;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

public class UpdatesListAdapter extends RecyclerView.Adapter<UpdatesListAdapter.ViewHolder> {

    private static final String TAG = "UpdateListAdapter";
    private static final Object PAYLOAD_PROGRESS = new Object();

    private static final int BATTERY_PLUGGED_ANY = BatteryManager.BATTERY_PLUGGED_AC
            | BatteryManager.BATTERY_PLUGGED_USB
            | BatteryManager.BATTERY_PLUGGED_WIRELESS;

    private final float mAlphaDisabledValue;

    private List<String> mDownloadIds;
    private String mSelectedDownload;
    private UpdaterController mUpdaterController;
    private final UpdatesListActivity mActivity;

    private AlertDialog infoDialog;

    private enum Action {
        DOWNLOAD,
        PAUSE,
        RESUME,
        INSTALL,
        INFO,
        DELETE,
        CANCEL_INSTALLATION,
        REBOOT,
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final MaterialCardView mCard;
        private final TextView mStatusBadge;
        private final MaterialButton mAction;
        private final ImageButton mMenu;

        private final TextView mBuildDate;
        private final TextView mBuildVersion;
        private final TextView mBuildSize;

        private final LinearLayout mProgress;
        private final ProgressBar mProgressBar;
        private final TextView mProgressText;
        private final TextView mPercentage;

        public ViewHolder(final View view) {
            super(view);
            mCard = view.findViewById(R.id.update_card);
            mStatusBadge = view.findViewById(R.id.update_status_badge);
            mAction = view.findViewById(R.id.update_action);
            mMenu = view.findViewById(R.id.update_menu);

            mBuildDate = view.findViewById(R.id.build_date);
            mBuildVersion = view.findViewById(R.id.build_version);
            mBuildSize = view.findViewById(R.id.build_size);

            mProgress = view.findViewById(R.id.progress);
            mProgressBar = view.findViewById(R.id.progress_bar);
            mProgressText = view.findViewById(R.id.progress_text);
            mPercentage = view.findViewById(R.id.progress_percent);
        }
    }

    public UpdatesListAdapter(UpdatesListActivity activity) {
        mActivity = activity;

        TypedValue tv = new TypedValue();
        mActivity.getTheme().resolveAttribute(android.R.attr.disabledAlpha, tv, true);
        mAlphaDisabledValue = tv.getFloat();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(ViewGroup viewGroup, int i) {
        View view = LayoutInflater.from(viewGroup.getContext())
                .inflate(R.layout.update_item_view, viewGroup, false);
        return new ViewHolder(view);
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ViewHolder holder) {
        super.onViewDetachedFromWindow(holder);

        if (infoDialog != null && infoDialog.isShowing()) {
            infoDialog.dismiss();
        }
    }

    public void setUpdaterController(UpdaterController updaterController) {
        mUpdaterController = updaterController;
        notifyDataSetChanged();
    }

    private void styleStatusBadge(TextView badge, @StringRes int textResId,
            @ColorRes int bgColorResId, @ColorRes int textColorResId) {
        if (badge == null) {
            return;
        }
        badge.setText(textResId);
        badge.setTextColor(ContextCompat.getColor(mActivity, textColorResId));
        ViewCompat.setBackgroundTintList(badge,
                ColorStateList.valueOf(ContextCompat.getColor(mActivity, bgColorResId)));
    }

    private void handleActiveStatus(ViewHolder viewHolder, UpdateInfo update) {
        boolean canDelete = false;

        final String downloadId = update.getDownloadId();
        String totalSize = Formatter.formatShortFileSize(mActivity, update.getFileSize());
        viewHolder.mBuildSize.setText(totalSize);

        if (mUpdaterController.isDownloading(downloadId)) {
            canDelete = true;
            styleStatusBadge(viewHolder.mStatusBadge, R.string.status_downloading,
                    R.color.blue_primary_container, R.color.blue_on_primary_container);
            long downloadedBytes = update.getFile() != null ? update.getFile().length() : 0;
            String downloaded = Formatter.formatShortFileSize(mActivity, downloadedBytes);
            String percentage = NumberFormat.getPercentInstance().format(
                    update.getProgress() / 100.f);
            viewHolder.mPercentage.setVisibility(View.VISIBLE);
            viewHolder.mPercentage.setText(percentage);
            long eta = update.getEta();
            if (eta > 0) {
                CharSequence etaString = StringGenerator.formatETA(mActivity, eta * 1000);
                viewHolder.mProgressText.setText(mActivity.getString(
                        R.string.list_download_progress_eta_newer, downloaded, totalSize,
                        etaString));
            } else {
                viewHolder.mProgressText.setText(mActivity.getString(
                        R.string.list_download_progress_newer, downloaded, totalSize));
            }
            setButtonAction(viewHolder.mAction, Action.PAUSE, downloadId, true);
            boolean isStarting = update.getStatus() == UpdateStatus.STARTING;
            if (viewHolder.mProgressBar.isIndeterminate() != isStarting) {
                viewHolder.mProgressBar.setIndeterminate(isStarting);
            }
            viewHolder.mProgressBar.setProgress(update.getProgress());
        } else if (mUpdaterController.isInstallingUpdate(downloadId)) {
            styleStatusBadge(viewHolder.mStatusBadge, R.string.status_installing,
                    R.color.blue_primary_container, R.color.blue_on_primary_container);
            setButtonAction(viewHolder.mAction, Action.CANCEL_INSTALLATION, downloadId, true);
            boolean notAB = !mUpdaterController.isInstallingABUpdate();
            viewHolder.mProgressText.setText(notAB ? R.string.dialog_prepare_zip_message :
                    update.getFinalizing() ?
                            R.string.finalizing_package :
                            R.string.preparing_ota_first_boot);
            String percentage = NumberFormat.getPercentInstance().format(
                    update.getInstallProgress() / 100.f);
            viewHolder.mPercentage.setVisibility(View.VISIBLE);
            viewHolder.mPercentage.setText(percentage);
            if (viewHolder.mProgressBar.isIndeterminate()) {
                viewHolder.mProgressBar.setIndeterminate(false);
            }
            viewHolder.mProgressBar.setProgress(update.getInstallProgress());
        } else if (mUpdaterController.isVerifyingUpdate(downloadId)) {
            styleStatusBadge(viewHolder.mStatusBadge, R.string.status_verifying,
                    R.color.blue_primary_container, R.color.blue_on_primary_container);
            setButtonAction(viewHolder.mAction, Action.INSTALL, downloadId, false);
            viewHolder.mPercentage.setVisibility(View.GONE);
            viewHolder.mProgressText.setText(R.string.list_verifying_update);
            if (!viewHolder.mProgressBar.isIndeterminate()) {
                viewHolder.mProgressBar.setIndeterminate(true);
            }
        } else {
            canDelete = true;
            styleStatusBadge(viewHolder.mStatusBadge, R.string.status_paused,
                    R.color.status_warning_container, R.color.status_warning);
            setButtonAction(viewHolder.mAction, Action.RESUME, downloadId, !isBusy());
            long downloadedBytes = update.getFile() != null ? update.getFile().length() : 0;
            String downloaded = Formatter.formatShortFileSize(mActivity, downloadedBytes);
            String percentage = NumberFormat.getPercentInstance().format(
                    update.getProgress() / 100.f);
            viewHolder.mPercentage.setVisibility(View.VISIBLE);
            viewHolder.mPercentage.setText(percentage);
            viewHolder.mProgressText.setText(mActivity.getString(
                    R.string.list_download_progress_newer, downloaded, totalSize));
            if (viewHolder.mProgressBar.isIndeterminate()) {
                viewHolder.mProgressBar.setIndeterminate(false);
            }
            viewHolder.mProgressBar.setProgress(update.getProgress());
        }

        viewHolder.mMenu.setOnClickListener(getClickListener(update, canDelete, viewHolder.mMenu));
        viewHolder.mProgress.setVisibility(View.VISIBLE);
    }

    private void handleNotActiveStatus(ViewHolder viewHolder, UpdateInfo update) {
        final String downloadId = update.getDownloadId();
        if (mUpdaterController.isWaitingForReboot(downloadId)) {
            styleStatusBadge(viewHolder.mStatusBadge, R.string.status_reboot,
                    R.color.status_success_container, R.color.status_success);
            viewHolder.mMenu.setOnClickListener(getClickListener(update, false, viewHolder.mMenu));
            setButtonAction(viewHolder.mAction, Action.REBOOT, downloadId, true);
        } else if (update.getPersistentStatus() == UpdateStatus.Persistent.VERIFIED) {
            if (Utils.canInstall(update)) {
                styleStatusBadge(viewHolder.mStatusBadge, R.string.status_ready,
                        R.color.status_success_container, R.color.status_success);
                setButtonAction(viewHolder.mAction, Action.INSTALL, downloadId, !isBusy());
            } else {
                styleStatusBadge(viewHolder.mStatusBadge, R.string.status_blocked,
                        R.color.status_error_container, R.color.status_error);
                setButtonAction(viewHolder.mAction, Action.DELETE, downloadId, !isBusy());
            }
            viewHolder.mMenu.setOnClickListener(getClickListener(update, true, viewHolder.mMenu));
        } else if (!Utils.canInstall(update)) {
            styleStatusBadge(viewHolder.mStatusBadge, R.string.status_blocked,
                    R.color.status_error_container, R.color.status_error);
            viewHolder.mMenu.setOnClickListener(getClickListener(update, false, viewHolder.mMenu));
            setButtonAction(viewHolder.mAction, Action.INFO, downloadId, !isBusy());
        } else {
            styleStatusBadge(viewHolder.mStatusBadge, R.string.status_available,
                    R.color.blue_primary_container, R.color.blue_on_primary_container);
            viewHolder.mMenu.setOnClickListener(getClickListener(update, false, viewHolder.mMenu));
            setButtonAction(viewHolder.mAction, Action.DOWNLOAD, downloadId, !isBusy());
        }
        String fileSize = Formatter.formatShortFileSize(mActivity, update.getFileSize());
        viewHolder.mBuildSize.setText(fileSize);
        viewHolder.mProgress.setVisibility(View.GONE);
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder viewHolder, int position,
            @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_PROGRESS)
                && mDownloadIds != null && mUpdaterController != null
                && position >= 0 && position < mDownloadIds.size()) {
            UpdateInfo update = mUpdaterController.getUpdate(mDownloadIds.get(position));
            if (update != null && bindProgressOnly(viewHolder, update)) {
                return;
            }
        }
        onBindViewHolder(viewHolder, position);
    }

    private boolean bindProgressOnly(ViewHolder viewHolder, UpdateInfo update) {
        final String downloadId = update.getDownloadId();
        if (mUpdaterController.isDownloading(downloadId)) {
            String totalSize = Formatter.formatShortFileSize(mActivity, update.getFileSize());
            long downloadedBytes = update.getFile() != null ? update.getFile().length() : 0;
            String downloaded = Formatter.formatShortFileSize(mActivity, downloadedBytes);
            String percentage = NumberFormat.getPercentInstance().format(
                    update.getProgress() / 100.f);
            viewHolder.mPercentage.setVisibility(View.VISIBLE);
            viewHolder.mPercentage.setText(percentage);
            long eta = update.getEta();
            if (eta > 0) {
                CharSequence etaString = StringGenerator.formatETA(mActivity, eta * 1000);
                viewHolder.mProgressText.setText(mActivity.getString(
                        R.string.list_download_progress_eta_newer, downloaded, totalSize,
                        etaString));
            } else {
                viewHolder.mProgressText.setText(mActivity.getString(
                        R.string.list_download_progress_newer, downloaded, totalSize));
            }
            boolean isStarting = update.getStatus() == UpdateStatus.STARTING;
            if (viewHolder.mProgressBar.isIndeterminate() != isStarting) {
                viewHolder.mProgressBar.setIndeterminate(isStarting);
            }
            viewHolder.mProgressBar.setProgress(update.getProgress());
            viewHolder.mProgress.setVisibility(View.VISIBLE);
            return true;
        } else if (mUpdaterController.isInstallingUpdate(downloadId)) {
            boolean notAB = !mUpdaterController.isInstallingABUpdate();
            viewHolder.mProgressText.setText(notAB ? R.string.dialog_prepare_zip_message :
                    update.getFinalizing() ?
                            R.string.finalizing_package :
                            R.string.preparing_ota_first_boot);
            String percentage = NumberFormat.getPercentInstance().format(
                    update.getInstallProgress() / 100.f);
            viewHolder.mPercentage.setVisibility(View.VISIBLE);
            viewHolder.mPercentage.setText(percentage);
            if (viewHolder.mProgressBar.isIndeterminate()) {
                viewHolder.mProgressBar.setIndeterminate(false);
            }
            viewHolder.mProgressBar.setProgress(update.getInstallProgress());
            viewHolder.mProgress.setVisibility(View.VISIBLE);
            return true;
        }
        return false;
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder viewHolder, int i) {
        if (mDownloadIds == null || mUpdaterController == null) {
            viewHolder.mAction.setEnabled(false);
            return;
        }

        final String downloadId = mDownloadIds.get(i);
        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        if (update == null) {
            // The update was deleted
            viewHolder.mAction.setEnabled(false);
            viewHolder.mAction.setText(R.string.action_download);
            return;
        }

        boolean selected = downloadId.equals(mSelectedDownload);
        viewHolder.itemView.setSelected(selected);
        if (viewHolder.mCard != null) {
            float density = mActivity.getResources().getDisplayMetrics().density;
            viewHolder.mCard.setStrokeColor(ContextCompat.getColor(mActivity,
                    selected ? R.color.blue_primary : R.color.surface_stroke));
            viewHolder.mCard.setStrokeWidth((int) ((selected ? 2f : 1f) * density));
        }

        boolean activeLayout;
        switch (update.getPersistentStatus()) {
            case UpdateStatus.Persistent.UNKNOWN:
                activeLayout = update.getStatus() == UpdateStatus.STARTING;
                break;
            case UpdateStatus.Persistent.VERIFIED:
                activeLayout = update.getStatus() == UpdateStatus.INSTALLING;
                break;
            case UpdateStatus.Persistent.INCOMPLETE:
                activeLayout = true;
                break;
            default:
                throw new RuntimeException("Unknown update status");
        }

        String buildDate = StringGenerator.getDateLocalizedUTC(mActivity,
                DateFormat.LONG, update.getTimestamp());
        String buildVersion = mActivity.getString(
                R.string.list_build_version_formatted,
                Utils.getDisplayVersion(update.getVersion()),
                android.os.Build.VERSION.RELEASE);
        viewHolder.mBuildDate.setText(buildDate);
        viewHolder.mBuildVersion.setText(buildVersion);
        viewHolder.mBuildVersion.setCompoundDrawables(null, null, null, null);

        if (activeLayout) {
            handleActiveStatus(viewHolder, update);
        } else {
            handleNotActiveStatus(viewHolder, update);
        }
    }

    @Override
    public int getItemCount() {
        return mDownloadIds == null ? 0 : mDownloadIds.size();
    }

    public void setData(List<String> downloadIds) {
        mDownloadIds = downloadIds;
    }

    public void addItem(String downloadId) {
        if (mDownloadIds == null) {
            mDownloadIds = new ArrayList<>();
        }
        mDownloadIds.add(0, downloadId);
        notifyItemInserted(0);
    }

    public void notifyItemChanged(String downloadId) {
        if (mDownloadIds == null) {
            return;
        }
        int position = mDownloadIds.indexOf(downloadId);
        if (position >= 0) {
            notifyItemChanged(position);
        }
    }

    public void notifyProgressChanged(String downloadId) {
        if (mDownloadIds == null) {
            return;
        }
        int position = mDownloadIds.indexOf(downloadId);
        if (position >= 0) {
            notifyItemChanged(position, PAYLOAD_PROGRESS);
        }
    }

    public void removeItem(String downloadId) {
        if (mDownloadIds == null) {
            return;
        }
        int position = mDownloadIds.indexOf(downloadId);
        if (position < 0) {
            return;
        }
        mDownloadIds.remove(position);
        notifyItemRemoved(position);
        notifyItemRangeChanged(position, getItemCount());
    }

    private void startDownloadWithWarning(final String downloadId) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mActivity);
        boolean warn = preferences.getBoolean(Constants.PREF_METERED_NETWORK_WARNING, false);
        if (!(Utils.isNetworkMetered(mActivity) && warn)) {
            mUpdaterController.startDownload(downloadId);
            return;
        }

        View checkboxView = LayoutInflater.from(mActivity).inflate(R.layout.checkbox_view, null);
        CheckBox checkbox = checkboxView.findViewById(R.id.checkbox);
        checkbox.setText(R.string.checkbox_metered_network_warning);

        new MaterialAlertDialogBuilder(mActivity)
                .setTitle(R.string.update_over_metered_network_title)
                .setMessage(R.string.update_over_metered_network_message)
                .setView(checkboxView)
                .setPositiveButton(R.string.action_download,
                        (dialog, which) -> {
                            if (checkbox.isChecked()) {
                                preferences.edit()
                                        .putBoolean(Constants.PREF_METERED_NETWORK_WARNING, false)
                                        .apply();
                                mActivity.supportInvalidateOptionsMenu();
                            }
                            mUpdaterController.startDownload(downloadId);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void applyButtonColors(MaterialButton button, @ColorRes int bgColorRes,
            @ColorRes int textColorRes) {
        button.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(mActivity, bgColorRes)));
        button.setTextColor(ContextCompat.getColor(mActivity, textColorRes));
    }

    private void setButtonAction(MaterialButton button, Action action, final String downloadId,
            boolean enabled) {
        final View.OnClickListener clickListener;
        switch (action) {
            case DOWNLOAD:
                button.setText(R.string.action_download);
                applyButtonColors(button, R.color.blue_primary, R.color.on_blue_primary);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> startDownloadWithWarning(downloadId) : null;
                break;
            case PAUSE:
                button.setText(R.string.action_pause);
                applyButtonColors(button, R.color.blue_primary_container,
                        R.color.blue_on_primary_container);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> mUpdaterController.pauseDownload(downloadId)
                        : null;
                break;
            case RESUME: {
                button.setText(R.string.action_resume);
                applyButtonColors(button, R.color.blue_primary, R.color.on_blue_primary);
                button.setEnabled(enabled);
                UpdateInfo update = mUpdaterController.getUpdate(downloadId);
                final boolean canInstall = Utils.canInstall(update) ||
                        update.getFile().length() == update.getFileSize();
                clickListener = enabled ? view -> {
                    if (canInstall) {
                        mUpdaterController.resumeDownload(downloadId);
                    } else {
                        mActivity.showSnackbar(R.string.snack_update_not_installable,
                                Snackbar.LENGTH_LONG);
                    }
                } : null;
            }
            break;
            case INSTALL: {
                button.setText(R.string.action_install);
                applyButtonColors(button, R.color.blue_primary, R.color.on_blue_primary);
                button.setEnabled(enabled);
                UpdateInfo update = mUpdaterController.getUpdate(downloadId);
                final boolean canInstall = Utils.canInstall(update);
                clickListener = enabled ? view -> {
                    if (canInstall) {
                        AlertDialog.Builder installDialog = getInstallDialog(downloadId);
                        if (installDialog != null) {
                            installDialog.show();
                        }
                    } else {
                        mActivity.showSnackbar(R.string.snack_update_not_installable,
                                Snackbar.LENGTH_LONG);
                    }
                } : null;
            }
            break;
            case INFO: {
                button.setText(R.string.action_info);
                applyButtonColors(button, R.color.blue_primary_container,
                        R.color.blue_on_primary_container);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> showInfoDialog() : null;
            }
            break;
            case DELETE: {
                button.setText(R.string.action_delete);
                applyButtonColors(button, R.color.status_error_container, R.color.status_error);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> getDeleteDialog(downloadId).show() : null;
            }
            break;
            case CANCEL_INSTALLATION: {
                button.setText(R.string.action_cancel);
                applyButtonColors(button, R.color.status_error_container, R.color.status_error);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> getCancelInstallationDialog().show() : null;
            }
            break;
            case REBOOT: {
                button.setText(R.string.reboot);
                applyButtonColors(button, R.color.blue_primary, R.color.on_blue_primary);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> {
                    PowerManager pm = mActivity.getSystemService(PowerManager.class);
                    pm.reboot(null);
                } : null;
            }
            break;
            default:
                clickListener = null;
        }
        button.setAlpha(enabled ? 1.f : mAlphaDisabledValue);

        // Disable action mode when a button is clicked
        button.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onClick(v);
            }
        });
    }

    private boolean isBusy() {
        return mUpdaterController.hasActiveDownloads() || mUpdaterController.isVerifyingUpdate()
                || mUpdaterController.isInstallingUpdate();
    }

    private AlertDialog.Builder getDeleteDialog(final String downloadId) {
        return new MaterialAlertDialogBuilder(mActivity)
                .setTitle(R.string.confirm_delete_dialog_title)
                .setMessage(R.string.confirm_delete_dialog_message)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> {
                            mUpdaterController.pauseDownload(downloadId);
                            mUpdaterController.deleteUpdate(downloadId);
                        })
                .setNegativeButton(android.R.string.cancel, null);
    }

    private View.OnClickListener getClickListener(final UpdateInfo update,
            final boolean canDelete, View anchor) {
        return view -> startActionMode(update, canDelete, anchor);
    }

    private AlertDialog.Builder getInstallDialog(final String downloadId) {
        if (!isBatteryLevelOk()) {
            Resources resources = mActivity.getResources();
            String message = resources.getString(R.string.dialog_battery_low_message_pct,
                    resources.getInteger(R.integer.battery_ok_percentage_discharging),
                    resources.getInteger(R.integer.battery_ok_percentage_charging));
            return new MaterialAlertDialogBuilder(mActivity)
                    .setTitle(R.string.dialog_battery_low_title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null);
        }
        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        int resId;
        try {
            if (Utils.isABUpdate(update.getFile())) {
                resId = R.string.apply_update_dialog_message_ab;
            } else {
                resId = R.string.apply_update_dialog_message;
            }
        } catch (IOException e) {
            Log.e(TAG, "Could not determine the type of the update");
            return null;
        }

        String buildDate = StringGenerator.getDateLocalizedUTC(mActivity,
                DateFormat.MEDIUM, update.getTimestamp());
        String buildInfoText = mActivity.getString(R.string.list_build_version_date,
                update.getVersion(), buildDate);
        return new MaterialAlertDialogBuilder(mActivity)
                .setTitle(R.string.apply_update_dialog_title)
                .setMessage(mActivity.getString(resId, buildInfoText,
                        mActivity.getString(android.R.string.ok)))
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> {
                            Utils.triggerUpdate(mActivity, downloadId);
                            maybeShowInfoDialog();
                        })
                .setNegativeButton(android.R.string.cancel, null);
    }

    private AlertDialog.Builder getCancelInstallationDialog() {
        return new MaterialAlertDialogBuilder(mActivity)
                .setMessage(R.string.cancel_installation_dialog_message)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> {
                            Intent intent = new Intent(mActivity, UpdaterService.class);
                            intent.setAction(UpdaterService.ACTION_INSTALL_STOP);
                            mActivity.startService(intent);
                        })
                .setNegativeButton(android.R.string.cancel, null);
    }

    private void maybeShowInfoDialog() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mActivity);
        boolean alreadySeen = preferences.getBoolean(Constants.HAS_SEEN_INFO_DIALOG, false);
        if (alreadySeen) {
            return;
        }
        new MaterialAlertDialogBuilder(mActivity)
                .setTitle(R.string.info_dialog_title)
                .setMessage(R.string.info_dialog_message)
                .setPositiveButton(R.string.info_dialog_ok, (dialog, which) -> preferences.edit()
                        .putBoolean(Constants.HAS_SEEN_INFO_DIALOG, true)
                        .apply())
                .show();
    }

    @SuppressLint("RestrictedApi")
    private void startActionMode(final UpdateInfo update, final boolean canDelete, View anchor) {
        mSelectedDownload = update.getDownloadId();
        notifyItemChanged(update.getDownloadId());

        ContextThemeWrapper wrapper = new ContextThemeWrapper(mActivity,
                R.style.AppTheme_PopupMenuOverlapAnchor);
        PopupMenu popupMenu = new PopupMenu(wrapper, anchor, Gravity.NO_GRAVITY,
                R.attr.actionOverflowMenuStyle, 0);
        popupMenu.inflate(R.menu.menu_action_mode);

        boolean shouldShowDelete = canDelete;
        boolean isVerified = update.getPersistentStatus() == UpdateStatus.Persistent.VERIFIED;
        if (isVerified && !Utils.canInstall(update) && !update.getAvailableOnline()) {
            shouldShowDelete = false;
        }
        MenuBuilder menu = (MenuBuilder) popupMenu.getMenu();
        menu.findItem(R.id.menu_delete_action).setVisible(shouldShowDelete);
        menu.findItem(R.id.menu_copy_url).setVisible(update.getAvailableOnline());
        menu.findItem(R.id.menu_export_update).setVisible(isVerified);

        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.menu_delete_action) {
                getDeleteDialog(update.getDownloadId()).show();
                return true;
            } else if (itemId == R.id.menu_copy_url) {
                Utils.addToClipboard(mActivity,
                        mActivity.getString(R.string.label_download_url),
                        update.getDownloadUrl(),
                        mActivity.getString(R.string.toast_download_url_copied));
                return true;
            } else if (itemId == R.id.menu_export_update) {
                if (mActivity != null) {
                    mActivity.exportUpdate(update);
                }
                return true;
            }
            return false;
        });

        MenuPopupHelper helper = new MenuPopupHelper(wrapper, menu, anchor);
        helper.setOnDismissListener(() -> {
            String prevSelected = mSelectedDownload;
            mSelectedDownload = null;
            if (prevSelected != null) {
                notifyItemChanged(prevSelected);
            }
        });
        helper.show();
    }

    private void showInfoDialog() {
        if (infoDialog != null && infoDialog.isShowing()) {
            infoDialog.dismiss();
        }
        infoDialog = new MaterialAlertDialogBuilder(mActivity)
                .setTitle(R.string.blocked_update_dialog_title)
                .setPositiveButton(android.R.string.ok, null)
                .setMessage(R.string.blocked_update_dialog_message_custom)
                .show();
        TextView textView = infoDialog.findViewById(android.R.id.message);
        if (textView != null) {
            textView.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }

    private boolean isBatteryLevelOk() {
        Intent intent = mActivity.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent == null || !intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) {
            return true;
        }
        int percent = Math.round(100.f * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) /
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int required = (plugged & BATTERY_PLUGGED_ANY) != 0 ?
                mActivity.getResources().getInteger(R.integer.battery_ok_percentage_charging) :
                mActivity.getResources().getInteger(R.integer.battery_ok_percentage_discharging);
        return percent >= required;
    }
}
