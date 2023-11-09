package com.android.settings.custom;

import android.app.settings.SettingsEnums;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings.Global;

import androidx.annotation.VisibleForTesting;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.preference.Preference;

import com.android.settings.R;
import com.android.settings.SettingsActivity;
import com.android.settings.Utils;
import com.android.settings.overlay.FeatureFactory;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;
import com.android.settingslib.widget.LayoutPreference;

import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import java.util.List;

@SearchIndexable
public class BatterySettingExtras extends SettingsPreferenceFragment {

    private static final String TAG = "BatterySettingExtras";

    @Override
    public int getMetricsCategory() {
         return METRICS_CATEGORY_UNKNOWN;
    }

     protected String getLogTag() {
         return TAG;
     }

     @Override
     protected int getPreferenceScreenResId() {
         return R.xml.battery_setting_extras;
     }

     public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
             new BaseSearchIndexProvider(R.xml.battery_setting_extras);
}