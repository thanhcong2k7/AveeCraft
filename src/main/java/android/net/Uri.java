package android.net;

import java.io.File;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class Uri {
    public static final Uri EMPTY = new Uri("");
    private final String raw;

    private Uri(String raw) {
        this.raw = raw != null ? raw : "";
    }

    public static Uri parse(String uriString) {
        return new Uri(uriString);
    }

    public static Uri fromFile(File file) {
        return new Uri(file != null ? file.toURI().toString() : "");
    }

    public static String decode(String s) {
        if (s == null) return null;
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    public String getScheme() {
        try { return URI.create(raw).getScheme(); } catch (Exception e) { return null; }
    }

    public String getPath() {
        if (raw.startsWith("file://")) {
            try { return new File(URI.create(raw)).getAbsolutePath(); } catch (Exception ignored) {}
        }
        return raw;
    }

    public String getLastPathSegment() {
        int idx = raw.lastIndexOf('/');
        return idx >= 0 ? raw.substring(idx + 1) : raw;
    }

    @Override public String toString() { return raw; }
    @Override public boolean equals(Object o) { return (o instanceof Uri) && raw.equals(((Uri) o).raw); }
    @Override public int hashCode() { return raw.hashCode(); }
}
