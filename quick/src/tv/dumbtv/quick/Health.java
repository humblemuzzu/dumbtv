package tv.dumbtv.quick;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TV health check: pure logic to compare the TV's current state with an expected-state
 * file pushed at /sdcard/Android/media/tv.dumbtv.quick/expected.conf.
 *
 * Checks:
 * - Fire OS build string (detects unexpected OTA updates)
 * - That debloated packages remain disabled
 * - Key settings (sleep timeout, screensaver, ad tracking)
 * - That Guard is still the enabled accessibility service
 *
 * Pure Java, so quick/tests/HealthTest.java runs it on a computer.
 */
public final class Health {
    public static final String CONF_FILE = "/sdcard/Android/media/tv.dumbtv.quick/expected.conf";

    public static final class Expected {
        public String build;
        public String accessibility;
        public final List<String> disabledPackages = new ArrayList<>();
        public final Map<String, String> settings = new LinkedHashMap<>();
    }

    public static final class Actual {
        public String build;
        public String accessibility;
        public final Set<String> disabledPackages = new HashSet<>();
        public final Set<String> enabledPackages = new HashSet<>();
        public final Map<String, String> settings = new LinkedHashMap<>();
    }

    private Health() {}

    public static Expected parse(BufferedReader r) throws IOException {
        Expected exp = new Expected();
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            if ("build".equalsIgnoreCase(k)) {
                exp.build = v;
            } else if ("accessibility".equalsIgnoreCase(k)) {
                exp.accessibility = v;
            } else if ("disabled_packages".equalsIgnoreCase(k) || "disabled".equalsIgnoreCase(k)) {
                for (String p : v.split("[, ]+")) {
                    p = p.trim();
                    if (!p.isEmpty()) exp.disabledPackages.add(p);
                }
            } else if (k.startsWith("setting.") || k.startsWith("settings.")) {
                String settingKey = k.substring(k.indexOf('.') + 1).trim();
                exp.settings.put(settingKey, v);
            } else if ("settings".equalsIgnoreCase(k)) {
                for (String pair : v.split(",")) {
                    int pEq = pair.indexOf('=');
                    if (pEq > 0) {
                        exp.settings.put(pair.substring(0, pEq).trim(), pair.substring(pEq + 1).trim());
                    }
                }
            }
        }
        return exp;
    }

    public static Expected loadExpected(File file) {
        if (!file.exists() || !file.canRead()) return null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            return parse(r);
        } catch (IOException e) {
            return null;
        }
    }

    public static List<String> check(Expected exp, Actual actual) {
        List<String> issues = new ArrayList<>();
        if (exp.build != null && !exp.build.isEmpty()) {
            if (actual.build == null || !actual.build.contains(exp.build)) {
                issues.add("Build: " + actual.build + " (want " + exp.build + ")");
            }
        }
        if (exp.accessibility != null && !exp.accessibility.isEmpty()) {
            if (actual.accessibility == null || !actual.accessibility.contains(exp.accessibility)) {
                issues.add("Guard service inactive");
            }
        }
        for (String p : exp.disabledPackages) {
            if (actual.enabledPackages.contains(p)) {
                issues.add("Package enabled: " + p);
            }
        }
        for (Map.Entry<String, String> e : exp.settings.entrySet()) {
            String actualVal = actual.settings.get(e.getKey());
            if (actualVal == null || !actualVal.equals(e.getValue())) {
                issues.add("Setting " + e.getKey() + "=" + (actualVal != null ? actualVal : "unset") + " (want " + e.getValue() + ")");
            }
        }
        return issues;
    }
}
