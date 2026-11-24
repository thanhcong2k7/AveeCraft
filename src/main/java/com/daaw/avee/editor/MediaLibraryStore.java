package com.daaw.avee.editor;

import com.daaw.avee.Common.tlog;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Manages persisted music folders (seeded with OS ~/Music once so users can remove or add folders)
 * and scans audio tracks asynchronously without stalling the UI thread.
 */
public class MediaLibraryStore {
    private static final Set<String> AUDIO_EXTS = new LinkedHashSet<>(Arrays.asList(
            ".mp3", ".wav", ".flac", ".ogg", ".m4a", ".aac", ".opus", ".wma"
    ));

    public static class TrackEntry {
        public final File file;
        public final String title;
        public final String ext;
        public final String folderLabel;

        TrackEntry(File file, String title, String ext, String folderLabel) {
            this.file = file;
            this.title = title;
            this.ext = ext;
            this.folderLabel = folderLabel;
        }
    }

    public static class SubfolderEntry {
        public final File dir;
        public final String name;
        public final int itemCount;

        SubfolderEntry(File dir, String name, int itemCount) {
            this.dir = dir;
            this.name = name;
            this.itemCount = itemCount;
        }
    }

    public static class FolderListing {
        public final File directory;
        public final List<SubfolderEntry> subfolders;
        public final List<TrackEntry> tracks;

        FolderListing(File directory, List<SubfolderEntry> subfolders, List<TrackEntry> tracks) {
            this.directory = directory;
            this.subfolders = subfolders;
            this.tracks = tracks;
        }
    }

    private final File configFile;
    private final List<File> folders = new ArrayList<>();
    private final List<TrackEntry> tracks = new ArrayList<>();
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AveeMediaScanner");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean scanning = false;
    private String lastScanThreadName = "";

    public MediaLibraryStore() {
        this(defaultConfigFile());
    }

    public MediaLibraryStore(File configFile) {
        this.configFile = configFile != null ? configFile : defaultConfigFile();
        loadConfig();
    }

    public static File defaultConfigFile() {
        return new File(new File(System.getProperty("user.home", "."), ".aveecraft"), "media_library.json");
    }

    public static File defaultMusicFolder() {
        return new File(System.getProperty("user.home", "."), "Music");
    }

    public boolean isScanning() {
        return scanning;
    }

    public String getLastScanThreadName() {
        return lastScanThreadName;
    }

    public synchronized List<File> getFolders() {
        return new ArrayList<>(folders);
    }

    public synchronized List<TrackEntry> getTracks() {
        return new ArrayList<>(tracks);
    }

    public synchronized boolean addFolder(File dir) {
        if (dir == null) return false;
        File norm = dir.getAbsoluteFile();
        for (File existing : folders) {
            if (existing.equals(norm)) return false;
        }
        folders.add(norm);
        saveConfig();
        return true;
    }

    public synchronized boolean removeFolder(File dir) {
        if (dir == null) return false;
        File norm = dir.getAbsoluteFile();
        boolean removed = folders.removeIf(f -> f.equals(norm));
        if (removed) {
            saveConfig();
        }
        return removed;
    }

    public void rescanAsync() {
        final List<File> snapshot = getFolders();
        scanning = true;
        scanExecutor.submit(() -> {
            try {
                lastScanThreadName = Thread.currentThread().getName();
                List<TrackEntry> scanned = scanFoldersInternal(snapshot);
                synchronized (MediaLibraryStore.this) {
                    tracks.clear();
                    tracks.addAll(scanned);
                }
            } finally {
                scanning = false;
            }
        });
    }

    public void rescanSync() {
        scanning = true;
        try {
            List<TrackEntry> scanned = scanFoldersInternal(getFolders());
            synchronized (this) {
                tracks.clear();
                tracks.addAll(scanned);
            }
        } finally {
            scanning = false;
        }
    }

    public void shutdown() {
        scanExecutor.shutdownNow();
    }

