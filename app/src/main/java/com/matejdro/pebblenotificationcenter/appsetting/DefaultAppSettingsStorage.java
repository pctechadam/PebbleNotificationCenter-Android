package com.matejdro.pebblenotificationcenter.appsetting;

import android.content.SharedPreferences;
import android.support.annotation.Nullable;

import com.matejdro.pebblecommons.util.PreferencesUtil;
import com.matejdro.pebblenotificationcenter.PebbleNotificationCenter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Created by Matej on 16.9.2014.
 */
public class DefaultAppSettingsStorage extends AbsAppSettingStorage
{
    private SharedPreferences preferences;
    private SharedPreferences.Editor editor;

    public DefaultAppSettingsStorage(SharedPreferences preferences, SharedPreferences.Editor editor)
    {
        this.preferences = preferences;
        this.editor = editor;
    }

    @Nullable
    @Override
    public String getStringByKey(String key)
    {
        return preferences.getString(key, null);
    }

    @Override
    public void setStringByKey(String key, String value)
    {
        editor.putString(key, value);
        editor.apply();
    }

    public String getString(AppSetting setting)
    {
        return preferences.getString(setting.getKey(), (String) setting.getDefault());
    }

    public boolean getBoolean(AppSetting setting)
    {
        return preferences.getBoolean(setting.getKey(), (Boolean) setting.getDefault());
    }

    public int getInt(AppSetting setting)
    {
        try
        {
            return preferences.getInt(setting.getKey(), (Integer) setting.getDefault());
        }
        catch (ClassCastException e)
        {
            //Some settings were stored as string when in global settings. Handle this here.
            return Integer.parseInt(preferences.getString(setting.getKey(), Integer.toString((Integer) setting.getDefault())));
        }
    }

    public List<String> getStringList(AppSetting setting)
    {
        List<String> list = new ArrayList<String>();
        PreferencesUtil.loadCollection(preferences, list, setting.getKey());

        return list;
    }

    public void setString(AppSetting setting, String val)
    {
        editor.putString(setting.getKey(), val);
        editor.apply();
    }

    public void setBoolean(AppSetting setting, boolean val)
    {
        editor.putBoolean(setting.getKey(), val);
        editor.apply();
    }

    public void setInt(AppSetting setting, int val)
    {
        editor.putInt(setting.getKey(), val);
        editor.apply();
    }

    public void setStringList(AppSetting setting, Collection<String> val)
    {
        PreferencesUtil.saveCollection(editor, val, setting.getKey());
    }

    @Override
    public void deleteSetting(AppSetting setting)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isAppChecked()
    {
        return true;
    }

    @Override
    public void setAppChecked(boolean checked)
    {

    }

    @Override
    public boolean canAppSendNotifications()
    {
        return false;
    }

    public boolean canAppSendNotifications(String pkg)
    {
        //Notification Center should always be enabled as some features depend on it. I also don't see any reason why would somebody disable it.
        if (pkg.equals(PebbleNotificationCenter.PACKAGE))
            return true;

        boolean includingMode = preferences.getBoolean(PebbleNotificationCenter.APP_INCLUSION_MODE, false);
        return includingMode == isAppChecked(pkg);
    }

    public boolean isAppChecked(String pkg)
    {
        return preferences.getBoolean("appChecked_".concat(pkg), false);
    }

    public void setAppChecked(String pkg, boolean checked)
    {
        if (checked)
            editor.putBoolean("appChecked_".concat(pkg), true);
        else
            editor.remove("appChecked_".concat(pkg));

        editor.apply();
    }
}
