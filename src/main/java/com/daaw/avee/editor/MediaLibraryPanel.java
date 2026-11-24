package com.daaw.avee.editor;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/**
 * Avee Player-inspired Media Library browser with cyan top bar, breadcrumb tabs,
 * action toolbar (Play All, Enqueue All, Sort, View), folder/track browser, and Queue view.
 */
public class MediaLibraryPanel {
    public interface HostCallbacks {
        void onPlayTrack(File audioFile);
        void onPlayQueue(List<File> tracks, int startIndex);
        void onEnqueueTracks(List<File> tracks);
        File getCurrentAudioFile();
        List<File> getPlaybackQueue();
        int getPlaybackQueueIndex();
        void onOpenVisualizer();
    }

    private enum Section {
        LIBRARY_ROOT,
        FOLDERS,
        ALL_SONGS,
        QUEUE
    }

    private enum SortMode {
        TITLE_ASC,
        TITLE_DESC,
        EXT,
        FOLDER
    }

    private Section section = Section.FOLDERS;
    private final List<File> folderTrail = new ArrayList<>();
    private boolean initialFolderResolved = false;
    private boolean searchOpen = false;
    private boolean gridView = false;
    private SortMode sortMode = SortMode.TITLE_ASC;
    private final ImString searchBuf = new ImString(128);

    // Light directory listing cache so 60fps ImGui rendering doesn't re-stat disk every frame
    private File cachedListingDir = null;
    private long cachedListingAtMs = 0L;
    private MediaLibraryStore.FolderListing cachedListing = null;

    public void invalidateCache() {
        cachedListingDir = null;
        cachedListingAtMs = 0L;
        cachedListing = null;
    }

    public void renderLibraryPage(MediaLibraryStore store, HostCallbacks callbacks) {
        if (store == null || callbacks == null) return;

        if (!initialFolderResolved) {
            initialFolderResolved = true;
            List<File> roots = store.getFolders();
            if (!roots.isEmpty() && roots.get(0).isDirectory()) {
                section = Section.FOLDERS;
                folderTrail.clear();
                folderTrail.add(roots.get(0));
            }
        }

        // Keep trail valid if root folder was removed
        if (section == Section.FOLDERS && !folderTrail.isEmpty()) {
            File rootInTrail = folderTrail.get(0);
            boolean stillConfigured = false;
            for (File f : store.getFolders()) {
                if (f.getAbsolutePath().equals(rootInTrail.getAbsolutePath())) {
                    stillConfigured = true;
                    break;
                }
            }
            if (!stillConfigured || !folderTrail.get(folderTrail.size() - 1).isDirectory()) {
                folderTrail.clear();
                invalidateCache();
            }
        }

        float fullW = Math.max(240f, ImGui.getContentRegionAvailX());
        float x0 = ImGui.getCursorScreenPosX();
        float y0 = ImGui.getCursorScreenPosY();
        ImDrawList dl = ImGui.getWindowDrawList();

        // 1. Top Cyan App Bar (44px)
        float headerH = 44f;
        int colHeaderBg = ImGui.getColorU32(0.0f, 0.64f, 0.90f, 1.0f);
        int colWhite = ImGui.getColorU32(1.0f, 1.0f, 1.0f, 1.0f);
        int colSoftWhite = ImGui.getColorU32(1.0f, 1.0f, 1.0f, 0.80f);
        dl.addRectFilled(x0, y0, x0 + fullW, y0 + headerH, colHeaderBg);

        ImGui.setCursorScreenPos(x0 + 8f, y0 + 6f);
        boolean canGoBack = (section == Section.FOLDERS && !folderTrail.isEmpty())
                || section == Section.ALL_SONGS
                || section == Section.QUEUE;
        if (canGoBack) {
            if (ImGui.invisibleButton("##LibBackBtn", 32f, 32f)) {
                navigateBack();
            }
            float bx = ImGui.getItemRectMinX() + 16f;
            float by = ImGui.getItemRectMinY() + 16f;
            drawBackArrowIcon(dl, bx, by, colWhite);
            if (ImGui.isItemHovered()) ImGui.setTooltip("Back");
            ImGui.sameLine(0f, 8f);
        }

        ImGui.setCursorScreenPos(x0 + (canGoBack ? 48f : 16f), y0 + 13f);
        dl.addText(ImGui.getCursorScreenPosX(), ImGui.getCursorScreenPosY(), colWhite, "AveeCraft");

        // Search input + search toggle on right of cyan header
        float searchBtnX = x0 + fullW - 42f;
        if (searchOpen) {
            float boxW = Math.min(240f, Math.max(110f, fullW - 220f));
            ImGui.setCursorScreenPos(searchBtnX - boxW - 8f, y0 + 9f);
            ImGui.setNextItemWidth(boxW);
            ImGui.pushStyleColor(ImGuiCol.FrameBg, 0.0f, 0.48f, 0.72f, 1.0f);
            ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 1.0f, 1.0f, 1.0f);
            ImGui.pushStyleColor(ImGuiCol.Border, 1.0f, 1.0f, 1.0f, 0.45f);
            ImGui.inputTextWithHint("##LibSearchInput", "Filter audio & folders...", searchBuf);
            ImGui.popStyleColor(3);
        }
        ImGui.setCursorScreenPos(searchBtnX, y0 + 6f);
        if (ImGui.invisibleButton("##LibSearchBtn", 32f, 32f)) {
            searchOpen = !searchOpen;
            if (!searchOpen) searchBuf.set("");
        }
        drawSearchIcon(dl,
                ImGui.getItemRectMinX() + 16f,
                ImGui.getItemRectMinY() + 16f,
                colWhite);
        if (ImGui.isItemHovered()) ImGui.setTooltip("Filter / Search");

