package com.daaw.avee.mcp;

import com.daaw.avee.editor.UpdateManager;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

public class McpStdioServer {
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final File bridgeFile;
    private final boolean readOnlyFlag;
    private final boolean liveOnly;
    private final boolean forceHeadless;
    private final int initialPreset;
    private final File initialProject;

    private HeadlessSceneHost headlessHost;
    private AveeSceneService headlessService;

    public McpStdioServer(File bridgeFile, boolean readOnlyFlag, boolean liveOnly, boolean forceHeadless,
                          int initialPreset, File initialProject) {
        this.bridgeFile = bridgeFile != null ? bridgeFile : McpBridgeServer.defaultBridgeFile();
        this.readOnlyFlag = readOnlyFlag;
        this.liveOnly = liveOnly;
        this.forceHeadless = forceHeadless;
        this.initialPreset = initialPreset;
        this.initialProject = initialProject;
    }

    public static void main(String[] args) throws Exception {
        PrintStream rpcOut = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        System.setOut(System.err);

        boolean readOnly = false;
        boolean liveOnly = false;
        boolean headless = false;
        int preset = 0;
        File project = null;
        File bridgeFile = McpBridgeServer.defaultBridgeFile();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--read-only".equals(a)) readOnly = true;
            else if ("--live-only".equals(a)) liveOnly = true;
            else if ("--headless".equals(a)) headless = true;
            else if ("--preset".equals(a) && i + 1 < args.length) preset = Integer.parseInt(args[++i]);
            else if ("--project".equals(a) && i + 1 < args.length) project = new File(args[++i]);
            else if ("--bridge-file".equals(a) && i + 1 < args.length) bridgeFile = new File(args[++i]);
        }

