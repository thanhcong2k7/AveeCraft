package com.daaw.avee.editor;

import com.daaw.avee.Common.tlog;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

public class UpdateManager {
    public static final String DEFAULT_REPO = "thanhcong2k7/AveeCraft";
    public static final String DEFAULT_API_BASE = "https://api.github.com/repos/" + DEFAULT_REPO;

    public enum State {
        IDLE,
        CHECKING,
        UP_TO_DATE,
        AVAILABLE,
        DOWNLOADING,
        READY,
        ERROR
    }

    public enum PackageKind {
        WINDOWS_SETUP,
        WINDOWS_PORTABLE,
        LINUX_DEB,
        LINUX_PORTABLE,
        OTHER
    }

    public static class ReleaseAsset {
        public final String name;
        public final String downloadUrl;
        public final long size;

        public ReleaseAsset(String name, String downloadUrl, long size) {
            this.name = name;
            this.downloadUrl = downloadUrl;
            this.size = size;
        }
    }

    public static class ReleaseInfo {
        public final String tagName;
        public final String name;
        public final String body;
        public final String htmlUrl;
        public final boolean prerelease;
        public final List<ReleaseAsset> assets;

        public ReleaseInfo(String tagName, String name, String body, String htmlUrl, boolean prerelease, List<ReleaseAsset> assets) {
            this.tagName = tagName;
            this.name = name;
            this.body = body;
            this.htmlUrl = htmlUrl;
            this.prerelease = prerelease;
            this.assets = assets;
        }
    }

    public static class Settings {
        public boolean autoCheckUpdates = true;
        public boolean autoDownloadUpdates = false;
        public String skippedVersion = "";
    }

    private static volatile String cachedVersion;

    private final File configDir;
    private final File settingsFile;
    private final File updatesDir;
    private final String apiBaseUrl;
    private final String currentVersionOverride;

    private final Settings settings = new Settings();
    private volatile State state = State.IDLE;
    private volatile String statusText = "";
    private volatile ReleaseInfo latestRelease;
    private volatile ReleaseAsset selectedAsset;
    private volatile File downloadedFile;
    private volatile long downloadedBytes = 0L;
    private volatile long totalBytes = 0L;
    private volatile boolean cancelled = false;
    private volatile Thread workerThread;

    public UpdateManager() {
        this(defaultConfigDir(), DEFAULT_API_BASE, null);
    }

    public UpdateManager(File configDir, String apiBaseUrl, String currentVersionOverride) {
        this.configDir = configDir != null ? configDir : defaultConfigDir();
        this.settingsFile = new File(this.configDir, "settings.json");
        this.updatesDir = new File(this.configDir, "updates");
        this.apiBaseUrl = (apiBaseUrl != null && !apiBaseUrl.isEmpty()) ? stripTrailingSlash(apiBaseUrl) : DEFAULT_API_BASE;
        this.currentVersionOverride = currentVersionOverride;
        loadSettings();
    }

    private static File defaultConfigDir() {
        String home = System.getProperty("user.home", ".");
        return new File(home, ".aveecraft");
    }