        // 2. Cyan Breadcrumb / Navigation Tab Strip (38px)
        float tabY = y0 + headerH;
        float tabH = 38f;
        int colTabBg = ImGui.getColorU32(0.0f, 0.68f, 0.94f, 1.0f);
        dl.addRectFilled(x0, tabY, x0 + fullW, tabY + tabH, colTabBg);

        float tabCursorX = x0 + 10f;
        tabCursorX = renderBreadcrumbTab(dl, "Library", "tab_library",
                tabCursorX, tabY, tabH, section == Section.LIBRARY_ROOT, colWhite, colSoftWhite,
                () -> {
                    section = Section.LIBRARY_ROOT;
                    folderTrail.clear();
                });

        if (section == Section.LIBRARY_ROOT) {
            tabCursorX = renderBreadcrumbTab(dl, "Folders", "tab_folders_shortcut",
                    tabCursorX, tabY, tabH, false, colWhite, colSoftWhite,
                    () -> {
                        section = Section.FOLDERS;
                        folderTrail.clear();
                        invalidateCache();
                    });
            renderBreadcrumbTab(dl, "All Songs", "tab_songs_shortcut",
                    tabCursorX, tabY, tabH, false, colWhite, colSoftWhite,
                    () -> section = Section.ALL_SONGS);
        } else if (section == Section.FOLDERS) {
            tabCursorX = renderBreadcrumbTab(dl, "Folders", "tab_folders",
                    tabCursorX, tabY, tabH, folderTrail.isEmpty(), colWhite, colSoftWhite,
                    () -> {
                        folderTrail.clear();
                        invalidateCache();
                    });
            for (int i = 0; i < folderTrail.size(); i++) {
                final int idx = i;
                File seg = folderTrail.get(i);
                String name = seg.getName().isEmpty() ? seg.getAbsolutePath() : seg.getName();
                boolean isLast = (i == folderTrail.size() - 1);
                tabCursorX = renderBreadcrumbTab(dl, name, "tab_seg_" + i,
                        tabCursorX, tabY, tabH, isLast, colWhite, colSoftWhite,
                        () -> {
                            while (folderTrail.size() > idx + 1) {
                                folderTrail.remove(folderTrail.size() - 1);
                            }
                            invalidateCache();
                        });
            }
        } else if (section == Section.ALL_SONGS) {
            renderBreadcrumbTab(dl, "All Songs", "tab_allsongs",
                    tabCursorX, tabY, tabH, true, colWhite, colSoftWhite, null);
        } else if (section == Section.QUEUE) {
            renderBreadcrumbTab(dl, "Queue", "tab_queue",
                    tabCursorX, tabY, tabH, true, colWhite, colSoftWhite, null);
        }

        // Gather current view data so action bar buttons know what tracks to act on
        String needle = searchBuf.get().trim().toLowerCase(Locale.ROOT);
        MediaLibraryStore.FolderListing dirListing = null;
        List<MediaLibraryStore.SubfolderEntry> visibleSubfolders = Collections.emptyList();
        List<MediaLibraryStore.TrackEntry> visibleTracks = new ArrayList<>();
        if (section == Section.FOLDERS && !folderTrail.isEmpty()) {
            File curDir = folderTrail.get(folderTrail.size() - 1);
            dirListing = getListingCached(store, curDir);
            visibleSubfolders = filterSubfolders(dirListing.subfolders, needle);
            visibleTracks = filterAndSortTracks(dirListing.tracks, needle);
        } else if (section == Section.ALL_SONGS) {
            visibleTracks = filterAndSortTracks(store.getTracks(), needle);
        }

        // 3. Action Toolbar Strip (54px) matching screenshot's Play All / Enqueue All / Sort / View
        float actY = tabY + tabH;
        float actH = 54f;
        int colActionBg = ImGui.getColorU32(0.965f, 0.972f, 0.985f, 1.0f);
        int colDivider = ImGui.getColorU32(0.86f, 0.88f, 0.91f, 1.0f);
        int colIconDark = ImGui.getColorU32(0.14f, 0.16f, 0.19f, 1.0f);
        int colCaptionGray = ImGui.getColorU32(0.52f, 0.56f, 0.62f, 1.0f);
        dl.addRectFilled(x0, actY, x0 + fullW, actY + actH, colActionBg);
        dl.addLine(x0, actY + actH - 1f, x0 + fullW, actY + actH - 1f, colDivider, 1.0f);

        float ax = x0 + 8f;
        float btnW = 64f;
        if (renderToolbarIconAction(dl, "##ActPlayAll", "Play All", ax, actY + 3f, btnW, 48f,
                colIconDark, colCaptionGray, (cx, cy, c) -> drawPlayAllIcon(dl, cx, cy, c))) {
            List<File> files = resolveBulkActionTracks(store, callbacks, visibleTracks);
            if (!files.isEmpty()) {
                callbacks.onPlayQueue(files, 0);
            }
        }
        ax += btnW + 2f;

        if (renderToolbarIconAction(dl, "##ActEnqueueAll", "Enqueue All", ax, actY + 3f, btnW + 6f, 48f,
                colIconDark, colCaptionGray, (cx, cy, c) -> drawEnqueueAllIcon(dl, cx, cy, c))) {
            List<File> files = resolveBulkActionTracks(store, callbacks, visibleTracks);
            if (!files.isEmpty()) {
                callbacks.onEnqueueTracks(files);
            }
        }
        ax += btnW + 8f;