        McpStdioServer server = new McpStdioServer(bridgeFile, readOnly, liveOnly, headless, preset, project);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String reply = server.handleJsonRpcLine(line);
                if (reply != null) {
                    rpcOut.println(reply);
                    rpcOut.flush();
                }
            }
        } finally {
            server.close();
        }
    }

    public void close() {
        if (headlessHost != null) {
            headlessHost.close();
            headlessHost = null;
            headlessService = null;
        }
    }

    public String handleJsonRpcLine(String rawLine) {
        JSONObject req;
        Object id = null;
        boolean hasId = false;
        try {
            req = new JSONObject(rawLine);
            hasId = req.has("id") && req.get("id") != JSONObject.NULL;
            if (hasId) id = req.get("id");
        } catch (Throwable t) {
            return makeErrorResponse(JSONObject.NULL, -32700, "Parse error: " + t.getMessage());
        }

        String method = req.optString("method", "");
        if (!hasId || method.startsWith("notifications/")) {
            return null;
        }

        JSONObject params = req.optJSONObject("params");
        if (params == null) params = new JSONObject();

        try {
            if ("initialize".equals(method)) {
                JSONObject res = new JSONObject();
                res.put("protocolVersion", PROTOCOL_VERSION);
                res.put("capabilities", new JSONObject().put("tools", new JSONObject()));
                res.put("serverInfo", new JSONObject()
                        .put("name", "aveecraft-mcp")
                        .put("version", UpdateManager.getCurrentVersion()));
                return makeResultResponse(id, res);
            }
            if ("ping".equals(method)) {
                return makeResultResponse(id, new JSONObject());
            }
            if ("tools/list".equals(method)) {
                return makeResultResponse(id, new JSONObject().put("tools", buildToolDefinitions(readOnlyFlag)));
            }
            if ("tools/call".equals(method)) {
                String toolName = params.getString("name");
                JSONObject args = params.optJSONObject("arguments");
                if (args == null) args = new JSONObject();
                JSONObject callResult = callTool(toolName, args);
                return makeResultResponse(id, callResult);
            }
            return makeErrorResponse(id, -32601, "Method not found: " + method);
        } catch (Throwable t) {
            return makeErrorResponse(id, -32603, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        }
    }

    private JSONObject callTool(String toolName, JSONObject args) {
        try {
            if (readOnlyFlag && isMutatingTool(toolName)) {
                return makeToolError("Tool '" + toolName + "' is disabled because MCP server started with --read-only.");
            }
            JSONObject toolOut = null;
            if (!forceHeadless) {
                toolOut = tryInvokeLiveBridge(toolName, args);
            }
            if (toolOut == null) {
                if (liveOnly) {
                    return makeToolError("AveeCraft live MCP bridge is not active. Open AveeCraft and enable Tools -> MCP Server -> Enable MCP Bridge.");
                }
                ensureHeadlessService();
                toolOut = headlessHost.runOnGlThread(() -> {
                    CompletableFuture<JSONObject> cf = new CompletableFuture<>();
                    headlessService.dispatch(toolName, args, cf);
                    return cf.get(15, TimeUnit.SECONDS);
                });
            }
            return formatMcpToolResult(toolOut);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return makeToolError(cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
        }
    }

    private JSONObject tryInvokeLiveBridge(String method, JSONObject params) throws Exception {
        if (!bridgeFile.isFile()) return null;
        JSONObject disc;
        try {
            disc = new JSONObject(Files.readString(bridgeFile.toPath(), StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            return null;
        }
        String host = disc.optString("host", "127.0.0.1");
        int port = disc.optInt("port", -1);
        String token = disc.optString("token", "");
        if (port <= 0 || token.isEmpty()) return null;

        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(host, port), 1200);
            sock.setSoTimeout(16000);
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            JSONObject req = new JSONObject()
                    .put("token", token)
                    .put("method", method)
                    .put("params", params != null ? params : new JSONObject());
            bw.write(req.toString());
            bw.write('\n');
            bw.flush();
            String respLine = br.readLine();
            if (respLine == null) throw new IllegalStateException("Live MCP bridge closed connection");
            JSONObject resp = new JSONObject(respLine);
            if (!resp.optBoolean("ok", false)) {
                throw new IllegalStateException(resp.optString("error", "Live MCP bridge error"));
            }
            return resp.getJSONObject("result");
        } catch (java.io.IOException connectErr) {
            if (liveOnly) {
                throw new IllegalStateException("Failed to reach live AveeCraft MCP bridge on " + host + ":" + port + " (" + connectErr.getMessage() + ")");
            }
            return null;
        }
    }

    private synchronized void ensureHeadlessService() throws Exception {
        if (headlessService != null) return;
        headlessHost = new HeadlessSceneHost(readOnlyFlag);
        headlessHost.init(initialPreset, initialProject);
        headlessService = new AveeSceneService(headlessHost);
    }

    private static JSONObject formatMcpToolResult(JSONObject toolOut) {
        JSONArray content = new JSONArray();
        if (toolOut.has("_mcpImageBase64")) {
            String b64 = toolOut.getString("_mcpImageBase64");
            String mime = toolOut.optString("_mcpMimeType", "image/png");
            JSONObject copy = new JSONObject(toolOut.toString());
            copy.remove("_mcpImageBase64");
            copy.remove("_mcpMimeType");
            content.put(new JSONObject().put("type", "image").put("data", b64).put("mimeType", mime));
            content.put(new JSONObject().put("type", "text").put("text", copy.toString(2)));
        } else {
            content.put(new JSONObject().put("type", "text").put("text", toolOut.toString(2)));
        }
        return new JSONObject().put("content", content).put("isError", false);
    }

    private static JSONObject makeToolError(String message) {
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text", message));
        return new JSONObject().put("content", content).put("isError", true);
    }

    private static String makeResultResponse(Object id, JSONObject result) {
        return new JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("result", result)
                .toString();
    }

    private static String makeErrorResponse(Object id, int code, String message) {
        return new JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("error", new JSONObject().put("code", code).put("message", message))
                .toString();
    }

    private static boolean isMutatingTool(String name) {
        List<String> mutating = Arrays.asList(
                "load_preset", "open_project", "add_composition", "delete_composition",
                "add_layer", "duplicate_layer", "delete_layer", "move_layer",
                "set_property", "set_properties", "set_audio", "undo", "redo"
        );
        return mutating.contains(name);
    }

    public static JSONArray buildToolDefinitions(boolean readOnly) {
        JSONArray tools = new JSONArray();
        tools.put(tool("get_state", "Get current editor/engine state (live vs headless mode, resolution, selection, audio status, version).",
                schema(new JSONObject())));
        tools.put(tool("get_scene", "List all compositions and layers with indices, types, tags, visibility, blendMode, transforms, and composition links.",
                schema(new JSONObject())));
        tools.put(tool("get_layer", "Inspect all properties (values, ranges, enum/media/composition options) of a layer or Composition (use layerIndex=-1 for the Composition itself).",
                schema(new JSONObject()
                        .put("compIndex", prop("integer", "Composition index (0 = Final)."))
                        .put("layerIndex", prop("integer", "Layer index inside composition, or -1 for Composition properties.")))));
        tools.put(tool("get_property", "Read a single property (supports child paths like 'blendMode' or 'MeasurePos').",
                schema(new JSONObject()
                        .put("compIndex", prop("integer", "Composition index."))
                        .put("layerIndex", prop("integer", "Layer index (-1 for Composition)."))
                        .put("property", prop("string", "Property name or child path.")), "property")));
        tools.put(tool("list_catalog", "List addable element types, built-in preset count, blend modes, measure names, and workspace media assets.",
                schema(new JSONObject())));
        tools.put(tool("render_preview", "Render and capture the visualizer preview canvas as a PNG screenshot.",
                schema(new JSONObject()
                        .put("maxWidth", prop("integer", "Optional max width to downscale PNG (default 960)."))
                        .put("advanceFrames", prop("integer", "Number of frames to advance before capture (default 2)."))
                        .put("timeMs", prop("integer", "Optional audio timestamp in ms to seek to before rendering."))
                        .put("highlightSelected", prop("boolean", "Trigger the yellow selection flash on the currently selected layer."))
                        .put("savePath", prop("string", "Optional file path to also save the PNG.")))));
        tools.put(tool("screenshot_window", "Capture a PNG screenshot of the entire live AveeCraft editor window (Scene Tree + Preview + Inspector).",
                schema(new JSONObject()
                        .put("maxWidth", prop("integer", "Optional max width to downscale PNG (default 1280)."))
                        .put("savePath", prop("string", "Optional file path to also save the PNG.")))));
        tools.put(tool("save_project", "Save the current scene as a .viz archive or scene.json file.",
                schema(new JSONObject().put("path", prop("string", "Destination .viz or .json path.")), "path")));

        if (!readOnly) {
            tools.put(tool("select_layer", "Select a composition/layer in the editor and optionally highlight it.",
                    schema(new JSONObject()
                            .put("compIndex", prop("integer", "Composition index."))
                            .put("layerIndex", prop("integer", "Layer index (-1 for Composition)."))
                            .put("highlight", prop("boolean", "Trigger selection highlight flash (default true).")))));
            tools.put(tool("load_preset", "Load built-in AveeCraft visualizer preset 0..17.",
                    schema(new JSONObject().put("presetIndex", prop("integer", "Preset index 0..17.")), "presetIndex")));
            tools.put(tool("open_project", "Open a .viz archive or scene.json file.",
                    schema(new JSONObject().put("path", prop("string", "Path to .viz or .json file.")), "path")));
            tools.put(tool("add_composition", "Append a new offscreen Composition to the scene.",
                    schema(new JSONObject())));
            tools.put(tool("delete_composition", "Delete Composition N (N > 0) and merge its layers onto the bottom of Composition N - 1.",
                    schema(new JSONObject().put("compIndex", prop("integer", "Composition index >= 1.")), "compIndex")));
            tools.put(tool("add_layer", "Add a new visualizer element layer (e.g. Image, Bars, Text, BlurEffect) to a Composition.",
                    schema(new JSONObject()
                            .put("compIndex", prop("integer", "Target Composition index."))
                            .put("type", prop("string", "Element type name from list_catalog.")), "type")));
            tools.put(tool("duplicate_layer", "Duplicate an existing layer in a Composition.",
                    schema(new JSONObject()
                            .put("compIndex", prop("integer", "Composition index."))
                            .put("layerIndex", prop("integer", "Layer index.")))));
            tools.put(tool("delete_layer", "Delete a layer from a Composition.",
                    schema(new JSONObject()
                            .put("compIndex", prop("integer", "Composition index."))
                            .put("layerIndex", prop("integer", "Layer index.")))));
            tools.put(tool("move_layer", "Move or reorder a layer within a Composition or across Compositions.",
                    schema(new JSONObject()
                            .put("srcCompIndex", prop("integer", "Source Composition index."))
                            .put("srcLayerIndex", prop("integer", "Source layer index."))
                            .put("dstCompIndex", prop("integer", "Destination Composition index."))
                            .put("dstLayerIndex", prop("integer", "Destination layer index.")),
                            "srcCompIndex", "srcLayerIndex", "dstCompIndex", "dstLayerIndex")));
            tools.put(tool("set_property", "Update a single property on a layer or Composition.",
                    schema(new JSONObject()
                            .put("compIndex", prop("integer", "Composition index."))
                            .put("layerIndex", prop("integer", "Layer index (-1 for Composition)."))
                            .put("property", prop("string", "Property name or child path (e.g. 'visible', 'scale', 'blendMode', 'MeasureScale/scale')."))
                            .put("value", new JSONObject().put("description", "New property value (boolean, number, {x,y}, '#RRGGBB', string, or mvar terms array).")),
                            "property", "value")));
            tools.put(tool("set_properties", "Batch update multiple properties on one layer or Composition.",
                    schema(new JSONObject()
                            .put("compIndex", prop("integer", "Composition index."))
                            .put("layerIndex", prop("integer", "Layer index (-1 for Composition)."))
                            .put("properties", new JSONObject().put("type", "object").put("description", "Map of property name/path to new value.")),
                            "properties")));
            tools.put(tool("set_audio", "Control audio playback, test signal, mute, HQ blur, or seek position.",
                    schema(new JSONObject()
                            .put("path", prop("string", "Optional audio file path to decode and load."))
                            .put("playing", prop("boolean", "Play/pause loaded audio."))
                            .put("syntheticBeat", prop("boolean", "Enable/disable synthetic test beat."))
                            .put("muted", prop("boolean", "Mute audio output."))
                            .put("highQualityBlur", prop("boolean", "Toggle HQ mipmap blur."))
                            .put("timeMs", prop("integer", "Seek audio position in ms.")))));
            tools.put(tool("undo", "Undo the last MCP scene modification.", schema(new JSONObject())));
            tools.put(tool("redo", "Redo the last undone MCP scene modification.", schema(new JSONObject())));
        }
        return tools;
    }

    private static JSONObject tool(String name, String description, JSONObject inputSchema) {
        return new JSONObject()
                .put("name", name)
                .put("description", description)
                .put("inputSchema", inputSchema);
    }

    private static JSONObject prop(String type, String desc) {
        return new JSONObject().put("type", type).put("description", desc);
    }

    private static JSONObject schema(JSONObject props, String... required) {
        JSONObject s = new JSONObject()
                .put("type", "object")
                .put("properties", props);
        if (required != null && required.length > 0) {
            s.put("required", new JSONArray(Arrays.asList(required)));
        }
        return s;
    }
}
