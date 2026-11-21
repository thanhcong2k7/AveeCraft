package com.daaw.avee.mcp;

import com.daaw.avee.Common.tlog;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

public class McpBridgeServer {
    private static final SecureRandom RNG = new SecureRandom();

    private final File bridgeFile;
    private final AveeSceneService service;
    private final ConcurrentLinkedQueue<Runnable> uiTaskQueue = new ConcurrentLinkedQueue<>();

    private volatile boolean running = false;
    private volatile int port = 0;
    private volatile String token = "";
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService clientPool;
    private Thread shutdownHook;

    public McpBridgeServer(AveeSceneService service) {
        this(defaultBridgeFile(), service);
    }

    public McpBridgeServer(File bridgeFile, AveeSceneService service) {
        this.bridgeFile = bridgeFile;
        this.service = service;
    }

    public static File defaultBridgeFile() {
        return new File(new File(System.getProperty("user.home", "."), ".aveecraft"), "mcp-bridge.json");
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        byte[] rnd = new byte[16];
        RNG.nextBytes(rnd);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : rnd) sb.append(String.format("%02x", b & 0xFF));
        this.token = sb.toString();

        this.serverSocket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        this.port = serverSocket.getLocalPort();
        this.running = true;
        this.clientPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "AveeMcpBridgeClient");
            t.setDaemon(true);
            return t;
        });

        writeDiscoveryFile();
        if (shutdownHook == null) {
            shutdownHook = new Thread(this::deleteDiscoveryFile, "AveeMcpBridgeCleanup");
            try {
                Runtime.getRuntime().addShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {}
        }

        acceptThread = new Thread(this::acceptLoop, "AveeMcpBridgeAccept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        tlog.d("MCP bridge listening on 127.0.0.1:" + port);
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            serverSocket = null;
        }
        if (clientPool != null) {
            clientPool.shutdownNow();
            clientPool = null;
        }
        port = 0;
        deleteDiscoveryFile();
        uiTaskQueue.clear();
    }

    public void drainUiTasks() {
        Runnable r;
        int limit = 16;
        while (limit-- > 0 && (r = uiTaskQueue.poll()) != null) {
            try {
                r.run();
            } catch (Throwable t) {
                tlog.w("MCP bridge task error: " + t.getMessage());
            }
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket sock = serverSocket.accept();
                ExecutorService pool = this.clientPool;
                if (pool != null && running) {
                    pool.submit(() -> handleClient(sock));
                } else {
                    sock.close();
                }
            } catch (IOException e) {
                if (running) tlog.w("MCP bridge accept error: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket sock) {
        try (Socket s = sock;
             BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                JSONObject resp = new JSONObject();
                try {
                    JSONObject req = new JSONObject(line);
                    String supplied = req.optString("token", "");
                    if (!MessageDigest.isEqual(
                            supplied.getBytes(StandardCharsets.UTF_8),
                            token.getBytes(StandardCharsets.UTF_8))) {
                        resp.put("ok", false);
                        resp.put("error", "Unauthorized MCP bridge token");
                    } else {
                        String method = req.getString("method");
                        JSONObject params = req.optJSONObject("params");
                        CompletableFuture<JSONObject> cf = new CompletableFuture<>();
                        uiTaskQueue.add(() -> service.dispatch(method, params, cf));
                        JSONObject result = cf.get(15, TimeUnit.SECONDS);
                        resp.put("ok", true);
                        resp.put("result", result);
                    }
                } catch (Throwable ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    resp.put("ok", false);
                    resp.put("error", cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
                }
                bw.write(resp.toString());
                bw.write('\n');
                bw.flush();
            }
        } catch (Throwable ignored) {
        }
    }

    private void writeDiscoveryFile() throws IOException {
        File parent = bridgeFile.getParentFile();
        if (parent != null) parent.mkdirs();
        JSONObject info = new JSONObject();
        info.put("host", "127.0.0.1");
        info.put("port", port);
        info.put("token", token);
        info.put("pid", ProcessHandle.current().pid());
        Files.writeString(bridgeFile.toPath(), info.toString(2), StandardCharsets.UTF_8);
    }

    private void deleteDiscoveryFile() {
        try {
            if (bridgeFile.isFile()) {
                String raw = Files.readString(bridgeFile.toPath(), StandardCharsets.UTF_8);
                JSONObject info = new JSONObject(raw);
                if (info.optInt("port", -1) == port || port == 0) {
                    bridgeFile.delete();
                }
            }
        } catch (Throwable ignored) {}
    }
}