        if (renderToolbarIconAction(dl, "##ActSort", "Sort", ax, actY + 3f, 52f, 48f,
                colIconDark, colCaptionGray, (cx, cy, c) -> drawSortIcon(dl, cx, cy, c))) {
            ImGui.openPopup("##LibSortPopup");
        }
        ax += 54f;

        if (renderToolbarIconAction(dl, "##ActView", gridView ? "Grid" : "View", ax, actY + 3f, 52f, 48f,
                colIconDark, colCaptionGray, (cx, cy, c) -> drawViewIcon(dl, cx, cy, c, gridView))) {
            gridView = !gridView;
        }

        // Right-aligned folder management controls (+ Folder, Rescan, Visualizer shortcut)
        float utilRight = x0 + fullW - 12f;
        float rescanW = 76f;
        float addFolderW = 94f;
        ImGui.pushStyleColor(ImGuiCol.Button, 0.14f, 0.56f, 0.80f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.16f, 0.65f, 0.90f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0.12f, 0.48f, 0.70f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 1.0f, 1.0f, 1.0f);

        ImGui.setCursorScreenPos(utilRight - rescanW, actY + 14f);
        if (ImGui.button(store.isScanning() ? "Scanning..." : "Rescan", rescanW, 26f)) {
            invalidateCache();
            if (!store.isScanning()) {
                store.rescanAsync();
            }
        }
        ImGui.setCursorScreenPos(utilRight - rescanW - 6f - addFolderW, actY + 14f);
        if (ImGui.button("+ Folder...", addFolderW, 26f)) {
            String start = MediaLibraryStore.defaultMusicFolder().isDirectory()
                    ? MediaLibraryStore.defaultMusicFolder().getAbsolutePath()
                    : System.getProperty("user.home", ".");
            String chosen = TinyFileDialogs.tinyfd_selectFolderDialog("Add Music Folder", start);
            if (chosen != null && !chosen.trim().isEmpty()) {
                File dir = new File(chosen.trim());
                if (store.addFolder(dir)) {
                    invalidateCache();
                    store.rescanAsync();
                    section = Section.FOLDERS;
                    folderTrail.clear();
                    if (dir.isDirectory()) {
                        folderTrail.add(dir.getAbsoluteFile());
                    }
                }
            }
        }
        ImGui.popStyleColor(4);

        if (ImGui.beginPopup("##LibSortPopup")) {
            ImGui.textDisabled("Sort Tracks");
            ImGui.separator();
            if (ImGui.menuItem("Title (A - Z)", null, sortMode == SortMode.TITLE_ASC)) sortMode = SortMode.TITLE_ASC;
            if (ImGui.menuItem("Title (Z - A)", null, sortMode == SortMode.TITLE_DESC)) sortMode = SortMode.TITLE_DESC;
            if (ImGui.menuItem("Format (MP3 / WAV / ...)", null, sortMode == SortMode.EXT)) sortMode = SortMode.EXT;
            if (ImGui.menuItem("Folder Name", null, sortMode == SortMode.FOLDER)) sortMode = SortMode.FOLDER;
            ImGui.endPopup();
        }

        // 4. Clean Light Material Content Area matching Avee Player screenshot
        float bodyY = actY + actH;
        ImGui.setCursorScreenPos(x0, bodyY);
        float bodyH = Math.max(80f, ImGui.getContentRegionAvailY());

        ImGui.pushStyleColor(ImGuiCol.ChildBg, 0.99f, 0.99f, 1.0f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.Text, 0.13f, 0.15f, 0.18f, 1.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.beginChild("##LibContentArea", fullW, bodyH, false, ImGuiWindowFlags.None);

        int itemCount = 0;
        if (section == Section.LIBRARY_ROOT) {
            itemCount = renderLibraryRootRows(store, callbacks);
        } else if (section == Section.FOLDERS) {
            if (folderTrail.isEmpty()) {
                itemCount = renderRootFoldersList(store, callbacks, needle);
            } else {
                itemCount = renderDirectoryContents(store, callbacks, visibleSubfolders, visibleTracks);
            }
        } else if (section == Section.ALL_SONGS) {
            itemCount = renderTrackCollection(callbacks, visibleTracks);
        } else if (section == Section.QUEUE) {
            itemCount = renderQueueList(callbacks, needle);
        }

        // Bottom-right "N item(s)" summary matching Avee Player screenshot
        float footW = ImGui.getContentRegionAvailX();
        String countStr = itemCount + (itemCount == 1 ? " item" : " items");
        float tw = ImGui.calcTextSize(countStr).x;
        ImGui.dummy(1f, 8f);
        ImGui.setCursorPosX(Math.max(12f, footW - tw - 18f));
        ImGui.pushStyleColor(ImGuiCol.Text, 0.56f, 0.59f, 0.65f, 1.0f);
        ImGui.textUnformatted(countStr);
        ImGui.popStyleColor();
        ImGui.dummy(1f, 12f);

        ImGui.endChild();
        ImGui.popStyleVar();
        ImGui.popStyleColor(2);
    }

    private void navigateBack() {
        if (section == Section.FOLDERS) {
            if (!folderTrail.isEmpty()) {
                folderTrail.remove(folderTrail.size() - 1);
                invalidateCache();
            } else {
                section = Section.LIBRARY_ROOT;
            }
        } else {
            section = Section.LIBRARY_ROOT;
        }
    }

