package tv.dumbtv.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Where tiles come from, and which of them the user wants on the home screen. */
final class Catalog {
    static final String APPS = "apps", ALL_APPS = "allapps", INPUTS = "inputs";
    static final String LIVE_TV = "com.amazon.tv.livetv";
    private static final String CHANNEL = "vnd.android.cursor.item/channel";

    private Catalog() {}

    static boolean isApp(String kind) {
        return APPS.equals(kind) || ALL_APPS.equals(kind);
    }

    /** Every launchable app, TV apps first; drawables are left for {@link #art}. */
    static List<Item> apps(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, Item> found = new LinkedHashMap<>();
        collect(pm, found, Intent.CATEGORY_LEANBACK_LAUNCHER, true, c.getPackageName());
        collect(pm, found, Intent.CATEGORY_LAUNCHER, false, c.getPackageName());
        List<Item> out = new ArrayList<>(found.values());
        Collator collator = Collator.getInstance();
        Collections.sort(out, (a, b) -> collator.compare(a.label.toString(), b.label.toString()));
        return out;
    }

    private static void collect(PackageManager pm, Map<String, Item> found, String category,
            boolean leanback, String self) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(self) || found.containsKey(ai.packageName)) continue;
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(category)
                    .setClassName(ai.packageName, ai.name);
            Item it = new Item(ai.packageName, ri.loadLabel(pm), launch);
            it.info = ai;
            it.leanback = leanback;
            found.put(ai.packageName, it);
        }
    }

    /**
     * Loads a banner, plus an icon for Afterglow or when there is no banner. A {@code replacement}
     * (an icon picked for the app, or the icon pack's match) is used instead of the app's own
     * art: a wide one fills the tile like a banner, any other is drawn as a centered icon.
     * Slow: off the UI thread.
     */
    static void art(Context c, Item it, boolean cornerIcon, Drawable replacement) {
        if (it.info == null) return;
        PackageManager pm = c.getPackageManager();
        boolean wide = replacement != null && isWide(replacement);
        it.banner = wide ? replacement : replacement == null ? it.info.loadBanner(pm) : null;
        it.icon = replacement != null && !wide ? replacement
                : it.banner == null || cornerIcon ? sharpIcon(c, it.info) : null;
    }

    /**
     * The name of dumbtv home's own vector copy of a package's icon, for built-in apps whose only
     * icon is a small bitmap (48 or 96 px) that blurs at tile size: res/drawable/builtin_*.xml.
     * Looked up by name, so adding one is adding the file.
     */
    static String redrawn(String pkg) {
        return "builtin_" + pkg.replace('.', '_');
    }

    /**
     * The {@link #redrawn} copy when there is one, else the app's icon at the highest density it
     * ships rather than the TV's own (xhdpi on a 1080p Fire TV): tiles draw icons larger than a
     * launcher grid would, and the xhdpi bitmap blurs when it is scaled up.
     */
    static Drawable sharpIcon(Context c, ActivityInfo ai) {
        int own = c.getResources().getIdentifier(redrawn(ai.packageName), "drawable", c.getPackageName());
        if (own != 0) return c.getDrawable(own);
        int res = ai.getIconResource();
        if (res != 0) {
            try {
                Drawable d = c.getPackageManager().getResourcesForApplication(ai.applicationInfo)
                        .getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
                if (d != null) return d;
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
                // fall through to the system's choice
            }
        }
        return ai.loadIcon(c.getPackageManager());
    }

    /** TV banners are 16:9; anything clearly wider than tall is drawn across the whole tile. */
    static boolean isWide(Drawable d) {
        return d.getIntrinsicWidth() * 3 > d.getIntrinsicHeight() * 4;
    }

    /**
     * HDMI, AV and other passthrough inputs; HDMI first in port order, then Live TV when the TV
     * has a tuner. The intent is the TV's own
     * app for the input; {@link Input#intent} decides whether a tile opens that or the built-in
     * screen.
     */
    static List<Item> inputs(Context c, TvInputManager tv) {
        List<Item> out = new ArrayList<>();
        if (tv == null) return out;
        List<Item> other = new ArrayList<>();
        Item live = null;
        for (TvInputInfo in : tv.getTvInputList()) {
            if (live == null && isTuner(in) && !in.isHidden(c)) live = liveTv(c, in.getId());
            if (!in.isPassthroughInput() || in.isHidden(c)) continue;
            CharSequence label = in.loadCustomLabel(c);
            if (TextUtils.isEmpty(label)) label = in.loadLabel(c);
            Intent view = new Intent(Intent.ACTION_VIEW,
                    TvContract.buildChannelUriForPassthroughInput(in.getId()));
            Item it = new Item(in.getId(), label, view);
            it.state = tv.getInputState(in.getId());
            (in.getType() == TvInputInfo.TYPE_HDMI ? out : other).add(it);
        }
        Collator collator = Collator.getInstance();
        Collections.sort(out, (a, b) -> collator.compare(a.label.toString(), b.label.toString()));
        Collections.sort(other, (a, b) -> collator.compare(a.label.toString(), b.label.toString()));
        out.addAll(other);
        if (live != null) out.add(live);
        return out;
    }

    /** Apps' own inputs (Prime Video's, VLC's preview) report TYPE_TUNER too; only hardware has /HW. */
    private static boolean isTuner(TvInputInfo in) {
        return in.getType() == TvInputInfo.TYPE_TUNER && in.getId().matches(".*/HW\\d+");
    }

    /**
     * A channel that does not exist opens Live TV's player on the last channel, or its channel scan
     * when there is none, without the permissions its tune and guide actions need (#199).
     */
    private static Item liveTv(Context c, String tunerId) {
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(TvContract.buildChannelUri(Integer.MAX_VALUE), CHANNEL)
                .setPackage(LIVE_TV);
        PackageManager pm = c.getPackageManager();
        ResolveInfo app = pm.resolveActivity(view, 0);
        return app == null ? null : new Item(tunerId, app.loadLabel(pm), view);
    }

    static boolean isLiveTv(Item it) {
        return CHANNEL.equals(it.intent.getType());
    }

    static CharSequence label(Config cfg, Item it) {
        return cfg.get("name." + it.id, it.label.toString());
    }

    static boolean shown(Config cfg, String kind, Item it) {
        if (cfg.list(kind + ".hide").contains(it.id)) return false;
        return !APPS.equals(kind) || it.leanback || cfg.list("apps.show").contains(it.id);
    }

    static void setShown(Config cfg, String kind, Item it, boolean on) {
        List<String> hide = cfg.list(kind + ".hide");
        hide.remove(it.id);
        if (!on && (it.leanback || !APPS.equals(kind))) hide.add(it.id);
        cfg.setList(kind + ".hide", hide);
        if (APPS.equals(kind) && !it.leanback) {
            List<String> show = cfg.list("apps.show");
            show.remove(it.id);
            if (on) show.add(it.id);
            cfg.setList("apps.show", show);
        }
    }

    /** The shown items, in the user's order; anything never moved keeps catalog order. */
    static List<Item> visible(Config cfg, String kind, List<Item> all) {
        return visible(cfg, kind, all, null);
    }

    /** As above, then newest first by {@code recent} (package to last-used time) when it is set. */
    static List<Item> visible(Config cfg, String kind, List<Item> all, Map<String, Long> recent) {
        // Pinning, hiding and recent use only affect the pinned section. Child lock keeps apps
        // hidden there out of reach on this screen. Sort the full list by its displayed names.
        if (ALL_APPS.equals(kind)) {
            List<Item> out = new ArrayList<>();
            if (!"on".equals(cfg.get(Config.ALL_APPS)) || "on".equals(cfg.get(Config.LOCK))) return out;
            out.addAll(all);
            Collator collator = Collator.getInstance();
            Collections.sort(out, (a, b) -> collator.compare(label(cfg, a).toString(), label(cfg, b).toString()));
            return out;
        }
        List<String> order = cfg.list(kind + ".order");
        List<Item> out = new ArrayList<>();
        for (Item it : all) {
            if (shown(cfg, kind, it)) out.add(it);
        }
        Collections.sort(out, (a, b) -> Integer.compare(rank(order, a.id), rank(order, b.id)));
        // The sort is stable, so apps with no record keep the user's order after the rest.
        if (recent != null) Collections.sort(out, (a, b) -> Recent.compare(recent, a.id, b.id));
        return out;
    }

    private static int rank(List<String> order, String id) {
        int i = order.indexOf(id);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** Saves {@code ids} as the new order, keeping the old positions of hidden items after them. */
    static void saveOrder(Config cfg, String kind, List<String> ids) {
        List<String> order = new ArrayList<>(ids);
        for (String id : cfg.list(kind + ".order")) {
            if (!order.contains(id)) order.add(id);
        }
        cfg.setList(kind + ".order", order);
    }
}
