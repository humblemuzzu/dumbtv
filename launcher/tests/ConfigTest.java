package tv.dumbtv.home;

import android.content.Context;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Real file imports and failed writes, with directory-only Android stubs and no TV. */
public final class ConfigTest {
    private ConfigTest() {}

    static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    static void write(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    static Config open(final File dir, final File media) throws Exception {
        Context context = new Context() {
            @Override public File getExternalFilesDir(String type) { return dir; }
            @Override public File getFilesDir() { return dir; }
            @Override public File[] getExternalMediaDirs() { return new File[] {media}; }
        };
        Constructor<Config> ctor = Config.class.getDeclaredConstructor(Context.class);
        ctor.setAccessible(true);
        return ctor.newInstance(context);
    }

    private static final class Fixture implements AutoCloseable {
        final File root = Files.createTempDirectory("dumbtv-config-").toFile();
        final File dir = new File(root, "files"), media = new File(root, "media");
        final File settings = new File(dir, Config.FILE);
        final File applied = new File(dir, Config.FILE + ".applied");

        Fixture() throws Exception {
            check(dir.mkdir() && media.mkdir(), "create folders");
            write(settings, "accent=blue\nunknown=keep me\n");
            write(new File(media, Config.FILE), "accent=red\n");
        }

        Config open() throws Exception { return ConfigTest.open(dir, media); }

        @Override public void close() { remove(root); }

        private static void remove(File file) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) remove(child);
            check(file.delete(), "remove fixture " + file.getName());
        }
    }

    public static void main(String[] args) throws Exception {
        try (Fixture f = new Fixture()) {
            Config cfg = f.open();
            check("red".equals(cfg.get(Config.ACCENT)), "import wins");
            check("keep me".equals(cfg.get("unknown", "")), "unknown keys retained");
            check(f.applied.isFile(), "successful import acknowledged");
            cfg.set("accent", "green");
            cfg.refresh();
            check("green".equals(f.open().get(Config.ACCENT)), "same import never replaces UI edit");
        }
        try (Fixture f = new Fixture()) {
            File blocked = new File(f.dir, Config.FILE + ".tmp");
            check(blocked.mkdir(), "block settings write");
            Config cfg = f.open();
            check("blue".equals(cfg.get(Config.ACCENT)), "failed import keeps persisted values");
            check(!f.applied.exists(), "failed write not acknowledged");
            check(blocked.delete(), "unblock settings write");
            cfg.refresh();
            check("red".equals(cfg.get(Config.ACCENT)), "unchanged import retried on refresh");
            check("red".equals(f.open().get(Config.ACCENT)), "retry survives restart");
        }
        try (Fixture f = new Fixture()) {
            File blocked = new File(f.dir, Config.FILE + ".tmp");
            check(blocked.mkdir(), "block before restart");
            f.open();
            check(blocked.delete(), "recover before restart");
            check("red".equals(f.open().get(Config.ACCENT)), "failed import retried after restart");
        }
        try (Fixture f = new Fixture()) {
            check(f.settings.delete() && f.settings.mkdir(), "block settings rename");
            write(new File(f.settings, "child"), "keep target nonempty");
            Config cfg = f.open();
            check(!f.applied.exists(), "failed rename not acknowledged");
            check(new File(f.settings, "child").delete() && f.settings.delete(), "unblock rename");
            cfg.refresh();
            check("red".equals(f.open().get(Config.ACCENT)), "rename failure can recover");
        }
        try (Fixture f = new Fixture()) {
            File blocked = new File(f.dir, Config.FILE + ".applied.tmp");
            check(blocked.mkdir(), "block acknowledgement");
            Config cfg = f.open();
            check("blue".equals(f.open().get(Config.ACCENT)), "nothing applied without a marker");
            check(!f.applied.exists(), "marker failure visible on disk");
            check(blocked.delete(), "unblock acknowledgement");
            cfg.refresh();
            check("red".equals(cfg.get(Config.ACCENT)), "import applied once the marker can be written");
            check(f.applied.isFile(), "marker retried without a source change");
            cfg.set("accent", "green");
            cfg.refresh();
            check("green".equals(f.open().get(Config.ACCENT)), "an applied import never comes back");
        }
        System.out.println("ConfigTest passed (import, write/rename failure, retry, acknowledgement)");
    }
}
