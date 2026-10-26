package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract.WatchNextPrograms;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Continue tab: what the apps put in Android TV's "Watch Next" (YouTube, Kinopoisk, IVI…), newest first. TvProvider
 * keeps it; other apps' rows need READ_TV_LISTINGS (HomeMode.tvListings). Whether an app writes there with the stock
 * home screen off is up to the app.
 */
final class WatchNext {
    static final String KIND = "continue";
    private static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    private static final String[] COLUMNS = {
        WatchNextPrograms._ID, WatchNextPrograms.COLUMN_PACKAGE_NAME, WatchNextPrograms.COLUMN_TITLE,
        WatchNextPrograms.COLUMN_EPISODE_TITLE, WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
        WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, WatchNextPrograms.COLUMN_THUMBNAIL_URI,
        WatchNextPrograms.COLUMN_POSTER_ART_URI, WatchNextPrograms.COLUMN_INTENT_URI,
        WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, WatchNextPrograms.COLUMN_DURATION_MILLIS,
        WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, WatchNextPrograms.COLUMN_BROWSABLE,
    };

    private WatchNext() {}

    static boolean allowed(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Off the UI thread: up to `max`, newest first, one per title, only from apps the TV still has. */
    static List<Recs.Pick> load(Context c, int max) {
        List<Recs.Pick> out = new ArrayList<>();
        if (!allowed(c)) return out;
        PackageManager pm = c.getPackageManager();
        List<long[]> order = new ArrayList<>();
        List<Recs.Pick> all = new ArrayList<>();
        try (Cursor q = c.getContentResolver().query(WatchNextPrograms.CONTENT_URI, COLUMNS, null, null, null)) {
            if (q == null) return out;
            while (q.moveToNext()) {
                if (q.getInt(12) == 0) continue;   // not browsable: the app hid it
                String pkg = q.getString(1), title = q.getString(2), intent = q.getString(8);
                if (pkg == null || title == null || intent == null || HomeApps.launchIntent(pm, pkg) == null) continue;
                String image = q.getString(6) != null ? q.getString(6) : q.getString(7);
                if (image == null) continue;
                Recs.Pick p = new Recs.Pick();
                p.id = (int) q.getLong(0);
                p.kind = KIND;
                p.title = title;
                p.pkg = pkg;
                p.link = intent;
                p.backdrop = image;
                p.external = true;
                p.detailed = true;
                List<String> parts = new ArrayList<>();
                String season = q.getString(4), episode = q.getString(5), name = q.getString(3);
                if (season != null && episode != null) parts.add(Ui.trf("S{s} E{e}", "s", season, "e", episode));
                else if (name != null) parts.add(name);
                long position = q.getLong(9), duration = q.getLong(10);
                if (duration > 0 && position > 0 && position < duration) {
                    parts.add(Ui.trf("{n} min left", "n", Math.max(1, (duration - position) / 60_000)));
                }
                parts.add(label(pm, pkg));
                p.meta = String.join(" · ", parts);
                all.add(p);
                order.add(new long[] {q.getLong(11), all.size() - 1});
            }
        } catch (RuntimeException e) {   // SecurityException where the permission didn't take, a provider error
            AppLog.w("watch next: " + e.getMessage());
            return out;
        }
        order.sort((a, b) -> Long.compare(b[0], a[0]));
        Set<String> seen = new HashSet<>();
        for (long[] o : order) {
            Recs.Pick p = all.get((int) o[1]);
            if (seen.add(p.pkg + "|" + p.title) && out.size() < max) out.add(p);
        }
        return out;
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
