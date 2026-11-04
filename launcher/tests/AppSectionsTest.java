package tv.dumbtv.home;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Catalog selection uses real settings files; no package manager, Android UI or TV is needed. */
public final class AppSectionsTest {
    private AppSectionsTest() {}

    private static void check(boolean ok, String name) { ConfigTest.check(ok, name); }

    private static Item app(String id, boolean tv) {
        Item item = new Item(id, id, null);
        item.leanback = tv;
        return item;
    }

    private static void ids(List<Item> actual, String... expected) {
        check(actual.size() == expected.length, "section size " + Arrays.toString(expected));
        for (int i = 0; i < expected.length; i++) {
            check(expected[i].equals(actual.get(i).id), "section order at " + i);
        }
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("dumbtv-sections-").toFile();
        try {
            Config cfg = ConfigTest.open(dir, null);
            Item a = app("alpha", true), b = app("beta", true), c = app("gamma", false);
            List<Item> catalog = Arrays.asList(a, b, c);
            check("off".equals(cfg.get(Config.ALL_APPS)), "all apps off by default");
            check(Arrays.asList(Config.CHOICES).contains(Config.ALL_APPS), "available in Options");
            ids(Catalog.visible(cfg, Catalog.APPS, catalog), "alpha", "beta");
            ids(Catalog.visible(cfg, Catalog.ALL_APPS, catalog));

            Catalog.setShown(cfg, Catalog.APPS, b, false);
            cfg.setList("apps.order", Arrays.asList("gamma", "alpha"));
            cfg.set("all_apps", "on");
            ids(Catalog.visible(cfg, Catalog.ALL_APPS, catalog), "alpha", "beta", "gamma");

            cfg.set("name.alpha", "Zeta");
            ids(Catalog.visible(cfg, Catalog.ALL_APPS, catalog), "beta", "gamma", "alpha");
            cfg.set("name.alpha", null);
            ids(Catalog.visible(cfg, Catalog.APPS, catalog), "alpha");
            Catalog.setShown(cfg, Catalog.APPS, c, true);
            ids(Catalog.visible(cfg, Catalog.APPS, catalog), "gamma", "alpha");
            check(Catalog.shown(cfg, Catalog.APPS, c), "non-TV app pinned");
            Catalog.setShown(cfg, Catalog.APPS, c, false);
            check(!Catalog.shown(cfg, Catalog.APPS, c), "non-TV app unpinned");
            ids(Catalog.visible(cfg, Catalog.ALL_APPS, catalog), "alpha", "beta", "gamma");

            cfg.set("order", "recent");
            Map<String, Long> recent = new HashMap<>();
            recent.put("gamma", 100L);
            recent.put("beta", 50L);
            ids(Catalog.visible(cfg, Catalog.ALL_APPS, catalog, recent), "alpha", "beta", "gamma");
            Catalog.setShown(cfg, Catalog.APPS, b, true);
            ids(Catalog.visible(cfg, Catalog.APPS, catalog, recent), "beta", "alpha");

            cfg.set("lock", "on");
            ids(Catalog.visible(cfg, Catalog.ALL_APPS, catalog));
            ids(Catalog.visible(cfg, Catalog.APPS, catalog), "alpha", "beta");
            cfg.set("lock", "off");
            ids(Catalog.visible(cfg, Catalog.ALL_APPS, catalog), "alpha", "beta", "gamma");
            cfg.set("all_apps", "off");
            ids(Catalog.visible(cfg, Catalog.ALL_APPS, catalog));
            Config reloaded = ConfigTest.open(dir, null);
            ids(Catalog.visible(reloaded, Catalog.APPS, catalog), "alpha", "beta");
            ids(Catalog.visible(reloaded, Catalog.ALL_APPS, catalog));
            ids(catalog, "alpha", "beta", "gamma");
            ids(Catalog.visible(cfg, Catalog.INPUTS, Arrays.asList(c)), "gamma");
            check(Catalog.isApp(Catalog.ALL_APPS) && !Catalog.isApp(Catalog.INPUTS), "tile types");
            System.out.println("AppSectionsTest passed (default, pin/unpin, full catalog, order, lock)");
        } finally {
            File[] files = dir.listFiles();
            if (files != null) for (File file : files) check(file.delete(), "remove settings");
            check(dir.delete(), "remove folder");
        }
    }
}
