package tv.dumbtv.quick;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;

/** Host test for Health.java check logic and expected-state parsing. */
public final class HealthTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) throws Exception {
        String sampleConf =
                "# Expected TV configuration\n" +
                "build=RS8185.3885N\n" +
                "accessibility=tv.dumbtv.home/tv.dumbtv.home.Guard\n" +
                "disabled_packages=com.amazon.spiderpork, com.amazon.bueller.music\n" +
                "setting.secure.sleep_timeout=0\n" +
                "setting.secure.screensaver_enabled=0\n";

        Health.Expected exp = Health.parse(new BufferedReader(new StringReader(sampleConf)));
        check("RS8185.3885N".equals(exp.build), "build parsed");
        check("tv.dumbtv.home/tv.dumbtv.home.Guard".equals(exp.accessibility), "accessibility parsed");
        check(exp.disabledPackages.size() == 2, "disabled packages count");
        check(exp.disabledPackages.contains("com.amazon.spiderpork"), "contains spiderpork");
        check("0".equals(exp.settings.get("secure.sleep_timeout")), "sleep timeout parsed");

        // 1. All match
        Health.Actual actOk = new Health.Actual();
        actOk.build = "RS8185.3885N";
        actOk.accessibility = "tv.dumbtv.home/tv.dumbtv.home.Guard";
        actOk.disabledPackages.add("com.amazon.spiderpork");
        actOk.disabledPackages.add("com.amazon.bueller.music");
        actOk.settings.put("secure.sleep_timeout", "0");
        actOk.settings.put("secure.screensaver_enabled", "0");
        List<String> issuesOk = Health.check(exp, actOk);
        check(issuesOk.isEmpty(), "all match gives no issues");

        // 2. Build changed (e.g. unexpected OTA update)
        Health.Actual actBuild = new Health.Actual();
        actBuild.build = "RS8190.1234N";
        actBuild.accessibility = "tv.dumbtv.home/tv.dumbtv.home.Guard";
        actBuild.disabledPackages.add("com.amazon.spiderpork");
        actBuild.disabledPackages.add("com.amazon.bueller.music");
        actBuild.settings.put("secure.sleep_timeout", "0");
        actBuild.settings.put("secure.screensaver_enabled", "0");
        List<String> issuesBuild = Health.check(exp, actBuild);
        check(issuesBuild.size() == 1, "1 issue for build mismatch");
        check(issuesBuild.get(0).startsWith("Build: RS8190"), "issue reports build: " + issuesBuild.get(0));

        // 3. Accessibility Guard missing
        Health.Actual actGuard = new Health.Actual();
        actGuard.build = "RS8185.3885N";
        actGuard.accessibility = "";
        actGuard.disabledPackages.add("com.amazon.spiderpork");
        actGuard.disabledPackages.add("com.amazon.bueller.music");
        actGuard.settings.put("secure.sleep_timeout", "0");
        actGuard.settings.put("secure.screensaver_enabled", "0");
        List<String> issuesGuard = Health.check(exp, actGuard);
        check(issuesGuard.size() == 1 && issuesGuard.get(0).contains("Guard"), "issue reports guard");

        // 4. Package re-enabled
        Health.Actual actPkg = new Health.Actual();
        actPkg.build = "RS8185.3885N";
        actPkg.accessibility = "tv.dumbtv.home/tv.dumbtv.home.Guard";
        actPkg.enabledPackages.add("com.amazon.spiderpork");
        actPkg.disabledPackages.add("com.amazon.bueller.music");
        actPkg.settings.put("secure.sleep_timeout", "0");
        actPkg.settings.put("secure.screensaver_enabled", "0");
        List<String> issuesPkg = Health.check(exp, actPkg);
        check(issuesPkg.size() == 1 && issuesPkg.get(0).contains("spiderpork"), "issue reports package enabled");

        // 5. Setting reset
        Health.Actual actSetting = new Health.Actual();
        actSetting.build = "RS8185.3885N";
        actSetting.accessibility = "tv.dumbtv.home/tv.dumbtv.home.Guard";
        actSetting.disabledPackages.add("com.amazon.spiderpork");
        actSetting.disabledPackages.add("com.amazon.bueller.music");
        actSetting.settings.put("secure.sleep_timeout", "300000"); // 5 minutes instead of 0
        actSetting.settings.put("secure.screensaver_enabled", "0");
        List<String> issuesSetting = Health.check(exp, actSetting);
        check(issuesSetting.size() == 1 && issuesSetting.get(0).contains("sleep_timeout"), "issue reports setting");

        System.out.println("HealthTest ok");
    }
}