    private MediaLibraryStore.FolderListing getListingCached(MediaLibraryStore store, File dir) {
        long now = System.currentTimeMillis();
        if (cachedListing != null && cachedListingDir != null && cachedListingDir.equals(dir)
                && (now - cachedListingAtMs) < 1500L) {
            return cachedListing;
        }
        cachedListing = store.listDirectory(dir);
        cachedListingDir = dir;
        cachedListingAtMs = now;
        return cachedListing;
    }

    private List<MediaLibraryStore.SubfolderEntry> filterSubfolders(List<MediaLibraryStore.SubfolderEntry> subs, String needle) {
        if (needle.isEmpty()) return subs;
        List<MediaLibraryStore.SubfolderEntry> out = new ArrayList<>();
        for (MediaLibraryStore.SubfolderEntry s : subs) {
            if (s.name.toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(s);
            }
        }
        return out;
    }

    private List<MediaLibraryStore.TrackEntry> filterAndSortTracks(List<MediaLibraryStore.TrackEntry> input, String needle) {
        List<MediaLibraryStore.TrackEntry> out = new ArrayList<>();
        for (MediaLibraryStore.TrackEntry t : input) {
            if (needle.isEmpty()
                    || t.title.toLowerCase(Locale.ROOT).contains(needle)
                    || t.folderLabel.toLowerCase(Locale.ROOT).contains(needle)
                    || t.ext.toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(t);
            }
        }
        Comparator<MediaLibraryStore.TrackEntry> cmp;
        switch (sortMode) {
            case TITLE_DESC:
                cmp = Comparator.comparing((MediaLibraryStore.TrackEntry t) -> t.title.toLowerCase(Locale.ROOT)).reversed();
                break;
            case EXT:
                cmp = Comparator.comparing((MediaLibraryStore.TrackEntry t) -> t.ext)
                        .thenComparing(t -> t.title.toLowerCase(Locale.ROOT));
                break;
            case FOLDER:
                cmp = Comparator.comparing((MediaLibraryStore.TrackEntry t) -> t.folderLabel.toLowerCase(Locale.ROOT))
                        .thenComparing(t -> t.title.toLowerCase(Locale.ROOT));
                break;
            default:
                cmp = Comparator.comparing(t -> t.title.toLowerCase(Locale.ROOT));
                break;
        }
        Collections.sort(out, cmp);
        return out;
    }

    private List<File> resolveBulkActionTracks(MediaLibraryStore store, HostCallbacks callbacks,
                                               List<MediaLibraryStore.TrackEntry> visibleTracks) {
        List<File> files = new ArrayList<>();
        if (section == Section.FOLDERS) {
            if (!folderTrail.isEmpty()) {
                File cur = folderTrail.get(folderTrail.size() - 1);
                List<MediaLibraryStore.TrackEntry> rec = filterAndSortTracks(store.collectTracksInFolder(cur),
                        searchBuf.get().trim().toLowerCase(Locale.ROOT));
                for (MediaLibraryStore.TrackEntry t : rec) files.add(t.file);
            } else {
                for (MediaLibraryStore.TrackEntry t : filterAndSortTracks(store.getTracks(),
                        searchBuf.get().trim().toLowerCase(Locale.ROOT))) {
                    files.add(t.file);
                }
            }
        } else if (section == Section.ALL_SONGS) {
            for (MediaLibraryStore.TrackEntry t : visibleTracks) files.add(t.file);
        } else if (section == Section.QUEUE) {
            files.addAll(callbacks.getPlaybackQueue());
        } else {
            for (MediaLibraryStore.TrackEntry t : store.getTracks()) files.add(t.file);
        }
        return files;
    }

    private int renderLibraryRootRows(MediaLibraryStore store, HostCallbacks callbacks) {
        if (renderMaterialFolderRow("root_folders", "Folders", "Configured music locations (~/Music, custom folders)",
                String.valueOf(store.getFolders().size()), false, null)) {
            section = Section.FOLDERS;
            folderTrail.clear();
            invalidateCache();
        }
        if (renderMaterialFolderRow("root_allsongs", "All Songs", "All audio tracks discovered across library folders",
                String.valueOf(store.getTracks().size()), false, null)) {
            section = Section.ALL_SONGS;
        }
        if (renderMaterialFolderRow("root_queue", "Queue", "Current playback queue",
                String.valueOf(callbacks.getPlaybackQueue().size()), false, null)) {
            section = Section.QUEUE;
        }
        return 3;
    }

