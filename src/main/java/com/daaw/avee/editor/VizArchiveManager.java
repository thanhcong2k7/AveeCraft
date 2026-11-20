package com.daaw.avee.editor;

import com.daaw.avee.Common.tlog;
import com.daaw.avee.comp.AppPreferences.AppPreferences;
import com.daaw.avee.comp.Visualizer.CustomScene;
import com.daaw.avee.comp.Visualizer.Graphic.RendererCore;
import com.daaw.avee.comp.Visualizer.IDependencyResourceCounter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class VizArchiveManager {
    private static final Pattern LOCAL_PATTERN = Pattern.compile("\"local:([^\"\\\\]+)\"");

    public static CustomScene loadPreset(int index) throws IOException {
        File ws = Files.createTempDirectory("avee_ws_preset_").toFile();
        AppPreferences.setActiveWorkspaceFolder(ws);
        String resPath = "/raw/visualizer_" + index + ".json";
        try (InputStream is = VizArchiveManager.class.getResourceAsStream(resPath)) {
            if (is == null) throw new IOException("Preset not found: " + resPath);
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            CustomScene scene = CustomScene.deserialize(json);
            if (scene == null) throw new IOException("Failed to parse preset JSON");
            return scene;
        }
    }

    public static CustomScene loadFile(File file) throws IOException {
        if (file == null || !file.exists()) {
            throw new IOException("File does not exist");
        }
        File ws = Files.createTempDirectory("avee_ws_").toFile();
        AppPreferences.setActiveWorkspaceFolder(ws);

        String lower = file.getName().toLowerCase();
        if (lower.endsWith(".json") || lower.endsWith(".txt")) {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            CustomScene scene = CustomScene.deserialize(json);
            if (scene == null) throw new IOException("Invalid JSON template");
            return scene;
        }

        // .viz ZIP archive
        String sceneJson = null;
        String fallbackJson = null;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String base = new File(entry.getName()).getName();
                if (base.isEmpty()) continue;
                if ("scene.json".equalsIgnoreCase(base)) {
                    sceneJson = readUtf8(zis, buf);
                } else if (base.toLowerCase().endsWith(".json") && fallbackJson == null) {
                    fallbackJson = readUtf8(zis, buf);
                } else {
                    writeOut(new File(ws, base), zis, buf);
                }
                zis.closeEntry();
            }
        }
        String chosen = sceneJson != null ? sceneJson : fallbackJson;
        if (chosen == null) {
            throw new IOException("No scene.json found inside .viz archive");
        }
        CustomScene scene = CustomScene.deserialize(chosen);
        if (scene == null) throw new IOException("Invalid scene.json in .viz");
        return scene;
    }

    public static void saveJson(File outFile, CustomScene scene, RendererCore rendererCore) throws IOException {
        CustomScene latest = readLiveScene(scene, rendererCore, null);
        Files.writeString(outFile.toPath(), latest.serializePretty(), StandardCharsets.UTF_8);
    }

    public static void saveViz(File outZip, CustomScene scene, RendererCore rendererCore) throws IOException {
        final Set<String> deps = new LinkedHashSet<>();
        CustomScene latest = readLiveScene(scene, rendererCore, new IDependencyResourceCounter() {
            @Override
            public void PutDependencyResourceName(String name) {
                if (name != null && !name.trim().isEmpty()) {
                    deps.add(name.trim());
                }
            }
        });

        String json = latest.serializePretty();
        Matcher m = LOCAL_PATTERN.matcher(json);
        while (m.find()) {
            deps.add("local:" + m.group(1));
        }

        File ws = AppPreferences.getActiveWorkspaceFolder();
        Set<String> packedNames = new LinkedHashSet<>();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outZip))) {
            zos.putNextEntry(new ZipEntry("scene.json"));
            zos.write(json.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            byte[] buf = new byte[16384];
            for (String dep : deps) {
                File asset = resolveDepFile(ws, dep);
                if (asset != null && asset.exists() && asset.isFile()) {
                    String entryName = dep.startsWith("local:") ? dep.substring("local:".length()) : asset.getName();
                    if (!packedNames.add(entryName)) continue;
                    zos.putNextEntry(new ZipEntry(entryName));
                    try (FileInputStream fis = new FileInputStream(asset)) {
                        int n;
                        while ((n = fis.read(buf)) > 0) {
                            zos.write(buf, 0, n);
                        }
                    }
                    zos.closeEntry();
                }
            }
        }
        tlog.d("Saved .viz with " + packedNames.size() + " bundled asset(s) to " + outZip.getAbsolutePath());
    }

    public static String importLocalAsset(File sourceFile) throws IOException {
        if (sourceFile == null || !sourceFile.exists() || !sourceFile.isFile()) {
            throw new IOException("Selected file does not exist");
        }
        File ws = AppPreferences.getActiveWorkspaceFolder();
        ws.mkdirs();
        String base = sourceFile.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isEmpty()) base = "asset.bin";
        File target = new File(ws, base);
        if (!sourceFile.getCanonicalFile().equals(target.getCanonicalFile())) {
            int counter = 1;
            String stem = base;
            String ext = "";
            int dot = base.lastIndexOf('.');
            if (dot > 0) {
                stem = base.substring(0, dot);
                ext = base.substring(dot);
            }
            while (target.exists() && target.length() != sourceFile.length()) {
                target = new File(ws, stem + "_" + (counter++) + ext);
            }
            if (!target.exists()) {
                Files.copy(sourceFile.toPath(), target.toPath());
            }
        }
        return "local:" + target.getName();
    }

    public static CustomScene readLiveScene(CustomScene fallback, RendererCore rendererCore, IDependencyResourceCounter counter) {
        if (rendererCore == null) return fallback;
        CustomScene out = CustomScene.Create();
        IDependencyResourceCounter c = counter != null ? counter : name -> {};
        rendererCore.readThemeCustomizationData(out, c);
        if (fallback != null) {
            out.setVersionId(fallback.getVersionId());
            out.setTypeId(fallback.getTypeId());
            out.setBasedOnTemplate(fallback.getBasedOnTemplate());
        }
        return out;
    }

    private static File resolveDepFile(File ws, String dep) {
        if (dep == null) return null;
        if (dep.startsWith("internalres:") || dep.startsWith("composition:")) return null;
        if (dep.startsWith("local:")) {
            return new File(ws, dep.substring("local:".length()));
        }
        File direct = new File(dep);
        if (direct.exists() && direct.isFile()) return direct;
        File inWs = new File(ws, new File(dep).getName());
        return inWs.exists() ? inWs : null;
    }

    private static String readUtf8(InputStream is, byte[] buf) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int n;
        while ((n = is.read(buf)) > 0) {
            baos.write(buf, 0, n);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static void writeOut(File dst, InputStream is, byte[] buf) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(dst)) {
            int n;
            while ((n = is.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        }
    }
}
