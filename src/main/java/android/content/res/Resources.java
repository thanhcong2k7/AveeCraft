package android.content.res;

import com.daaw.avee.R;
import java.io.InputStream;

public class Resources {
    private final AssetManager assets = new AssetManager();

    public AssetManager getAssets() {
        return assets;
    }

    public InputStream openRawResource(int id) {
        String path = R.resolveResourcePath(id);
        if (path == null) return null;
        return Resources.class.getResourceAsStream(path);
    }

    public String getString(int id) {
        return "";
    }

    public float getDimension(int id) {
        return 1f;
    }
}