    private int renderRootFoldersList(MediaLibraryStore store, HostCallbacks callbacks, String needle) {
        List<File> folders = store.getFolders();
        if (folders.isEmpty()) {
            ImGui.setCursorPos(20f, 24f);
            ImGui.pushStyleColor(ImGuiCol.Text, 0.45f, 0.48f, 0.54f, 1.0f);
            ImGui.textUnformatted("No folders added yet. Click '+ Folder...' above to add a music directory.");
            ImGui.popStyleColor();
            return 0;
        }
        int shown = 0;
        File folderToRemove = null;
        for (int i = 0; i < folders.size(); i++) {
            File dir = folders.get(i);
            String name = dir.getName().isEmpty() ? dir.getAbsolutePath() : dir.getName();
            if (!needle.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(needle)
                    && !dir.getAbsolutePath().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            shown++;
            int count = store.countItemsInFolder(dir);
            String sub = dir.isDirectory() ? dir.getAbsolutePath() : (dir.getAbsolutePath() + "  [Missing]");
            String popupId = "##RootDirMenu_" + i;
            boolean openDir = renderMaterialFolderRow("rf_" + i, name, sub, String.valueOf(count),
                    false, popupId);
            if (openDir && dir.isDirectory()) {
                folderTrail.clear();
                folderTrail.add(dir);
                invalidateCache();
            }
            if (ImGui.beginPopup(popupId)) {
                ImGui.pushStyleColor(ImGuiCol.Text, 0.93f, 0.95f, 0.98f, 1.0f);
                if (dir.isDirectory() && ImGui.menuItem("Open Folder")) {
                    folderTrail.clear();
                    folderTrail.add(dir);
                    invalidateCache();
                }
                if (dir.isDirectory() && ImGui.menuItem("Play All")) {
                    List<File> f = toFileList(store.collectTracksInFolder(dir));
                    if (!f.isEmpty()) callbacks.onPlayQueue(f, 0);
                }
                if (dir.isDirectory() && ImGui.menuItem("Enqueue All")) {
                    List<File> f = toFileList(store.collectTracksInFolder(dir));
                    if (!f.isEmpty()) callbacks.onEnqueueTracks(f);
                }
                ImGui.separator();
                if (ImGui.menuItem("Remove Folder")) {
                    folderToRemove = dir;
                }
                ImGui.popStyleColor();
                ImGui.endPopup();
            }
        }
        if (folderToRemove != null && store.removeFolder(folderToRemove)) {
            invalidateCache();
            store.rescanAsync();
        }
        return shown;
    }

    private int renderDirectoryContents(MediaLibraryStore store, HostCallbacks callbacks,
                                        List<MediaLibraryStore.SubfolderEntry> subs,
                                        List<MediaLibraryStore.TrackEntry> tracks) {
        if (subs.isEmpty() && tracks.isEmpty()) {
            ImGui.setCursorPos(20f, 24f);
            ImGui.pushStyleColor(ImGuiCol.Text, 0.45f, 0.48f, 0.54f, 1.0f);
            ImGui.textUnformatted("This folder has no visible subfolders or audio tracks.");
            ImGui.popStyleColor();
            return 0;
        }
        int total = subs.size() + tracks.size();
        for (int i = 0; i < subs.size(); i++) {
            MediaLibraryStore.SubfolderEntry sub = subs.get(i);
            String popupId = "##SubDirMenu_" + i;
            boolean clicked = renderMaterialFolderRow("sub_" + i, sub.name, null,
                    String.valueOf(sub.itemCount), false, popupId);
            if (clicked) {
                folderTrail.add(sub.dir);
                invalidateCache();
                break;
            }
            if (ImGui.beginPopup(popupId)) {
                ImGui.pushStyleColor(ImGuiCol.Text, 0.93f, 0.95f, 0.98f, 1.0f);
                if (ImGui.menuItem("Open")) {
                    folderTrail.add(sub.dir);
                    invalidateCache();
                }
                if (ImGui.menuItem("Play All")) {
                    List<File> files = toFileList(store.collectTracksInFolder(sub.dir));
                    if (!files.isEmpty()) callbacks.onPlayQueue(files, 0);
                }
                if (ImGui.menuItem("Enqueue All")) {
                    List<File> files = toFileList(store.collectTracksInFolder(sub.dir));
                    if (!files.isEmpty()) callbacks.onEnqueueTracks(files);
                }
                ImGui.popStyleColor();
                ImGui.endPopup();
            }
        }
        renderTrackCollection(callbacks, tracks);
        return total;
    }

    private int renderTrackCollection(HostCallbacks callbacks, List<MediaLibraryStore.TrackEntry> tracks) {
        if (tracks.isEmpty()) return 0;
        File current = callbacks.getCurrentAudioFile();
        List<File> allFiles = toFileList(tracks);

        if (gridView) {
            float availW = Math.max(200f, ImGui.getContentRegionAvailX());
            float pad = 12f;
            float tileW = Math.max(150f, (availW - pad * 4f) / Math.max(2, (int) (availW / 190f)));
            int cols = Math.max(1, (int) ((availW - pad) / (tileW + pad)));
            float tileH = 66f;
            ImDrawList dl = ImGui.getWindowDrawList();
            for (int i = 0; i < tracks.size(); i++) {
                MediaLibraryStore.TrackEntry t = tracks.get(i);
                int col = i % cols;
                int row = i / cols;
                if (col == 0) {
                    ImGui.setCursorPos(pad, ImGui.getCursorPosY() + (row == 0 ? pad : 0f));
                } else {
                    ImGui.sameLine(0f, pad);
                }
                boolean playing = current != null && t.file.equals(current);
                float sx = ImGui.getCursorScreenPosX();
                float sy = ImGui.getCursorScreenPosY();
                if (ImGui.invisibleButton("##tile_" + i, tileW, tileH)) {
                    callbacks.onPlayQueue(allFiles, i);
                }
                boolean hov = ImGui.isItemHovered();
                int bg = playing ? ImGui.getColorU32(0.86f, 0.95f, 0.99f, 1.0f)
                        : (hov ? ImGui.getColorU32(0.94f, 0.96f, 0.98f, 1.0f) : ImGui.getColorU32(0.975f, 0.98f, 0.99f, 1.0f));
                int border = playing ? ImGui.getColorU32(0.0f, 0.65f, 0.90f, 1.0f) : ImGui.getColorU32(0.86f, 0.88f, 0.92f, 1.0f);
                dl.addRectFilled(sx, sy, sx + tileW, sy + tileH, bg, 6f);
                dl.addRect(sx, sy, sx + tileW, sy + tileH, border, 6f, 0, 1.2f);
                int iconCol = playing ? ImGui.getColorU32(0.0f, 0.62f, 0.88f, 1.0f) : ImGui.getColorU32(0.20f, 0.24f, 0.28f, 1.0f);
                drawMusicNoteIcon(dl, sx + 22f, sy + tileH * 0.5f, iconCol);
                dl.pushClipRect(sx + 40f, sy, sx + tileW - 8f, sy + tileH, true);
                dl.addText(sx + 42f, sy + 14f, iconCol, t.title);
                dl.addText(sx + 42f, sy + 36f, ImGui.getColorU32(0.50f, 0.54f, 0.60f, 1.0f), t.ext + " • " + t.folderLabel);
                dl.popClipRect();
                if (col == cols - 1 || i == tracks.size() - 1) {
                    ImGui.dummy(1f, 6f);
                }
            }
            return tracks.size();
        }

        for (int i = 0; i < tracks.size(); i++) {
            MediaLibraryStore.TrackEntry t = tracks.get(i);
            boolean playing = current != null && t.file.equals(current);
            String popupId = "##TrackMenu_" + i;
            boolean clicked = renderMaterialTrackRow("tr_" + i, t.title, t.folderLabel, t.ext, playing, popupId);
            if (clicked) {
                callbacks.onPlayQueue(allFiles, i);
            }
            if (ImGui.beginPopup(popupId)) {
                ImGui.pushStyleColor(ImGuiCol.Text, 0.93f, 0.95f, 0.98f, 1.0f);
                if (ImGui.menuItem("Play Now")) {
                    callbacks.onPlayQueue(allFiles, i);
                }
                if (ImGui.menuItem("Enqueue")) {
                    callbacks.onEnqueueTracks(Collections.singletonList(t.file));
                }
                ImGui.separator();
                if (ImGui.menuItem("Open in Visualizer")) {
                    callbacks.onPlayQueue(allFiles, i);
                    callbacks.onOpenVisualizer();
                }
                ImGui.popStyleColor();
                ImGui.endPopup();
            }
        }
        return tracks.size();
    }

    private int renderQueueList(HostCallbacks callbacks, String needle) {
        List<File> q = callbacks.getPlaybackQueue();
        int curIdx = callbacks.getPlaybackQueueIndex();
        if (q.isEmpty()) {
            ImGui.setCursorPos(20f, 24f);
            ImGui.pushStyleColor(ImGuiCol.Text, 0.45f, 0.48f, 0.54f, 1.0f);
            ImGui.textUnformatted("Queue is empty. Play or Enqueue songs from Folders or All Songs.");
            ImGui.popStyleColor();
            return 0;
        }
        int shown = 0;
        for (int i = 0; i < q.size(); i++) {
            File f = q.get(i);
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            String title = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot + 1).toUpperCase(Locale.ROOT) : "AUDIO";
            if (!needle.isEmpty() && !title.toLowerCase(Locale.ROOT).contains(needle)) continue;
            shown++;
            boolean active = (i == curIdx);
            if (renderMaterialTrackRow("q_" + i, (i + 1) + ". " + title, f.getParentFile() != null ? f.getParentFile().getName() : "",
                    ext, active, null)) {
                callbacks.onPlayQueue(q, i);
            }
        }
        return shown;
    }

