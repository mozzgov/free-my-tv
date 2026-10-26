package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** OK held on a pick: follow its new episodes (shows), or look for it in the TV's other film apps. */
final class PickMenu {
    /** App, package, and its search: "search" (ACTION_SEARCH) or a URL its VIEW filter takes (the title appended). */
    private static final String[][] SEARCH = {
        {"Кинопоиск", "ru.kinopoisk.tv", "search"},
        {"Filmix", "net.filmix.filmix", "search"},
        {"HDRezka", "com.falcofemoralis.hdrezkaapp", "https://hdrzk.org/search/?do=search&subaction=search&q="},
    };

    private PickMenu() {}

    static boolean hasItems(Context c, Recs.Pick p) {
        if ("tv".equals(p.kind)) return true;
        PackageManager pm = c.getPackageManager();
        for (String[] s : SEARCH) if (HomeApps.launchIntent(pm, s[1]) != null) return true;
        return false;
    }

    static void show(Activity a, Recs.Pick p, Consumer<String> toast) {
        PackageManager pm = a.getPackageManager();
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if ("tv".equals(p.kind)) {
            boolean on = Episodes.following(a, p.id);
            labels.add(on ? Ui.tr("Stop following new episodes") : Ui.tr("Follow new episodes"));
            actions.add(() -> {
                boolean now = Episodes.toggle(a, p);
                toast.accept(now ? Ui.trf("New episodes of {show} will show here", "show", p.title)
                        : !on && Episodes.full(a) ? Ui.tr("You follow too many shows")
                        : Ui.trf("Stopped following {show}", "show", p.title));
            });
        }
        for (String[] s : SEARCH) {
            if (HomeApps.launchIntent(pm, s[1]) == null) continue;
            labels.add(Ui.trf("Search in {app}", "app", s[0]));
            actions.add(() -> search(a, p.title, s, toast));
        }
        if (labels.isEmpty()) return;
        String next = "tv".equals(p.kind) ? Episodes.next(a, p.id) : "";
        AlertDialog.Builder b = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(next.isEmpty() ? p.title : p.title + "\n" + next)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run());
        b.show();
    }

    private static void search(Activity a, String title, String[] app, Consumer<String> toast) {
        Intent i;
        try {
            i = app[2].equals("search")
                    ? new Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, title).putExtra("query", title)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(app[2] + URLEncoder.encode(title, "UTF-8")));
        } catch (java.io.UnsupportedEncodingException e) {
            return;
        }
        i.setPackage(app[1]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            a.startActivity(i);
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("search in " + app[1] + ": " + e.getMessage());
        }
        Intent launch = HomeApps.launchIntent(a.getPackageManager(), app[1]);
        if (launch == null) return;
        a.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        toast.accept(Ui.trf("Search for {title} there", "title", title));
    }
}
