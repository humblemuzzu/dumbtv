package android.content;

import java.io.File;

/** Only the directory lookups Config uses; the APK is compiled against the real SDK. */
public abstract class Context {
    public Context getApplicationContext() { return this; }
    public abstract File getExternalFilesDir(String type);
    public abstract File getFilesDir();
    public abstract File[] getExternalMediaDirs();
}