    private boolean renderMaterialFolderRow(String uid, String title, String subtitle, String countText,
                                            boolean highlight, String popupId) {
        float rowW = Math.max(120f, ImGui.getContentRegionAvailX());
        float rowH = (subtitle != null && !subtitle.isEmpty()) ? 56f : 48f;
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImDrawList dl = ImGui.getWindowDrawList();

        float actionReserve = popupId != null ? 42f : 8f;
        boolean rowClicked = ImGui.invisibleButton("##row_" + uid, Math.max(40f, rowW - actionReserve), rowH);
        boolean rowHovered = ImGui.isItemHovered();

        boolean moreClicked = false;
        if (popupId != null) {
            ImGui.setCursorScreenPos(x + rowW - 38f, y + (rowH - 32f) * 0.5f);
            moreClicked = ImGui.invisibleButton("##more_" + uid, 30f, 32f);
            if (ImGui.isItemHovered()) rowHovered = true;
            ImGui.setCursorScreenPos(x, y + rowH);
            ImGui.dummy(0f, 0f);
        }

        if (rowHovered) {
            dl.addRectFilled(x, y, x + rowW, y + rowH - 1f, ImGui.getColorU32(0.935f, 0.962f, 0.988f, 1.0f));
        } else if (highlight) {
            dl.addRectFilled(x, y, x + rowW, y + rowH - 1f, ImGui.getColorU32(0.89f, 0.96f, 0.995f, 1.0f));
        }

        int iconCol = ImGui.getColorU32(0.14f, 0.16f, 0.18f, 1.0f);
        drawFolderIcon(dl, x + 28f, y + rowH * 0.5f, iconCol);

        int titleCol = ImGui.getColorU32(0.15f, 0.17f, 0.20f, 1.0f);
        int subCol = ImGui.getColorU32(0.54f, 0.58f, 0.63f, 1.0f);
        float textX = x + 64f;
        float countW = countText != null ? ImGui.calcTextSize(countText).x : 0f;
        float rightTextLimit = x + rowW - actionReserve - countW - 18f;

        dl.pushClipRect(textX, y, Math.max(textX + 20f, rightTextLimit), y + rowH, true);
        if (subtitle != null && !subtitle.isEmpty()) {
            dl.addText(textX, y + 10f, titleCol, title);
            dl.addText(textX, y + 30f, subCol, subtitle);
        } else {
            dl.addText(textX, y + (rowH - ImGui.getFontSize()) * 0.5f, titleCol, title);
        }
        dl.popClipRect();

        if (countText != null) {
            dl.addText(x + rowW - actionReserve - countW - 8f, y + (rowH - ImGui.getFontSize()) * 0.5f, subCol, countText);
        }
        if (popupId != null) {
            drawMoreDotsIcon(dl, x + rowW - 22f, y + rowH * 0.5f, subCol);
            if (moreClicked) {
                ImGui.openPopup(popupId);
            }
        }
        dl.addLine(x + 12f, y + rowH - 1f, x + rowW - 12f, y + rowH - 1f, ImGui.getColorU32(0.90f, 0.91f, 0.93f, 1.0f), 1.0f);
        return rowClicked;
    }

