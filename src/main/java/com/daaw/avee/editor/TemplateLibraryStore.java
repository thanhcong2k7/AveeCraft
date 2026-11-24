package com.daaw.avee.editor;

import com.daaw.avee.Common.tlog;
import com.daaw.avee.comp.AppPreferences.AppPreferences;
import com.daaw.avee.comp.Visualizer.CustomScene;
import com.daaw.avee.comp.Visualizer.Graphic.RendererCore;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Private template store (~/.aveecraft/.library) with built-in + imported templates,
 * cached live preview thumbnails, and non-blocking off-main-thread coalesced auto-save.
 */
public class TemplateLibraryStore {
    public static final int BUILT_IN_COUNT = 18;
    public static final int THUMB_W = 320;
    public static final int THUMB_H = 180;

    public static class TemplateEntry {
        public final String id;
        public String title;
        public final boolean builtIn;
        public final int builtInIndex;
        public volatile boolean modified;
        public volatile long updatedAtMs;
        public volatile long previewRevision;
        public final File dir;
        public final File sceneFile;
        public final File assetsDir;
        public final File previewFile;
        public final File metaFile;

        TemplateEntry(String id, String title, boolean builtIn, int builtInIndex,
                      boolean modified, long updatedAtMs, File libraryRoot) {
            this.id = id;
            this.title = title;
            this.builtIn = builtIn;
            this.builtInIndex = builtInIndex;
            this.modified = modified;
            this.updatedAtMs = updatedAtMs;
            this.dir = new File(libraryRoot, id);
            this.sceneFile = new File(this.dir, "scene.json");
            this.assetsDir = new File(this.dir, "assets");
            this.previewFile = new File(this.dir, "preview.png");
            this.metaFile = new File(this.dir, "meta.json");
            this.previewRevision = this.previewFile.isFile() ? this.previewFile.lastModified() : 0L;
        }
    }

    private final File libraryRoot;
    private final File indexFile;
    private final Map<String, TemplateEntry> entriesById = new LinkedHashMap<>();
    private final Map<String, Long> latestScheduledSeq = new ConcurrentHashMap<>();
    private final AtomicLong seqGen = new AtomicLong(0L);
    private final AtomicLong completedSaveCount = new AtomicLong(0L);
    private final AtomicInteger pendingTasks = new AtomicInteger(0);
    private final ExecutorService saveExecutor;

    private String lastSelectedId = presetId(0);
    private String lastSaveThreadName = "";

    public TemplateLibraryStore() {
        this(defaultLibraryDir());
    }

