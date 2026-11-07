package tv.dumbtv.home;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An installed icon pack in the ADW/Nova format most packs use (Blueprint, Projectivy and many
 * phone packs): an appfilter maps launcher activities to drawables inside the pack. Only the
 * pack's resources are read, so none of its code runs, and a pack switched off with
 * {@code pm disable-user} still works.
 */
final class IconPack {
    static final String KEY = "icon_pack";
    static final String OFF = "off";
    /** Per-app choice from the icon picker: {@code icon.<app>=<pack>/<drawable>}, or {@link #OWN}. */
    static final String CHOSEN = "icon.";
    /** Picked "App's own picture": no pack replaces this app's art. */
    static final String OWN = "own";
    /** What launchers look for; every ADW-format pack answers at least one of them. */
    private static final String[] ACTIONS = {"org.adw.launcher.THEMES", "com.novalauncher.THEME"};
    private static final int ALL = PackageManager.MATCH_DISABLED_COMPONENTS
            | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;

    final String pkg;
    private final Resources res;
    private final Map<String, String> byActivity = new HashMap<>();
    private final Map<String, String> byPackage = new HashMap<>();
    /** Every drawable the appfilter or the pack's own icon list names, in file order. */
    private final Set<String> listed = new LinkedHashSet<>();
    private List<String> names;

    private IconPack(String pkg, Resources res) {
        this.pkg = pkg;
        this.res = res;
    }

    /**
     * The installed icon packs, switched-off ones included, as package -> name. A package counts
     * when it answers a launcher's theme intent and carries an appfilter: the intent alone is
     * also declared by themes and wallpaper apps that have no icons.
     */
    static Map<String, String> installed(PackageManager pm) {
        Map<String, String> packs = new LinkedHashMap<>();
        for (String action : ACTIONS) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(action), ALL)) {
                ApplicationInfo app = ri.activityInfo.applicationInfo;
                if (packs.containsKey(app.packageName) || !hasAppfilter(pm, app)) continue;
                packs.put(app.packageName, app.loadLabel(pm).toString());
            }
        }
        return packs;
    }

    /**
     * Off, then every installed pack by name. A chosen pack that was uninstalled is simply not
     * offered: the row shows Off and the tiles their own art, while the setting stays, so the
     * pack is back as it was once installed again. With no pack installed, Off reads
     * {@code none}, so the row says why there is nothing to pick. Both labels arrive in the TV's
     * language; their strings are not named choice_icon_pack_*, so Text cannot swap one for the
     * other.
     */
    static Config.Choice choice(Map<String, String> installed, String off, String none) {
        Map<String, String> packs = new LinkedHashMap<>();
        packs.put(OFF, installed.isEmpty() ? none : off);
        packs.putAll(installed);
        return new Config.Choice(KEY, "Icon pack", OFF,
                packs.keySet().toArray(new String[0]), packs.values().toArray(new String[0]));
    }

    /** Reads the pack's appfilter; null when the package is missing or is not an icon pack. Slow. */
    static IconPack load(PackageManager pm, String pkg) {
        if (pkg == null || OFF.equals(pkg)) return null;
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, ALL);
            IconPack pack = new IconPack(pkg, pm.getResourcesForApplication(info));
            return pack.read() ? pack : null;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Keeper.note("icon pack " + pkg + ": " + e);
            return null;
        }
    }

    private static boolean hasAppfilter(PackageManager pm, ApplicationInfo app) {
        try {
            Resources r = pm.getResourcesForApplication(app);
            if (r.getIdentifier("appfilter", "xml", app.packageName) != 0) return true;
            r.getAssets().open("appfilter.xml").close();
            return true;
        } catch (PackageManager.NameNotFoundException | IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The pack's picture for this app: by its launcher activity, then by package, then by a
     * drawable named after the app ("YouTube" -> youtube), which catches the many TV packs
     * mapped for Google TV package names that Fire TV apps do not share. Null when none fits.
     */
    Drawable drawable(Item it) {
        ComponentName c = it.intent.getComponent();
        String name = c == null ? null : byActivity.get(c.getPackageName() + "/" + c.getClassName());
        if (name == null) name = byPackage.get(it.id);
        int id = name == null ? 0 : res.getIdentifier(name, "drawable", pkg);
        if (id == 0) id = named(it.label.toString());
        return id == 0 ? null : load(id);
    }

    /** One of {@link #names()} by name, for the icon picker and the choices it saved. */
    Drawable picture(String name) {
        int id = res.getIdentifier(name, "drawable", pkg);
        return id == 0 ? null : load(id);
    }

    /** Every picture the pack offers, sorted by name. Slow the first time. */
    synchronized List<String> names() {
        if (names == null) {
            names = new ArrayList<>();
            for (String n : listed) {
                if (res.getIdentifier(n, "drawable", pkg) != 0) names.add(n);
            }
            Collections.sort(names);
        }
        return names;
    }

    private Drawable load(int id) {
        try {
            return res.getDrawable(id, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int named(String label) {
        for (String name : IconMatch.names(label)) {
            int id = name.isEmpty() ? 0 : res.getIdentifier(name, "drawable", pkg);
            if (id != 0) return id;
        }
        return 0;
    }

    /**
     * res/xml/appfilter, or assets/appfilter.xml in older packs, then the pack's own icon list
     * (res/xml/drawable), which holds pictures no app is mapped to. Many packs leave that list as
     * Blueprint's template, so its names only count where the drawable exists.
     */
    private boolean read() {
        try {
            if (!parse("appfilter", true)) return false;
        } catch (Exception e) {
            Keeper.note("icon pack " + pkg + ": no appfilter (" + e + ")");
            return false;
        }
        try {
            parse("drawable", false);
        } catch (Exception ignored) {
            // optional
        }
        return !byPackage.isEmpty();
    }

    private boolean parse(String file, boolean appfilter) throws Exception {
        int id = res.getIdentifier(file, "xml", pkg);
        if (id != 0) {
            XmlResourceParser p = res.getXml(id);
            try {
                items(p, appfilter);
            } finally {
                p.close();
            }
            return true;
        }
        if (!appfilter) return false;
        InputStream in = res.getAssets().open(file + ".xml");
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, "UTF-8");
            items(p, true);
        } finally {
            in.close();
        }
        return true;
    }

    private void items(XmlPullParser p, boolean appfilter) throws Exception {
        for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e != XmlPullParser.START_TAG || !"item".equals(p.getName())) continue;
            String drawable = p.getAttributeValue(null, "drawable");
            if (drawable == null || drawable.isEmpty()) continue;
            listed.add(drawable);
            if (!appfilter) continue;
            String[] app = IconMatch.component(p.getAttributeValue(null, "component"));
            if (app == null) continue;
            byActivity.put(app[0] + "/" + app[1], drawable);
            if (!byPackage.containsKey(app[0])) byPackage.put(app[0], drawable);
        }
    }

    /** For the log: how many apps the pack maps. */
    int size() {
        return byPackage.size();
    }
}
