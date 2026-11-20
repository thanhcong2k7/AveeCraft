package android.content.res;

import java.io.IOException;
import java.io.InputStream;

public class AssetManager {
    public InputStream open(String fileName) throws IOException {
        String clean = fileName != null && fileName.startsWith("/") ? fileName.substring(1) : fileName;
        InputStream is = AssetManager.class.getResourceAsStream("/" + clean);
        if (is == null) throw new IOException("Asset not found: " + fileName);
        return is;
    }
}