    public TemplateLibraryStore(File libraryDir) {
        this.libraryRoot = libraryDir != null ? libraryDir : defaultLibraryDir();
        this.indexFile = new File(this.libraryRoot, "index.json");
        this.saveExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AveeTemplateAutoSave");
            t.setDaemon(true);
            return t;
        });
        initLibrary();
    }

    public static File defaultLibraryDir() {
        String home = System.getProperty("user.home", ".");
        return new File(new File(home, ".aveecraft"), ".library");
    }

    public static String presetId(int idx) {
        return String.format(Locale.US, "preset_%02d", idx);
    }

    public File getLibraryRoot() {
        return libraryRoot;
    }

    public synchronized String getLastSelectedId() {
        return entriesById.containsKey(lastSelectedId) ? lastSelectedId : presetId(0);
    }

    public boolean isSavePending() {
        return pendingTasks.get() > 0;
    }

    public long getCompletedSaveCount() {
        return completedSaveCount.get();
    }

    public String getLastSaveThreadName() {
        return lastSaveThreadName;
    }

    public synchronized List<TemplateEntry> getEntries() {
        return new ArrayList<>(entriesById.values());
    }

    public synchronized TemplateEntry getEntry(String id) {
        return entriesById.get(id);
    }

    private synchronized void initLibrary() {
        libraryRoot.mkdirs();

        // Ensure all 18 built-in slots exist in private storage
        for (int i = 0; i < BUILT_IN_COUNT; i++) {
            String id = presetId(i);
            TemplateEntry e = loadOrCreateBuiltIn(id, i);
            entriesById.put(id, e);
        }

        // Load index for custom/imported order + last selected template
        if (indexFile.isFile()) {
            try {
                JSONObject idxObj = new JSONObject(Files.readString(indexFile.toPath(), StandardCharsets.UTF_8));
                String savedSel = idxObj.optString("lastSelectedId", presetId(0));
                if (!savedSel.isEmpty()) {
                    lastSelectedId = savedSel;
                }
                JSONArray customArr = idxObj.optJSONArray("customIds");
                if (customArr != null) {
                    for (int i = 0; i < customArr.length(); i++) {
                        String cid = customArr.optString(i, "");
                        if (cid.isEmpty() || entriesById.containsKey(cid)) continue;
                        TemplateEntry ce = loadExistingCustomEntry(cid);
                        if (ce != null) {
                            entriesById.put(cid, ce);
                        }
                    }
                }
            } catch (Throwable t) {
                tlog.w("Failed loading template index: " + t.getMessage());
            }
        }

        // Also discover any custom folders in libraryRoot that may not have been indexed
        File[] children = libraryRoot.listFiles();
        if (children != null) {
            java.util.Arrays.sort(children, Comparator.comparing(File::getName));
            for (File sub : children) {
                if (!sub.isDirectory()) continue;
                String id = sub.getName();
                if (entriesById.containsKey(id)) continue;
                if (new File(sub, "scene.json").isFile()) {
                    TemplateEntry ce = loadExistingCustomEntry(id);
                    if (ce != null) {
                        entriesById.put(id, ce);
                    }
                }
            }
        }
        writeIndexSync();
    }

    private TemplateEntry loadOrCreateBuiltIn(String id, int index) {
        File dir = new File(libraryRoot, id);
        File metaFile = new File(dir, "meta.json");
        File sceneFile = new File(dir, "scene.json");
        File assetsDir = new File(dir, "assets");
        dir.mkdirs();
        assetsDir.mkdirs();

        String title = "Preset " + index;
        boolean modified = false;
        long updated = 0L;
        if (metaFile.isFile()) {
            try {
                JSONObject m = new JSONObject(Files.readString(metaFile.toPath(), StandardCharsets.UTF_8));
                title = m.optString("title", title);
                modified = m.optBoolean("modified", false);
                updated = m.optLong("updatedAtMs", 0L);
            } catch (Throwable ignored) {
            }
        }
        if (!sceneFile.isFile()) {
            String raw = readBuiltInPresetJson(index);
            if (raw != null) {
                try {
                    atomicWriteUtf8(sceneFile, raw);
                } catch (IOException ex) {
                    tlog.w("Failed materializing preset " + index + ": " + ex.getMessage());
                }
            }
        }
        TemplateEntry e = new TemplateEntry(id, title, true, index, modified, updated, libraryRoot);
        if (!metaFile.isFile()) {
            writeMetaSync(e);
        }
        return e;
    }

    private TemplateEntry loadExistingCustomEntry(String id) {
        File dir = new File(libraryRoot, id);
        File sceneFile = new File(dir, "scene.json");
        if (!dir.isDirectory() || !sceneFile.isFile()) return null;
        File assetsDir = new File(dir, "assets");
        assetsDir.mkdirs();
        String title = id;
        boolean modified = false;
        long updated = sceneFile.lastModified();
        File metaFile = new File(dir, "meta.json");
        if (metaFile.isFile()) {
            try {
                JSONObject m = new JSONObject(Files.readString(metaFile.toPath(), StandardCharsets.UTF_8));
                title = m.optString("title", id);
                modified = m.optBoolean("modified", false);
                updated = m.optLong("updatedAtMs", updated);
            } catch (Throwable ignored) {
            }
        }
        return new TemplateEntry(id, title, false, -1, modified, updated, libraryRoot);
    }

    private static String readBuiltInPresetJson(int index) {
        String resPath = "/raw/visualizer_" + index + ".json";
        try (InputStream is = TemplateLibraryStore.class.getResourceAsStream(resPath)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    public CustomScene loadTemplate(String id) throws IOException {
        TemplateEntry entry;
        synchronized (this) {
            entry = entriesById.get(id);
            if (entry == null) {
                throw new IOException("Template not found: " + id);
            }
            lastSelectedId = id;
        }
        entry.assetsDir.mkdirs();
        AppPreferences.setActiveWorkspaceFolder(entry.assetsDir);
        String json;
        if (entry.sceneFile.isFile()) {
            json = Files.readString(entry.sceneFile.toPath(), StandardCharsets.UTF_8);
        } else if (entry.builtIn) {
            json = readBuiltInPresetJson(entry.builtInIndex);
            if (json == null) throw new IOException("Built-in preset missing: " + entry.builtInIndex);
            atomicWriteUtf8(entry.sceneFile, json);
        } else {
            throw new IOException("Template scene missing: " + entry.id);
        }
        CustomScene scene = CustomScene.deserialize(json);
        if (scene == null) {
            throw new IOException("Invalid template JSON: " + entry.id);
        }
        scheduleIndexWrite();
        return scene;
    }

    /**
     * Imports a .viz or .json template into the app-private ~/.aveecraft/.library store.
     */
    public TemplateEntry importTemplateFile(File externalFile, String optionalTitle) throws IOException {
        if (externalFile == null || !externalFile.isFile()) {
            throw new IOException("Import file does not exist");
        }
        String rawName = (optionalTitle != null && !optionalTitle.trim().isEmpty())
                ? optionalTitle.trim()
                : stripExt(externalFile.getName());
        if (rawName.isEmpty()) rawName = "Imported Template";

        String id = "imp_" + System.currentTimeMillis() + "_" + seqGen.incrementAndGet();
        TemplateEntry entry = new TemplateEntry(id, rawName, false, -1, false, System.currentTimeMillis(), libraryRoot);
        entry.dir.mkdirs();
        entry.assetsDir.mkdirs();

        CustomScene scene = VizArchiveManager.loadFileIntoWorkspace(externalFile, entry.assetsDir);
        atomicWriteUtf8(entry.sceneFile, scene.serializePretty());
        writeMetaSync(entry);

        synchronized (this) {
            entriesById.put(id, entry);
            lastSelectedId = id;
            writeIndexSync();
        }
        return entry;
    }

    public TemplateEntry duplicateTemplate(String sourceId) throws IOException {
        TemplateEntry src;
        synchronized (this) {
            src = entriesById.get(sourceId);
            if (src == null) throw new IOException("Source template not found");
        }
        flushPendingSavesSync(2000L);
        String id = "imp_" + System.currentTimeMillis() + "_" + seqGen.incrementAndGet();
        TemplateEntry copy = new TemplateEntry(id, src.title + " Copy", false, -1, true, System.currentTimeMillis(), libraryRoot);
        copy.dir.mkdirs();
        copy.assetsDir.mkdirs();
        if (src.sceneFile.isFile()) {
            Files.copy(src.sceneFile.toPath(), copy.sceneFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        copyDirContents(src.assetsDir, copy.assetsDir);
        if (src.previewFile.isFile()) {
            Files.copy(src.previewFile.toPath(), copy.previewFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            copy.previewRevision = copy.previewFile.lastModified();
        }
        writeMetaSync(copy);
        synchronized (this) {
            entriesById.put(id, copy);
            lastSelectedId = id;
            writeIndexSync();
        }
        return copy;
    }

    public CustomScene resetBuiltInPreset(String id) throws IOException {
        TemplateEntry entry;
        synchronized (this) {
            entry = entriesById.get(id);
            if (entry == null || !entry.builtIn) {
                throw new IOException("Not a built-in preset: " + id);
            }
            lastSelectedId = id;
        }
        String raw = readBuiltInPresetJson(entry.builtInIndex);
        if (raw == null) throw new IOException("Missing preset JSON " + entry.builtInIndex);
        entry.modified = false;
        entry.updatedAtMs = System.currentTimeMillis();
        atomicWriteUtf8(entry.sceneFile, raw);
        writeMetaSync(entry);
        if (entry.previewFile.isFile()) {
            entry.previewFile.delete();
        }
        entry.previewRevision = 0L;
        writeIndexSync();
        AppPreferences.setActiveWorkspaceFolder(entry.assetsDir);
        return CustomScene.deserialize(raw);
    }

    public synchronized boolean deleteTemplate(String id) {
        TemplateEntry entry = entriesById.get(id);
        if (entry == null || entry.builtIn) return false;
        entriesById.remove(id);
        latestScheduledSeq.remove(id);
        deleteRecursively(entry.dir);
        if (id.equals(lastSelectedId)) {
            lastSelectedId = presetId(0);
        }
        writeIndexSync();
        return true;
    }

    public void exportViz(String id, File outZip, CustomScene liveSceneOverride, RendererCore liveRenderer) throws IOException {
        flushPendingSavesSync(3000L);
        TemplateEntry entry = getEntry(id);
        if (entry == null) throw new IOException("Template not found: " + id);
        CustomScene scene = liveSceneOverride != null
                ? VizArchiveManager.readLiveScene(liveSceneOverride, liveRenderer, null)
                : loadTemplate(id);
        VizArchiveManager.saveVizFromWorkspace(outZip, scene, liveSceneOverride != null ? liveRenderer : null, entry.assetsDir);
    }

    /**
     * Snapshots the scene JSON in memory and queues an atomic off-main-thread write.
     * Coalesces rapid bursts so only the latest edit per template touches disk.
     */
    public long scheduleAutoSave(String id, CustomScene sceneSnapshot, File activeWorkspaceDir) {
        if (id == null || sceneSnapshot == null) return -1L;
        TemplateEntry entry = getEntry(id);
        if (entry == null) return -1L;

        final String prettyJson = sceneSnapshot.serializePretty();
        final long mySeq = seqGen.incrementAndGet();
        latestScheduledSeq.put(id, mySeq);
        final File wsCopySource = (activeWorkspaceDir != null && !activeWorkspaceDir.equals(entry.assetsDir))
                ? activeWorkspaceDir : null;
        pendingTasks.incrementAndGet();

        saveExecutor.submit(() -> {
            try {
                lastSaveThreadName = Thread.currentThread().getName();
                Long newest = latestScheduledSeq.get(id);
                if (newest != null && newest.longValue() > mySeq) {
                    return; // Coalesced by a newer pending save
                }
                entry.dir.mkdirs();
                entry.assetsDir.mkdirs();
                if (wsCopySource != null && wsCopySource.isDirectory()) {
                    copyDirContents(wsCopySource, entry.assetsDir);
                }
                atomicWriteUtf8(entry.sceneFile, prettyJson);
                entry.modified = true;
                entry.updatedAtMs = System.currentTimeMillis();
                writeMetaSync(entry);
                completedSaveCount.incrementAndGet();
            } catch (Throwable t) {
                tlog.w("Background auto-save failed for " + id + ": " + t.getMessage());
            } finally {
                pendingTasks.decrementAndGet();
            }
        });
        return mySeq;
    }

    /**
     * Offloads PNG encoding + disk write of a preview thumbnail onto the background thread.
     */
    public void schedulePreviewSave(String id, BufferedImage rawImage) {
        if (id == null || rawImage == null) return;
        TemplateEntry entry = getEntry(id);
        if (entry == null) return;
        pendingTasks.incrementAndGet();
        saveExecutor.submit(() -> {
            try {
                lastSaveThreadName = Thread.currentThread().getName();
                BufferedImage scaled = scaleThumb(rawImage, THUMB_W, THUMB_H);
                entry.dir.mkdirs();
                File tmp = new File(entry.dir, "preview.png.tmp");
                ImageIO.write(scaled, "png", tmp);
                moveAtomic(tmp.toPath(), entry.previewFile.toPath());
                entry.previewRevision = Math.max(entry.previewRevision + 1L, entry.previewFile.lastModified());
                completedSaveCount.incrementAndGet();
            } catch (Throwable t) {
                tlog.w("Background preview save failed for " + id + ": " + t.getMessage());
            } finally {
                pendingTasks.decrementAndGet();
            }
        });
    }

    public boolean flushPendingSavesSync(long timeoutMs) {
        if (saveExecutor.isShutdown()) return true;
        CountDownLatch latch = new CountDownLatch(1);
        saveExecutor.submit(latch::countDown);
        try {
            return latch.await(Math.max(50L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void shutdown() {
        flushPendingSavesSync(3000L);
        saveExecutor.shutdown();
        try {
            saveExecutor.awaitTermination(2000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void scheduleIndexWrite() {
        pendingTasks.incrementAndGet();
        saveExecutor.submit(() -> {
            try {
                writeIndexSync();
            } finally {
                pendingTasks.decrementAndGet();
            }
        });
    }

    private synchronized void writeIndexSync() {
        try {
            libraryRoot.mkdirs();
            JSONObject obj = new JSONObject();
            obj.put("lastSelectedId", lastSelectedId);
            JSONArray customIds = new JSONArray();
            for (TemplateEntry e : entriesById.values()) {
                if (!e.builtIn) {
                    customIds.put(e.id);
                }
            }
            obj.put("customIds", customIds);
            atomicWriteUtf8(indexFile, obj.toString(2));
        } catch (Throwable t) {
            tlog.w("Failed saving template index: " + t.getMessage());
        }
    }

    private static void writeMetaSync(TemplateEntry entry) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("id", entry.id);
            obj.put("title", entry.title);
            obj.put("builtIn", entry.builtIn);
            obj.put("builtInIndex", entry.builtInIndex);
            obj.put("modified", entry.modified);
            obj.put("updatedAtMs", entry.updatedAtMs);
            atomicWriteUtf8(entry.metaFile, obj.toString(2));
        } catch (Throwable ignored) {
        }
    }

    private static void atomicWriteUtf8(File target, String content) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(parent, target.getName() + ".tmp");
        Files.writeString(tmp.toPath(), content, StandardCharsets.UTF_8);
        moveAtomic(tmp.toPath(), target.toPath());
    }

    private static void moveAtomic(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static BufferedImage scaleThumb(BufferedImage src, int tw, int th) {
        if (src.getWidth() == tw && src.getHeight() == th) {
            return src;
        }
        BufferedImage dst = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, tw, th, null);
        g.dispose();
        return dst;
    }

    private static void copyDirContents(File srcDir, File dstDir) throws IOException {
        if (srcDir == null || !srcDir.isDirectory()) return;
        dstDir.mkdirs();
        File[] list = srcDir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (!f.isFile()) continue;
            File dest = new File(dstDir, f.getName());
            if (!dest.exists() || dest.length() != f.length() || dest.lastModified() < f.lastModified()) {
                Files.copy(f.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        f.delete();
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
