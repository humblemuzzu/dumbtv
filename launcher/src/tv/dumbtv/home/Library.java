package tv.dumbtv.home;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The titles the hero banner rotates through. They come from {@code library/library.conf}
 * next to home.conf, pushed over adb with the artwork beside it, one title per line:
 *
 * <pre>
 * image | title | package | deep link (optional) | key=value extras (optional)
 * stranger-things.jpg | Stranger Things | com.netflix.ninja | https://www.netflix.com/title/80057281 | source=30
 * </pre>
 *
 * The launcher has no internet permission, so nothing here is downloaded.
 */
final class Library {
    static final String DIR = "library";
    static final String FILE = "library.conf";

    static final class Entry {
        final String image, title, pkg, uri;
        final Map<String, String> extras;
        File file;
        CharSequence appLabel;

        Entry(String image, String title, String pkg, String uri, Map<String, String> extras) {
            this.image = image;
            this.title = title;
            this.pkg = pkg;
            this.uri = uri;
            this.extras = extras;
        }

        /** Same title, same picture on disk: nothing to reload. */
        String key() {
            return image + "|" + title + "|" + pkg + "|" + uri + "|" + extras
                    + (file == null ? "" : "|" + file.lastModified());
        }
    }

    private Library() {}

    /** Pure parsing, so it can be checked on a computer without Android. */
    static List<Entry> parse(List<String> lines) {
        List<Entry> out = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\\|", -1);
            if (f.length < 3) continue;
            String image = f[0].trim(), title = f[1].trim(), pkg = f[2].trim();
            if (image.isEmpty() || pkg.isEmpty() || image.contains("/") || image.contains("..")) continue;
            String uri = f.length > 3 ? f[3].trim() : "";
            Map<String, String> extras = new LinkedHashMap<>();
            if (f.length > 4) {
                for (String kv : f[4].split(",")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) extras.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                }
            }
            out.add(new Entry(image, title.isEmpty() ? image : title, pkg, uri, extras));
        }
        return out;
    }

    /** The titles whose picture exists and whose app is installed and enabled. */
    static List<Entry> load(Context c, Config cfg) {
        List<Entry> out = new ArrayList<>();
        File dir = cfg.libraryDir();
        if (dir == null) return out;
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(new File(dir, FILE)), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) lines.add(line);
        } catch (IOException e) {
            return out;
        }
        PackageManager pm = c.getPackageManager();
        for (Entry e : parse(lines)) {
            File f = new File(dir, e.image);
            if (!f.isFile()) continue;
            try {
                if (!pm.getApplicationInfo(e.pkg, 0).enabled) continue;
                e.appLabel = pm.getApplicationInfo(e.pkg, 0).loadLabel(pm);
            } catch (PackageManager.NameNotFoundException missing) {
                continue;
            }
            e.file = f;
            out.add(e);
        }
        return out;
    }

    /**
     * Opens the title in its app. The deep link goes to that app only; when the app does not
     * answer it, the app opens on its own home screen instead.
     */
    static void open(Context c, Entry e) {
        PackageManager pm = c.getPackageManager();
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP;
        if (!e.uri.isEmpty()) {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(e.uri)).setPackage(e.pkg);
            for (Map.Entry<String, String> x : e.extras.entrySet()) view.putExtra(x.getKey(), x.getValue());
            if (view.resolveActivity(pm) != null) {
                try {
                    c.startActivity(view.addFlags(flags));
                    Recent.opened(c, e.pkg);
                    return;
                } catch (ActivityNotFoundException | SecurityException ignored) {
                    // falls back to the app itself
                }
            }
        }
        Intent app = pm.getLeanbackLaunchIntentForPackage(e.pkg);
        if (app == null) app = pm.getLaunchIntentForPackage(e.pkg);
        if (app == null) return;
        try {
            c.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            Recent.opened(c, e.pkg);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // nothing else to try
        }
    }
}
