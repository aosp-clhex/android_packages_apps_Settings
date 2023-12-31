/*
 * Copyright (C) 2018 The Android Open Source Project
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

/*
 * Changes from Qualcomm Innovation Center are provided under the following license:
 * Copyright (c) 2023 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.android.settings.network.telephony;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.settings.SettingsEnums;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.TelephonyManager;

import com.android.settings.R;
import com.android.settings.core.instrumentation.InstrumentedDialogFragment;

/**
 * A dialog fragment that asks the user if they are sure they want to turn on data roaming
 * to avoid accidental charges.
 */
public class RoamingDialogFragment extends InstrumentedDialogFragment implements OnClickListener {

    private static final String PREF_TITLE = "pref_title";
    private static final String SUB_ID_KEY = "sub_id_key";
    private static final String DIALOG_TYPE = "dialog_type";
    private static final String ARG_CIWLAN_MODE_SUPPORTED = "ciwlan_mode_supported";

    public static final int TYPE_ENABLE_DIALOG = 0;
    public static final int TYPE_DISABLE_CIWLAN_DIALOG = 1;

    private CarrierConfigManager mCarrierConfigManager;
    private String mPrefTitle;
    private int mType;
    private int mSubId;
    private boolean mCiwlanModeSupported;

    public static RoamingDialogFragment newInstance(String prefTitle, int type, int subId,
            boolean ciwlanModeSupported) {
        final RoamingDialogFragment dialogFragment = new RoamingDialogFragment();
        final Bundle args = new Bundle();
        args.putString(PREF_TITLE, prefTitle);
        args.putInt(DIALOG_TYPE, type);
        args.putInt(SUB_ID_KEY, subId);
        args.putBoolean(ARG_CIWLAN_MODE_SUPPORTED, ciwlanModeSupported);
        dialogFragment.setArguments(args);

        return dialogFragment;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        final Bundle args = getArguments();
        mSubId = args.getInt(SUB_ID_KEY);
        mCarrierConfigManager = context.getSystemService(CarrierConfigManager.class);
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Bundle bundle = getArguments();
        final Context context = getContext();
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);

        mPrefTitle = bundle.getString(PREF_TITLE).toLowerCase();
        mType = bundle.getInt(DIALOG_TYPE);
        mCiwlanModeSupported = bundle.getBoolean(ARG_CIWLAN_MODE_SUPPORTED);

        switch (mType) {
            case TYPE_ENABLE_DIALOG:
                int message = R.string.roaming_warning;
                final PersistableBundle carrierConfig = mCarrierConfigManager.getConfigForSubId(
                        mSubId);
                if (carrierConfig != null && carrierConfig.getBoolean(
                        CarrierConfigManager.KEY_CHECK_PRICING_WITH_CARRIER_FOR_DATA_ROAMING_BOOL))
                {
                    message = R.string.roaming_check_price_warning;
                }
                builder.setMessage(getResources().getString(message))
                       .setTitle(getResources().getString(R.string.roaming_alert_title));
                break;
            case TYPE_DISABLE_CIWLAN_DIALOG:
                String msg = mCiwlanModeSupported ?
                        context.getString(
                                R.string.toggle_disable_ciwlan_call_will_drop_dialog_body,
                                mPrefTitle) :
                        context.getString(
                                R.string.toggle_disable_ciwlan_call_might_drop_dialog_body,
                                mPrefTitle);
                builder.setTitle(context.getString(
                                R.string.toggle_disable_ciwlan_call_dialog_title, mPrefTitle))
                       .setMessage(msg);
                break;
        }
        builder.setIconAttribute(android.R.attr.alertDialogIcon)
               .setPositiveButton(android.R.string.ok, this)
               .setNegativeButton(android.R.string.cancel, this);
        return builder.create();
    }

    @Override
    public int getMetricsCategory() {
        return SettingsEnums.MOBILE_ROAMING_DIALOG;
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        final TelephonyManager telephonyManager =
                getContext().getSystemService(TelephonyManager.class)
                .createForSubscriptionId(mSubId);
        if (telephonyManager == null) {
            return;
        }
        switch (mType) {
            case TYPE_ENABLE_DIALOG:
                // let the host know that the positive button has been clicked
                if (which == dialog.BUTTON_POSITIVE) {
                    telephonyManager.setDataRoamingEnabled(true);
                }
                break;
            case TYPE_DISABLE_CIWLAN_DIALOG:
                if (which == dialog.BUTTON_POSITIVE) {
                    telephonyManager.setDataRoamingEnabled(false);
                }
                break;
        }
    }
}