    private boolean renderMaterialTrackRow(String uid, String title, String folderLabel, String ext,
                                           boolean isPlaying, String popupId) {
        float rowW = Math.max(120f, ImGui.getContentRegionAvailX());
        float rowH = 52f;
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImDrawList dl = ImGui.getWindowDrawList();

        float actionReserve = popupId != null ? 42f : 8f;
        boolean clicked = ImGui.invisibleButton("##trow_" + uid, Math.max(40f, rowW - actionReserve), rowH);
        boolean hovered = ImGui.isItemHovered();

        boolean moreClicked = false;
        if (popupId != null) {
            ImGui.setCursorScreenPos(x + rowW - 38f, y + (rowH - 32f) * 0.5f);
            moreClicked = ImGui.invisibleButton("##tmore_" + uid, 30f, 32f);
            if (ImGui.isItemHovered()) hovered = true;
            ImGui.setCursorScreenPos(x, y + rowH);
            ImGui.dummy(0f, 0f);
        }

        if (isPlaying) {
            dl.addRectFilled(x, y, x + rowW, y + rowH - 1f, ImGui.getColorU32(0.87f, 0.95f, 0.995f, 1.0f));
        } else if (hovered) {
            dl.addRectFilled(x, y, x + rowW, y + rowH - 1f, ImGui.getColorU32(0.945f, 0.968f, 0.99f, 1.0f));
        }

        int iconCol = isPlaying ? ImGui.getColorU32(0.0f, 0.58f, 0.85f, 1.0f) : ImGui.getColorU32(0.24f, 0.27f, 0.32f, 1.0f);
        drawMusicNoteIcon(dl, x + 28f, y + rowH * 0.5f, iconCol);

        int titleCol = isPlaying ? ImGui.getColorU32(0.0f, 0.48f, 0.76f, 1.0f) : ImGui.getColorU32(0.14f, 0.16f, 0.20f, 1.0f);
        int subCol = ImGui.getColorU32(0.54f, 0.58f, 0.63f, 1.0f);
        float textX = x + 64f;
        float extW = ImGui.calcTextSize(ext).x;
        float limitX = x + rowW - actionReserve - extW - 22f;

        dl.pushClipRect(textX, y, Math.max(textX + 20f, limitX), y + rowH, true);
        dl.addText(textX, y + 9f, titleCol, title);
        dl.addText(textX, y + 28f, subCol, folderLabel);
        dl.popClipRect();

        dl.addText(x + rowW - actionReserve - extW - 10f, y + (rowH - ImGui.getFontSize()) * 0.5f, subCol, ext);
        if (popupId != null) {
            drawMoreDotsIcon(dl, x + rowW - 22f, y + rowH * 0.5f, subCol);
            if (moreClicked) {
                ImGui.openPopup(popupId);
            }
        }
        dl.addLine(x + 12f, y + rowH - 1f, x + rowW - 12f, y + rowH - 1f, ImGui.getColorU32(0.90f, 0.91f, 0.93f, 1.0f), 1.0f);
        return clicked;
    }

    private float renderBreadcrumbTab(ImDrawList dl, String label, String uid,
                                      float x, float y, float h, boolean active,
                                      int colWhite, int colSoftWhite, Runnable onClick) {
        float textW = ImGui.calcTextSize(label).x;
        float tabW = Math.max(60f, textW + 28f);
        ImGui.setCursorScreenPos(x, y);
        boolean pressed = ImGui.invisibleButton("##" + uid, tabW, h);
        boolean hov = ImGui.isItemHovered();
        if (hov) {
            dl.addRectFilled(x, y, x + tabW, y + h, ImGui.getColorU32(1.0f, 1.0f, 1.0f, 0.12f));
        }
        dl.addText(x + (tabW - textW) * 0.5f, y + (h - ImGui.getFontSize()) * 0.5f,
                active ? colWhite : colSoftWhite, label);
        if (active) {
            dl.addRectFilled(x, y + h - 3.0f, x + tabW, y + h, colWhite);
        }
        if (pressed && onClick != null) {
            onClick.run();
        }
        return x + tabW;
    }

    private interface IconPainter {
        void draw(float cx, float cy, int color);
    }

