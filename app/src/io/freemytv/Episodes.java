package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shows you follow (hold OK on a pick): every 6 hours TMDB says each one's last and next episode, and the home screen
 * says once when a new one came out today or yesterday. Only in the :home process; its own file, not Prefs.
 */
final class Episodes {
    private static final long EVERY = 6 * 3600_000L;
    private static final int MAX = 30;

    private Episodes() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("episodes", Context.MODE_PRIVATE);
    }

    static boolean following(Context c, int id) {
        return sp(c).contains("show" + id);
    }

    /** Follows or stops; true if now followed. False also when the list is full. */
    static boolean toggle(Context c, Recs.Pick p) {
        SharedPreferences s = sp(c);
        String key = "show" + p.id;
        if (s.contains(key)) {
            s.edit().remove(key).remove("info" + p.id).remove("told" + p.id).apply();
            return false;
        }
        int shows = 0;
        for (String k : s.getAll().keySet()) if (k.startsWith("show")) shows++;
        if (shows >= MAX) return false;
        s.edit().putString(key, p.title).putLong("at", 0).apply();   // checked at the next resume
        return true;
    }

    static boolean full(Context c) {
        int shows = 0;
        for (String k : sp(c).getAll().keySet()) if (k.startsWith("show")) shows++;
        return shows >= MAX;
    }

    /**
     * Off the UI thread: TMDB again if 6 hours went by, then the new episodes not told yet, as lines to show
     * ("Episode 5 of Severance is out", once each).
     */
    static List<String> check(Context c) {
        List<String> out = new ArrayList<>();
        SharedPreferences s = sp(c);
        Map<String, ?> all = s.getAll();
        long now = System.currentTimeMillis();
        boolean fetch = now - s.getLong("at", 0) >= EVERY;
        SharedPreferences.Editor edit = s.edit();
        if (fetch) edit.putLong("at", now);
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        String today = day.format(new Date(now)), yesterday = day.format(new Date(now - 24 * 3600_000L));
        for (Map.Entry<String, ?> e : all.entrySet()) {
            if (!e.getKey().startsWith("show")) continue;
            String id = e.getKey().substring(4);
            String title = String.valueOf(e.getValue());
            String info = s.getString("info" + id, "");
            if (fetch) {
                try {
                    JSONObject show = Recs.get(c).tmdb("/tv/" + id + "?language=" + Recs.lang());
                    title = show.optString("name", title);
                    JSONObject keep = new JSONObject();
                    if (show.optJSONObject("last_episode_to_air") != null) keep.put("last", show.getJSONObject("last_episode_to_air"));
                    if (show.optJSONObject("next_episode_to_air") != null) keep.put("next", show.getJSONObject("next_episode_to_air"));
                    info = keep.toString();
                    edit.putString("show" + id, title).putString("info" + id, info);
                } catch (Exception ex) {
                    AppLog.w("episodes: " + id + ": " + ex.getMessage());
                }
            }
            JSONObject ep = aired(info, today);
            if (ep == null) continue;
            String date = ep.optString("air_date", "");
            if (!date.equals(today) && !date.equals(yesterday)) continue;
            String tag = ep.optInt("season_number") + "x" + ep.optInt("episode_number");
            if (tag.equals(s.getString("told" + id, ""))) continue;
            edit.putString("told" + id, tag);
            out.add(Ui.trf("New episode of {show}: season {s}, episode {e}", "show", title,
                    "s", ep.optInt("season_number"), "e", ep.optInt("episode_number")));
        }
        edit.apply();
        return out;
    }

    /** The newest episode out by today: TMDB moves "next" to "last" a while after it airs. */
    private static JSONObject aired(String info, String today) {
        if (info.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(info);
            JSONObject next = o.optJSONObject("next");
            if (next != null && !next.optString("air_date", "9").isEmpty() && next.optString("air_date", "9").compareTo(today) <= 0) return next;
            return o.optJSONObject("last");
        } catch (Exception e) {
            return null;
        }
    }

    /** "Next episode: Wednesday, November 12" for the pick menu, or empty. */
    static String next(Context c, int id) {
        try {
            JSONObject next = new JSONObject(sp(c).getString("info" + id, "{}")).optJSONObject("next");
            if (next == null || next.optString("air_date", "").isEmpty()) return "";
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(next.getString("air_date"));
            return Ui.trf("Next episode: {date}", "date", Ui.datePattern("format|EEEE, MMMM d").format(d.getTime()));
        } catch (Exception e) {
            return "";
        }
    }
}
