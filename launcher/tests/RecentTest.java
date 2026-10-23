package tv.dumbtv.home;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Host-side checks for App order › Recently used, without a device or test framework. */
public final class RecentTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        Map<String, Long> times = new HashMap<>();
        times.put("plex", 100L);
        times.put("smarttube", 300L);
        times.put("spotify", 200L);

        // Already in the user's order, as Catalog.visible sorts it first.
        List<String> apps = new ArrayList<>(Arrays.asList("prime", "plex", "netflix", "smarttube", "spotify"));
        Collections.sort(apps, (a, b) -> Recent.compare(times, a, b));
        check(apps.equals(Arrays.asList("smarttube", "spotify", "plex", "prime", "netflix")),
                "newest first, never-used keep the user's order: " + apps);

        List<String> none = new ArrayList<>(Arrays.asList("b", "a", "c"));
        Collections.sort(none, (a, b) -> Recent.compare(new HashMap<>(), a, b));
        check(none.equals(Arrays.asList("b", "a", "c")), "no history leaves the order alone");

        check("custom".equals(Config.ORDER.fallback), "default order unchanged");
        check("Recently used".equals(Config.ORDER.name("recent")), "recent is an Options choice");
        check(Arrays.asList(Config.CHOICES).contains(Config.ORDER), "App order shows in Options");
        System.out.println("RecentTest passed");
    }
}
