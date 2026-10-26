package io.freemytv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

import java.util.Calendar;

/**
 * Home Settings' alarm: every day at the time set, the TV wakes up (the Wake key through its own ADB) and opens an app
 * or the home screen. AlarmManager's alarm clock wakes the TV's processor in standby; a TV that cuts its power
 * when turned off (rather than sleeping) can't be woken by anything on it.
 */
final class Alarm {
    static final String ACTION = "io.freemytv.ALARM";

    private Alarm() {}

    /** Sets the next one from Prefs, or cancels it. Any process. */
    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 7, new Intent(ACTION).setClass(c, HomeReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int minute = Prefs.alarmMinute();
        if (minute < 0) {
            am.cancel(pi);
            return;
        }
        long at = next(minute, System.currentTimeMillis());
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);   // within minutes of the time
        } else {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, null), pi);
        }
        AppLog.w("alarm at " + new java.util.Date(at));
    }

    static long next(int minuteOfDay, long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        cal.set(Calendar.MINUTE, minuteOfDay % 60);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= now + 30_000) cal.add(Calendar.DAY_OF_YEAR, 1);
        return cal.getTimeInMillis();
    }

    /** Main process, off the UI thread: wake the screen, open what was picked, set tomorrow's. */
    static void fire(Context c) {
        Prefs.reload();
        schedule(c);
        if (Prefs.alarmMinute() < 0) return;
        String pkg = Prefs.alarmApp();
        try (Adb adb = Adb.connect(c, 10_000, null)) {
            adb.shell("input keyevent KEYCODE_WAKEUP");
            SystemClock.sleep(4000);   // the panel and HDMI-CEC come up first
            Intent launch = pkg.isEmpty() ? null : HomeApps.launchIntent(c.getPackageManager(), pkg);
            ComponentName target = launch != null ? launch.getComponent()
                    : new ComponentName(c, HomeActivity.class);
            if (target != null) adb.shell("am start -n " + target.flattenToShortString());
            AppLog.w("alarm: woke the TV, opened " + (target == null ? "nothing" : target.flattenToShortString()));
        } catch (Exception e) {
            AppLog.w("alarm: " + e.getMessage());
        }
    }
}