    private boolean renderToolbarIconAction(ImDrawList dl, String uid, String caption,
                                            float x, float y, float w, float h,
                                            int iconCol, int captionCol, IconPainter painter) {
        ImGui.setCursorScreenPos(x, y);
        boolean clicked = ImGui.invisibleButton(uid, w, h);
        if (ImGui.isItemHovered()) {
            dl.addRectFilled(x + 2f, y + 2f, x + w - 2f, y + h - 2f, ImGui.getColorU32(0.88f, 0.91f, 0.95f, 0.7f), 5f);
        }
        painter.draw(x + w * 0.5f, y + 17f, iconCol);
        float tw = ImGui.calcTextSize(caption).x;
        dl.addText(x + (w - tw) * 0.5f, y + 32f, captionCol, caption);
        return clicked;
    }

    private static List<File> toFileList(List<MediaLibraryStore.TrackEntry> entries) {
        List<File> out = new ArrayList<>(entries.size());
        for (MediaLibraryStore.TrackEntry e : entries) {
            out.add(e.file);
        }
        return out;
    }

    // ---- Crisp ImDrawList vector icons (no emoji/font glyph gaps) ----

    private static void drawBackArrowIcon(ImDrawList dl, float cx, float cy, int col) {
        dl.addLine(cx + 4f, cy - 6f, cx - 3f, cy, col, 2.2f);
        dl.addLine(cx - 3f, cy, cx + 4f, cy + 6f, col, 2.2f);
    }

    private static void drawSearchIcon(ImDrawList dl, float cx, float cy, int col) {
        dl.addCircle(cx - 2f, cy - 2f, 5.5f, col, 16, 2.0f);
        dl.addLine(cx + 2f, cy + 2f, cx + 7f, cy + 7f, col, 2.2f);
    }

    private static void drawPlayAllIcon(ImDrawList dl, float cx, float cy, int col) {
        dl.addTriangleFilled(cx - 10f, cy - 6f, cx - 10f, cy + 2f, cx - 3f, cy - 2f, col);
        dl.addLine(cx - 1f, cy - 5f, cx + 10f, cy - 5f, col, 2.2f);
        dl.addLine(cx - 1f, cy, cx + 10f, cy, col, 2.2f);
        dl.addLine(cx - 10f, cy + 5f, cx + 10f, cy + 5f, col, 2.2f);
    }

    private static void drawEnqueueAllIcon(ImDrawList dl, float cx, float cy, int col) {
        dl.addLine(cx - 10f, cy - 5f, cx + 9f, cy - 5f, col, 2.2f);
        dl.addLine(cx - 10f, cy, cx + 9f, cy, col, 2.2f);
        dl.addLine(cx - 10f, cy + 5f, cx + 1f, cy + 5f, col, 2.2f);
        dl.addLine(cx + 6f, cy + 2f, cx + 6f, cy + 8f, col, 2.0f);
        dl.addLine(cx + 3f, cy + 5f, cx + 9f, cy + 5f, col, 2.0f);
    }

    private static void drawSortIcon(ImDrawList dl, float cx, float cy, int col) {
        dl.addLine(cx - 6f, cy - 6f, cx - 6f, cy + 6f, col, 2.0f);
        dl.addTriangleFilled(cx - 9f, cy + 3f, cx - 3f, cy + 3f, cx - 6f, cy + 7f, col);
        dl.addLine(cx - 1f, cy - 5f, cx + 9f, cy - 5f, col, 2.2f);
        dl.addLine(cx - 1f, cy, cx + 6f, cy, col, 2.2f);
        dl.addLine(cx - 1f, cy + 5f, cx + 3f, cy + 5f, col, 2.2f);
    }

    private static void drawViewIcon(ImDrawList dl, float cx, float cy, int col, boolean isGrid) {
        if (isGrid) {
            dl.addRectFilled(cx - 9f, cy - 6f, cx - 1f, cy - 1f, col, 1f);
            dl.addRectFilled(cx + 1f, cy - 6f, cx + 9f, cy - 1f, col, 1f);
            dl.addRectFilled(cx - 9f, cy + 1f, cx - 1f, cy + 6f, col, 1f);
            dl.addRectFilled(cx + 1f, cy + 1f, cx + 9f, cy + 6f, col, 1f);
        } else {
            dl.addRectFilled(cx - 9f, cy - 6f, cx - 1f, cy, col, 1f);
            dl.addRectFilled(cx + 1f, cy - 6f, cx + 9f, cy + 6f, col, 1f);
            dl.addRectFilled(cx - 9f, cy + 2f, cx - 1f, cy + 6f, col, 1f);
        }
    }

    private static void drawFolderIcon(ImDrawList dl, float cx, float cy, int col) {
        dl.addRectFilled(cx - 11f, cy - 8f, cx - 2f, cy - 4f, col, 2f);
        dl.addRectFilled(cx - 11f, cy - 5f, cx + 11f, cy + 8f, col, 2f);
    }

    private static void drawMusicNoteIcon(ImDrawList dl, float cx, float cy, int col) {
        dl.addCircleFilled(cx - 3f, cy + 5f, 3.5f, col, 12);
        dl.addLine(cx, cy + 5f, cx, cy - 8f, col, 2.2f);
        dl.addTriangleFilled(cx, cy - 8f, cx + 7f, cy - 4f, cx, cy - 2f, col);
    }

    private static void drawMoreDotsIcon(ImDrawList dl, float cx, float cy, int col) {
        dl.addCircleFilled(cx, cy - 6f, 1.8f, col, 8);
        dl.addCircleFilled(cx, cy, 1.8f, col, 8);
        dl.addCircleFilled(cx, cy + 6f, 1.8f, col, 8);
    }
}