    private static String stripTrailingSlash(String s) {
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public static String getCurrentVersion() {
        if (cachedVersion != null) return cachedVersion;
        String ver = "v1.2.101a03";
        try (InputStream is = UpdateManager.class.getResourceAsStream("/aveecraft-version.properties")) {
            if (is != null) {
                Properties p = new Properties();
                p.load(is);
                String v = p.getProperty("version", "").trim();
                if (!v.isEmpty()) ver = v;
            }
        } catch (Throwable ignored) {}
        cachedVersion = ver;
        return ver;
    }

    public String getEffectiveCurrentVersion() {
        return (currentVersionOverride != null && !currentVersionOverride.isEmpty())
                ? currentVersionOverride : getCurrentVersion();
    }

    public synchronized Settings getSettings() {
        return settings;
    }

    public synchronized void loadSettings() {
        settings.autoCheckUpdates = true;
        settings.autoDownloadUpdates = false;
        settings.skippedVersion = "";
        if (!settingsFile.isFile()) return;
        try {
            String raw = Files.readString(settingsFile.toPath(), StandardCharsets.UTF_8);
            JSONObject obj = new JSONObject(raw);
            settings.autoCheckUpdates = obj.optBoolean("autoCheckUpdates", true);
            settings.autoDownloadUpdates = obj.optBoolean("autoDownloadUpdates", false);
            settings.skippedVersion = obj.optString("skippedVersion", "");
        } catch (Throwable t) {
            tlog.w("Failed to read update settings: " + t.getMessage());
        }
    }

    public synchronized void saveSettings() {
        try {
            configDir.mkdirs();
            JSONObject obj = new JSONObject();
            obj.put("autoCheckUpdates", settings.autoCheckUpdates);
            obj.put("autoDownloadUpdates", settings.autoDownloadUpdates);
            obj.put("skippedVersion", settings.skippedVersion != null ? settings.skippedVersion : "");
            Files.writeString(settingsFile.toPath(), obj.toString(2), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            tlog.w("Failed to save update settings: " + t.getMessage());
        }
    }

    public State getState() { return state; }
    public String getStatusText() { return statusText; }
    public ReleaseInfo getLatestRelease() { return latestRelease; }
    public ReleaseAsset getSelectedAsset() { return selectedAsset; }
    public File getDownloadedFile() { return downloadedFile; }
    public long getDownloadedBytes() { return downloadedBytes; }
    public long getTotalBytes() { return totalBytes; }
    public File getUpdatesDir() { return updatesDir; }

    public float getDownloadProgress() {
        if (totalBytes <= 0L) return 0f;
        return Math.max(0f, Math.min(1f, (float) downloadedBytes / (float) totalBytes));
    }

    public void shutdown() {
        cancelled = true;
        Thread t = workerThread;
        if (t != null) t.interrupt();
    }

    public void checkForUpdatesAsync(boolean manual) {
        if (state == State.CHECKING || state == State.DOWNLOADING) return;
        cancelled = false;
        state = State.CHECKING;
        statusText = "Checking GitHub for updates...";
        Thread t = new Thread(() -> checkForUpdatesBlocking(manual), "AveeUpdateCheck");
        t.setDaemon(true);
        workerThread = t;
        t.start();
    }

    public void checkForUpdatesBlocking(boolean manual) {
        try {
            ReleaseInfo info = fetchLatestRelease(apiBaseUrl, getEffectiveCurrentVersion());
            if (cancelled) return;
            if (info == null || info.tagName == null || info.tagName.isEmpty()) {
                state = State.UP_TO_DATE;
                statusText = "No releases published yet.";
                return;
            }
            latestRelease = info;
            int cmp = compareVersions(info.tagName, getEffectiveCurrentVersion());
            if (cmp <= 0) {
                state = State.UP_TO_DATE;
                statusText = "You are on the latest version (" + getEffectiveCurrentVersion() + ").";
                return;
            }
            if (!manual && info.tagName.equals(settings.skippedVersion)) {
                state = State.UP_TO_DATE;
                statusText = "Update " + info.tagName + " skipped.";
                return;
            }
            PackageKind pkgKind = detectPackageKind();
            selectedAsset = selectAssetForPlatform(info.assets, pkgKind);
            File existing = findExistingDownload(info.tagName, selectedAsset);
            if (existing != null) {
                downloadedFile = existing;
                downloadedBytes = existing.length();
                totalBytes = existing.length();
                state = State.READY;
                statusText = "Update " + info.tagName + " downloaded and ready.";
                return;
            }
            state = State.AVAILABLE;
            statusText = "Update available: " + info.tagName;
            if (settings.autoDownloadUpdates && selectedAsset != null) {
                downloadUpdateBlocking();
            }
        } catch (Throwable ex) {
            if (!cancelled) {
                state = State.ERROR;
                statusText = "Update check failed: " + ex.getMessage();
            }
        }
    }

    public void downloadUpdateAsync() {
        if (state == State.DOWNLOADING || selectedAsset == null || latestRelease == null) return;
        cancelled = false;
        Thread t = new Thread(this::downloadUpdateBlocking, "AveeUpdateDownload");
        t.setDaemon(true);
        workerThread = t;
        t.start();
    }

    public void downloadUpdateBlocking() {
        ReleaseInfo info = latestRelease;
        ReleaseAsset asset = selectedAsset;
        if (info == null || asset == null || asset.downloadUrl == null || asset.downloadUrl.isEmpty()) {
            state = State.ERROR;
            statusText = "No matching installer/archive asset found for this platform.";
            return;
        }
        state = State.DOWNLOADING;
        downloadedBytes = 0L;
        totalBytes = asset.size;
        statusText = "Downloading " + asset.name + "...";

        File tagDir = new File(updatesDir, sanitizeFileName(info.tagName));
        tagDir.mkdirs();
        File partFile = new File(tagDir, asset.name + ".part");
        File finalFile = new File(tagDir, asset.name);

        try {
            HttpURLConnection conn = openConnection(asset.downloadUrl, getEffectiveCurrentVersion());
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " downloading " + asset.name);
            }
            long len = conn.getContentLengthLong();
            if (len > 0) totalBytes = len;

            byte[] buf = new byte[32768];
            try (InputStream is = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream fos = new FileOutputStream(partFile)) {
                int n;
                while ((n = is.read(buf)) > 0) {
                    if (cancelled || Thread.currentThread().isInterrupted()) {
                        throw new IOException("Download cancelled");
                    }
                    fos.write(buf, 0, n);
                    downloadedBytes += n;
                }
            }
            if (asset.size > 0 && partFile.length() != asset.size) {
                throw new IOException("Size mismatch: expected " + asset.size + " bytes, got " + partFile.length());
            }
            Files.move(partFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            downloadedFile = finalFile;
            state = State.READY;
            statusText = "Update " + info.tagName + " ready to install.";
        } catch (Throwable ex) {
            if (partFile.exists()) partFile.delete();
            if (!cancelled) {
                state = State.ERROR;
                statusText = "Download failed: " + ex.getMessage();
            }
        }
    }

    private File findExistingDownload(String tag, ReleaseAsset asset) {
        if (tag == null || asset == null) return null;
        File f = new File(new File(updatesDir, sanitizeFileName(tag)), asset.name);
        if (f.isFile() && f.length() > 0 && (asset.size <= 0 || f.length() == asset.size)) {
            return f;
        }
        return null;
    }

    public void skipLatestVersion() {
        ReleaseInfo info = latestRelease;
        if (info != null && info.tagName != null) {
            synchronized (this) {
                settings.skippedVersion = info.tagName;
                saveSettings();
            }
            state = State.UP_TO_DATE;
            statusText = "Skipped " + info.tagName;
        }
    }

    /**
     * Launches the downloaded installer or portable self-updater.
     * @return true if the application should exit immediately so files can be replaced.
     */
    public boolean applyDownloadedUpdate() throws IOException {
        File pkg = downloadedFile;
        if (pkg == null || !pkg.isFile()) {
            throw new IOException("Downloaded update file not found");
        }
        String lower = pkg.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".exe")) {
            new ProcessBuilder("cmd", "/c", "start", "", pkg.getAbsolutePath(), "/SILENT").start();
            return true;
        }
        if (lower.endsWith(".deb")) {
            if (commandExists("pkexec")) {
                new ProcessBuilder("pkexec", "apt", "install", "-y", pkg.getAbsolutePath()).start();
                return false;
            }
            openFolder(pkg.getParentFile());
            statusText = "Downloaded .deb package to: " + pkg.getAbsolutePath();
            return false;
        }

