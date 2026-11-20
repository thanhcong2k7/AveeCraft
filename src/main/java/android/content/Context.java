package android.content;

import android.content.res.AssetManager;
import android.content.res.Resources;
import android.net.Uri;
import java.io.File;

public class Context {
    private final Resources resources = new Resources();
    private final ContentResolver contentResolver = new ContentResolver();
    private final File cacheDir;
    private final File filesDir;

    public Context() {
        String base = System.getProperty("java.io.tmpdir", "/tmp");
        cacheDir = new File(base, "avee_editor_cache");
        filesDir = new File(base, "avee_editor_files");
        cacheDir.mkdirs();
        filesDir.mkdirs();
    }

    public Context getApplicationContext() { return this; }
    public Resources getResources() { return resources; }
    public AssetManager getAssets() { return resources.getAssets(); }
    public ContentResolver getContentResolver() { return contentResolver; }
    public File getCacheDir() { return cacheDir; }
    public File getFilesDir() { return filesDir; }
    public File[] getExternalFilesDirs(String type) { return new File[]{filesDir}; }
    public String getPackageName() { return "com.daaw.avee"; }
    public void sendBroadcast(Intent intent) {}
    public String getString(int resId) { return ""; }

    public Uri parseUnused() { return null; }
}
