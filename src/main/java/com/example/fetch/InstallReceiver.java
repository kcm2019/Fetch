package com.example.fetch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Auto-deletes the downloaded APK after a successful install, when the
 * user opted in ("Delete after install"). Only acts on the file Fetch
 * itself launched the installer for, and only within 10 minutes of that
 * launch, so installs from other apps are never touched.
 */
public class InstallReceiver extends BroadcastReceiver {

    private static final long WINDOW_MS = 10 * 60 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_PACKAGE_ADDED.equals(action)
                && !Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        SharedPreferences p = context.getSharedPreferences(
                MainActivity.PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(MainActivity.KEY_DELETE_AFTER, false)) return;
        String name = p.getString(MainActivity.KEY_LAST_FILE, null);
        long launched = p.getLong(MainActivity.KEY_LAUNCH_TIME, 0);
        if (name == null || System.currentTimeMillis() - launched > WINDOW_MS) return;
        File f = new File(MainActivity.downloadDir(), name);
        if (f.isFile()) {
            f.delete();
        }
        p.edit().remove(MainActivity.KEY_LAST_FILE)
                .remove(MainActivity.KEY_LAUNCH_TIME).apply();
    }
}