    private synchronized void loadConfig() {
        folders.clear();
        if (configFile.isFile()) {
            try {
                JSONObject obj = new JSONObject(Files.readString(configFile.toPath(), StandardCharsets.UTF_8));
                JSONArray arr = obj.optJSONArray("folders");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        String p = arr.optString(i, "").trim();
                        if (!p.isEmpty()) {
                            folders.add(new File(p).getAbsoluteFile());
                        }
                    }
                }
                return;
            } catch (Throwable t) {
                tlog.w("Failed loading media_library.json: " + t.getMessage());
            }
        }
        // Initial launch only: add OS default ~/Music if not yet configured (user may remove it later)
        File defMusic = defaultMusicFolder().getAbsoluteFile();
        folders.add(defMusic);
        saveConfig();
    }

    private synchronized void saveConfig() {
        try {
            File parent = configFile.getParentFile();
            if (parent != null) parent.mkdirs();
            JSONObject obj = new JSONObject();
            obj.put("initializedDefaultFolder", true);
            JSONArray arr = new JSONArray();
            for (File f : folders) {
                arr.put(f.getAbsolutePath());
            }
            obj.put("folders", arr);
            File tmp = new File(parent, configFile.getName() + ".tmp");
            Files.writeString(tmp.toPath(), obj.toString(2), StandardCharsets.UTF_8);
            try {
                Files.move(tmp.toPath(), configFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
                Files.move(tmp.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            tlog.w("Failed saving media_library.json: " + t.getMessage());
        }
    }

    public int countItemsInFolder(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] list = dir.listFiles();
        if (list == null) return 0;
        int count = 0;
        for (File f : list) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".")) {
                    count++;
                }
            } else if (f.isFile() && isAudioFileName(f.getName())) {
                count++;
            }
        }
        return count;
    }

    public FolderListing listDirectory(File dir) {
        List<SubfolderEntry> subfolders = new ArrayList<>();
        List<TrackEntry> directTracks = new ArrayList<>();
        if (dir != null && dir.isDirectory()) {
            File[] list = dir.listFiles();
            String folderLabel = dir.getName().isEmpty() ? dir.getAbsolutePath() : dir.getName();
            if (list != null) {
                for (File f : list) {
                    String name = f.getName();
                    if (f.isDirectory()) {
                        if (!name.startsWith(".")) {
                            subfolders.add(new SubfolderEntry(f.getAbsoluteFile(), name, countItemsInFolder(f)));
                        }
                    } else if (f.isFile() && isAudioFileName(name)) {
                        int dot = name.lastIndexOf('.');
                        String title = name.substring(0, dot);
                        String ext = name.substring(dot + 1).toUpperCase(Locale.ROOT);
                        directTracks.add(new TrackEntry(f.getAbsoluteFile(), title, ext, folderLabel));
                    }
                }
            }
        }
        Collections.sort(subfolders, Comparator.comparing(s -> s.name.toLowerCase(Locale.ROOT)));
        Collections.sort(directTracks, Comparator.comparing(t -> t.title.toLowerCase(Locale.ROOT)));
        return new FolderListing(dir, subfolders, directTracks);
    }

    public List<TrackEntry> collectTracksInFolder(File dir) {
        if (dir == null || !dir.isDirectory()) return Collections.emptyList();
        return scanFoldersInternal(Collections.singletonList(dir));
    }

    private static boolean isAudioFileName(String name) {
        if (name == null) return false;
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return false;
        String ext = name.substring(dot).toLowerCase(Locale.ROOT);
        return AUDIO_EXTS.contains(ext);
    }

    private static List<TrackEntry> scanFoldersInternal(List<File> dirs) {
        List<TrackEntry> out = new ArrayList<>();
        Set<String> seenPaths = new LinkedHashSet<>();
        for (File root : dirs) {
            if (root == null || !root.isDirectory()) continue;
            scanDirRecursive(root, 0, seenPaths, out);
        }
        Collections.sort(out, Comparator.comparing(t -> t.title.toLowerCase(Locale.ROOT)));
        return out;
    }

    private static void scanDirRecursive(File dir, int depth, Set<String> seen, List<TrackEntry> out) {
        if (depth > 8 || dir == null) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            String abs = f.getAbsolutePath();
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".") && seen.add("DIR:" + abs)) {
                    scanDirRecursive(f, depth + 1, seen, out);
                }
            } else if (f.isFile()) {
                String name = f.getName();
                int dot = name.lastIndexOf('.');
                if (dot <= 0) continue;
                String ext = name.substring(dot).toLowerCase(Locale.ROOT);
                if (!AUDIO_EXTS.contains(ext)) continue;
                if (seen.add(abs)) {
                    String title = name.substring(0, dot);
                    String folderLabel = dir.getName().isEmpty() ? dir.getAbsolutePath() : dir.getName();
                    out.add(new TrackEntry(f, title, ext.substring(1).toUpperCase(Locale.ROOT), folderLabel));
                }
            }
        }
    }
}