        String appExe = System.getProperty("jpackage.app-path", "");
        if (appExe != null && !appExe.trim().isEmpty()) {
            File exeFile = new File(appExe.trim());
            File appRoot = resolveAppRoot(exeFile);
            if (appRoot != null && appRoot.isDirectory()) {
                File extractDir = new File(pkg.getParentFile(), "extracted");
                deleteRecursively(extractDir);
                extractDir.mkdirs();
                extractArchive(pkg, extractDir);
                File payloadRoot = findExtractedAppRoot(extractDir);
                if (payloadRoot != null) {
                    launchPortableSwapScript(payloadRoot, appRoot, exeFile);
                    return true;
                }
            }
        }
        openFolder(pkg.getParentFile());
        statusText = "Portable update saved to: " + pkg.getAbsolutePath();
        return false;
    }

    public static void openFolder(File dir) {
        if (dir == null) return;
        dir.mkdirs();
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(dir);
                return;
            }
        } catch (Throwable ignored) {}
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                new ProcessBuilder("explorer", dir.getAbsolutePath()).start();
            } else {
                new ProcessBuilder("xdg-open", dir.getAbsolutePath()).start();
            }
        } catch (Throwable ignored) {}
    }

    private static File resolveAppRoot(File exeFile) {
        if (exeFile == null) return null;
        File parent = exeFile.getParentFile();
        if (parent == null) return null;
        if ("bin".equalsIgnoreCase(parent.getName()) && parent.getParentFile() != null) {
            return parent.getParentFile();
        }
        return parent;
    }

    private static File findExtractedAppRoot(File extractDir) {
        File[] children = extractDir.listFiles();
        if (children == null || children.length == 0) return null;
        if (children.length == 1 && children[0].isDirectory()) {
            return children[0];
        }
        return extractDir;
    }

    private static void extractArchive(File archive, File dstDir) throws IOException {
        String lower = archive.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) {
            byte[] buf = new byte[16384];
            String canonicalDst = dstDir.getCanonicalPath() + File.separator;
            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    File out = new File(dstDir, entry.getName());
                    if (!out.getCanonicalPath().startsWith(canonicalDst)) {
                        throw new IOException("Blocked zip slip entry: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        out.mkdirs();
                    } else {
                        if (out.getParentFile() != null) out.getParentFile().mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(out)) {
                            int n;
                            while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                        }
                    }
                    zis.closeEntry();
                }
            }
            return;
        }
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            Process p = new ProcessBuilder("tar", "-xzf", archive.getAbsolutePath(), "-C", dstDir.getAbsolutePath())
                    .redirectErrorStream(true).start();
            try {
                if (p.waitFor() != 0) throw new IOException("tar extraction failed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("tar extraction interrupted");
            }
            return;
        }
        throw new IOException("Unsupported archive format: " + archive.getName());
    }

    private void launchPortableSwapScript(File payloadRoot, File appRoot, File exeFile) throws IOException {
        long pid = ProcessHandle.current().pid();
        boolean isWin = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        updatesDir.mkdirs();
        if (isWin) {
            File bat = new File(updatesDir, "apply_update.bat");
            String script = "@echo off\r\n"
                    + ":waitloop\r\n"
                    + "tasklist /FI \"PID eq " + pid + "\" 2>NUL | find /I \"" + pid + "\" >NUL\r\n"
                    + "if not errorlevel 1 (\r\n"
                    + "  timeout /t 1 /nobreak >NUL\r\n"
                    + "  goto waitloop\r\n"
                    + ")\r\n"
                    + "xcopy /E /Y /I \"" + payloadRoot.getAbsolutePath() + "\\*\" \"" + appRoot.getAbsolutePath() + "\\\" >NUL\r\n"
                    + "start \"\" \"" + exeFile.getAbsolutePath() + "\"\r\n";
            Files.writeString(bat.toPath(), script, StandardCharsets.UTF_8);
            new ProcessBuilder("cmd", "/c", "start", "/min", "", bat.getAbsolutePath()).start();
        } else {
            File sh = new File(updatesDir, "apply_update.sh");
            String script = "#!/bin/sh\n"
                    + "while kill -0 " + pid + " 2>/dev/null; do sleep 0.3; done\n"
                    + "cp -rf \"" + payloadRoot.getAbsolutePath() + "/.\" \"" + appRoot.getAbsolutePath() + "/\"\n"
                    + "chmod +x \"" + exeFile.getAbsolutePath() + "\" 2>/dev/null || true\n"
                    + "nohup \"" + exeFile.getAbsolutePath() + "\" >/dev/null 2>&1 &\n";
            Files.writeString(sh.toPath(), script, StandardCharsets.UTF_8);
            sh.setExecutable(true);
            new ProcessBuilder("sh", sh.getAbsolutePath()).start();
        }
    }

    private static boolean commandExists(String cmd) {
        try {
            Process p = new ProcessBuilder("which", cmd).redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        f.delete();
    }

    public static PackageKind detectPackageKind() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String appPath = System.getProperty("jpackage.app-path", "");
        if (os.contains("win")) {
            if (appPath != null && !appPath.isEmpty()) {
                File dir = resolveAppRoot(new File(appPath));
                if (dir != null && new File(dir, "unins000.exe").isFile()) {
                    return PackageKind.WINDOWS_SETUP;
                }
                return PackageKind.WINDOWS_PORTABLE;
            }
            return PackageKind.WINDOWS_SETUP;
        }
        if (os.contains("linux")) {
            if (appPath != null && (appPath.startsWith("/opt/") || appPath.startsWith("/usr/"))) {
                return PackageKind.LINUX_DEB;
            }
            return PackageKind.LINUX_PORTABLE;
        }
        return PackageKind.OTHER;
    }

    public static ReleaseAsset selectAssetForPlatform(List<ReleaseAsset> assets, PackageKind kind) {
        if (assets == null || assets.isEmpty()) return null;
        String exactName;
        String fallbackExt;
        switch (kind) {
            case WINDOWS_SETUP:
                exactName = "AveeCraft-windows-x64-setup.exe";
                fallbackExt = ".exe";
                break;
            case WINDOWS_PORTABLE:
                exactName = "AveeCraft-windows-x64-standalone.zip";
                fallbackExt = ".zip";
                break;
            case LINUX_DEB:
                exactName = "AveeCraft-linux-amd64.deb";
                fallbackExt = ".deb";
                break;
            case LINUX_PORTABLE:
                exactName = "AveeCraft-linux-x64-standalone.tar.gz";
                fallbackExt = ".tar.gz";
                break;
            default:
                exactName = "";
                fallbackExt = "";
                break;
        }
        if (!exactName.isEmpty()) {
            for (ReleaseAsset a : assets) {
                if (exactName.equalsIgnoreCase(a.name)) return a;
            }
        }
        if (!fallbackExt.isEmpty()) {
            for (ReleaseAsset a : assets) {
                if (a.name != null && a.name.toLowerCase(Locale.ROOT).endsWith(fallbackExt)) return a;
            }
        }
        return assets.get(0);
    }

    public static ReleaseInfo fetchLatestRelease(String apiBase, String userAgentVersion) throws IOException {
        String url = stripTrailingSlash(apiBase) + "/releases?per_page=5";
        HttpURLConnection conn = openConnection(url, userAgentVersion);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("GitHub API returned HTTP " + code);
        }
        String json = readAllUtf8(conn.getInputStream()).trim();
        JSONObject relObj = null;
        if (json.startsWith("[")) {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject candidate = arr.getJSONObject(i);
                if (!candidate.optBoolean("draft", false)) {
                    relObj = candidate;
                    break;
                }
            }
        } else if (json.startsWith("{")) {
            relObj = new JSONObject(json);
        }
        if (relObj == null) return null;
        return parseReleaseJson(relObj);
    }

    public static ReleaseInfo parseReleaseJson(JSONObject obj) {
        if (obj == null) return null;
        String tag = obj.optString("tag_name", "");
        String name = obj.optString("name", tag);
        String body = obj.optString("body", "");
        String htmlUrl = obj.optString("html_url", "");
        boolean prerelease = obj.optBoolean("prerelease", false);
        List<ReleaseAsset> assets = new ArrayList<>();
        JSONArray arr = obj.optJSONArray("assets");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.optJSONObject(i);
                if (a == null) continue;
                String aName = a.optString("name", "");
                String dlUrl = a.optString("browser_download_url", "");
                long size = a.optLong("size", -1L);
                if (!aName.isEmpty() && !dlUrl.isEmpty()) {
                    assets.add(new ReleaseAsset(aName, dlUrl, size));
                }
            }
        }
        return new ReleaseInfo(tag, name, body, htmlUrl, prerelease, assets);
    }

    private static HttpURLConnection openConnection(String rawUrl, String ver) throws IOException {
        String cur = rawUrl;
        for (int hops = 0; hops < 6; hops++) {
            URL url = URI.create(cur).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "AveeCraft/" + (ver != null ? ver : "1.2.101"));
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) throw new IOException("Redirect without Location");
                cur = loc;
                continue;
            }
            return conn;
        }
        throw new IOException("Too many redirects");
    }

    private static String readAllUtf8(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try (InputStream in = is) {
            while ((n = in.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static String sanitizeFileName(String s) {
        return (s == null || s.isEmpty()) ? "unknown" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Compares AveeCraft version tags such as v1.2.101, v1.2.101a03, v1.2.101.a04, v1.2.101.abc, v1.2.101.1.
     * Note: extending 1.2.101 with a 4th suffix (e.g. 1.2.101.a01 or 1.2.101.abc) is always > base 1.2.101.
     */
    public static int compareVersions(String a, String b) {
        List<Object> ta = tokenizeVersion(a);
        List<Object> tb = tokenizeVersion(b);
        int max = Math.max(ta.size(), tb.size());
        for (int i = 0; i < max; i++) {
            if (i >= ta.size()) return -1;
            if (i >= tb.size()) return 1;
            Object oa = ta.get(i);
            Object ob = tb.get(i);
            if (oa instanceof Integer && ob instanceof Integer) {
                int cmp = Integer.compare((Integer) oa, (Integer) ob);
                if (cmp != 0) return cmp;
            } else if (oa instanceof Integer) {
                return 1; // numeric release token > alphabetic pre-release token (e.g. .1 > .a04)
            } else if (ob instanceof Integer) {
                return -1;
            } else {
                int cmp = ((String) oa).compareToIgnoreCase((String) ob);
                if (cmp != 0) return cmp;
            }
        }
        return 0;
    }

    private static List<Object> tokenizeVersion(String raw) {
        List<Object> tokens = new ArrayList<>();
        if (raw == null) return tokens;
        String s = raw.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                int j = i + 1;
                while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
                try {
                    tokens.add(Integer.parseInt(s.substring(i, j)));
                } catch (NumberFormatException ignored) {
                    tokens.add(s.substring(i, j));
                }
                i = j;
            } else if (Character.isLetter(c)) {
                int j = i + 1;
                while (j < s.length() && Character.isLetter(s.charAt(j))) j++;
                tokens.add(s.substring(i, j).toLowerCase(Locale.ROOT));
                i = j;
            } else {
                i++;
            }
        }
        return tokens;
    }
}
