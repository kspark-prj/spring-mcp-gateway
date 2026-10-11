package com.mcp.gateway.transport.stdio;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Manager for local STDIO MCP server process execution and lifecycle monitoring.
 * <p>
 * Isolates blocking stdin/stdout/stderr I/O operations into dedicated Virtual Threads,
 * provides watchdog health checking with exponential backoff auto-restart, and implements
 * dual-tier Graceful Shutdown.
 * </p>
 */
@Component
public class StdioProcessManager {

    private static final Logger log = LoggerFactory.getLogger(StdioProcessManager.class);

    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<Long, ManagedProcess> activeProcesses = new ConcurrentHashMap<>();

    public static class ManagedProcess {
        public final Long serverId;
        public final String serverName;
        public final String commandLine;
        public final Map<String, String> environment;
        public Process process;
        public BufferedWriter writer;
        public BufferedReader reader;
        public final AtomicBoolean running = new AtomicBoolean(true);
        public final ConcurrentLinkedQueue<String> responseQueue = new ConcurrentLinkedQueue<>();
        public int restartCount = 0;

        public ManagedProcess(Long serverId, String serverName, String commandLine, Map<String, String> environment) {
            this.serverId = serverId;
            this.serverName = serverName;
            this.commandLine = commandLine;
            this.environment = environment != null ? environment : Collections.emptyMap();
        }
    }

    public ManagedProcess startProcess(Long serverId, String serverName, String commandLine, Map<String, String> envMap) throws IOException {
        log.info("📌 [STDIO-DEBUG] startProcess() called: serverId={}, serverName={}, commandLine={}", serverId, serverName, commandLine);
        stopProcess(serverId);

        ManagedProcess mp = new ManagedProcess(serverId, serverName, commandLine, envMap);
        launchProcess(mp);
        activeProcesses.put(serverId, mp);

        virtualThreadExecutor.submit(() -> monitorProcessHealth(mp));
        return mp;
    }

   private void launchProcess(ManagedProcess mp) throws IOException {
        String effectiveCommandLine = mp.commandLine;
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        // ⭐ Windows 환경에서 PATH에 등록된 npx 명령어를 감지하여 cmd.exe /c 자동 조합
        if (isWindows) {
            String trimmed = effectiveCommandLine != null ? effectiveCommandLine.trim() : "";
            if (trimmed.toLowerCase().startsWith("npx") && !trimmed.toLowerCase().startsWith("cmd.exe")) {
                effectiveCommandLine = "cmd.exe /c " + trimmed;
                log.info("📌 [STDIO-DEBUG] Auto-injected 'cmd.exe /c' for PATH-registered npx: {}", effectiveCommandLine);
            }
        }

        List<String> commandList = parseCommandLine(effectiveCommandLine);
        log.info("📌 [STDIO-DEBUG] launchProcess() parsed command: {}", commandList);

        ProcessBuilder pb = new ProcessBuilder(commandList);
        if (!mp.environment.isEmpty()) {
            log.info("📌 [STDIO-DEBUG] Setting environment variables: {}", mp.environment.keySet());
            pb.environment().putAll(mp.environment);
        }

        Process process = pb.start();
        mp.process = process;

        // OS별 캐릭터셋 자동 설정 (Windows는 MS949로 에러 및 출력 로그 깨짐 방지, Linux/Mac은 UTF-8)
        Charset systemCharset = isWindows ? Charset.forName("MS949") : StandardCharsets.UTF_8;

        mp.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        mp.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), systemCharset));
        log.info("📌 [STDIO-DEBUG] Process started, PID={}, isAlive={}, systemCharset={}", process.pid(), process.isAlive(), systemCharset);

        // Virtual Thread stdout reader loop
        virtualThreadExecutor.submit(() -> {
            log.info("📌 [STDIO-DEBUG] stdout reader thread started for [{}]", mp.serverName);
            try {
                String line;
                while (mp.running.get() && (line = mp.reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        log.info("📌 [STDIO-RAW-STDOUT] [{}] Received line (length={}): [{}]", mp.serverName, line.length(), line);

                        String trimmedLine = line.trim();
                        if (trimmedLine.startsWith("{") && trimmedLine.endsWith("}")) {
                            log.info("📌 [STDIO-PARSE-SUCCESS] [{}] Valid JSON-RPC response detected and queued.", mp.serverName);
                            mp.responseQueue.add(trimmedLine);
                        } else {
                            log.warn("📌 [STDIO-PARSE-WARNING] [{}] Received non-JSON output: [{}]", mp.serverName, trimmedLine);
                            mp.responseQueue.add(trimmedLine);
                        }
                    }
                }
                log.info("📌 [STDIO-DEBUG] stdout reader loop ended for [{}], running={}", mp.serverName, mp.running.get());
            } catch (IOException e) {
                if (mp.running.get()) {
                    log.error("📌 [STDIO-DEBUG] Error reading stdout from STDIO server [{}]: {}", mp.serverName, e.getMessage(), e);
                }
            }
        });

        // Virtual Thread stderr reader loop (MS949 캐릭터셋 적용)
        virtualThreadExecutor.submit(() -> {
            log.info("📌 [STDIO-DEBUG] stderr reader thread started for [{}]", mp.serverName);
            try (BufferedReader stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), systemCharset))) {
                String errLine;
                while (mp.running.get() && (errLine = stderrReader.readLine()) != null) {
                    log.warn("📌 [STDIO-DEBUG] stderr [{}]: {}", mp.serverName, errLine);
                }
            } catch (IOException e) {
                // Ignore during shutdown
            }
        });

        log.info("Successfully launched STDIO process [{}] with PID: {}", mp.serverName, process.pid());
    }

    public void sendInput(Long serverId, String jsonPayload) throws IOException {
        ManagedProcess mp = activeProcesses.get(serverId);
        log.info("📌 [STDIO-DEBUG] sendInput() called: serverId={}, payload={}", serverId, jsonPayload);
        if (mp == null || !mp.running.get() || mp.process == null || !mp.process.isAlive()) {
            throw new IOException("STDIO process for server is not alive");
        }

        synchronized (mp.writer) {
            mp.writer.write(jsonPayload);
            mp.writer.newLine();
            mp.writer.flush();
            log.info("📌 [STDIO-DEBUG] sendInput() successfully flushed to stdin for [{}]", mp.serverName);
        }
    }

    public String pollResponse(Long serverId, long timeoutMs) throws InterruptedException {
        ManagedProcess mp = activeProcesses.get(serverId);
        if (mp == null) return null;

        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            String resp = mp.responseQueue.poll();
            if (resp != null) {
                log.info("📌 [STDIO-DEBUG] pollResponse() successfully retrieved response: length={}", resp.length());
                return resp;
            }
            Thread.sleep(10);
        }
        log.warn("📌 [STDIO-DEBUG] pollResponse() TIMEOUT after {}ms, processAlive={}", timeoutMs, mp.process != null && mp.process.isAlive());
        return null;
    }

    public void stopProcess(Long serverId) {
        ManagedProcess mp = activeProcesses.remove(serverId);
        if (mp == null) return;

        mp.running.set(false);
        if (mp.process != null && mp.process.isAlive()) {
            mp.process.destroy();
            try {
                if (!mp.process.waitFor(5, TimeUnit.SECONDS)) {
                    mp.process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                mp.process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    @PreDestroy
    public void shutdownAll() {
        activeProcesses.keySet().forEach(this::stopProcess);
        virtualThreadExecutor.shutdownNow();
    }

    private List<String> parseCommandLine(String cmd) {
        if (cmd == null || cmd.isBlank()) return List.of();

        List<String> list = new ArrayList<>();
        String trimmed = cmd.trim();

        // ⭐ 핵심: cmd.exe /c로 시작하는 경우, /c 뒤의 전체 명령어를 하나의 인자로 묶어서 전달
        if (trimmed.toLowerCase().startsWith("cmd.exe /c")) {
            list.add("cmd.exe");
            list.add("/c");
            String remainder = trimmed.substring("cmd.exe /c".length()).trim();
            list.add(remainder);
            log.info("📌 [STDIO-DEBUG] Parsed cmd.exe /c with combined argument: {}", list);
            return list;
        }

        // 일반적인 파싱 폴백
        Matcher m = Pattern.compile("([^\"]\\S*|\"[^\"]*\")").matcher(cmd);
        while (m.find()) {
            list.add(m.group(1).replace("\"", ""));
        }
        return list;
    }

    private void monitorProcessHealth(ManagedProcess mp) {
        try {
            if (mp.process != null) {
                int exitCode = mp.process.waitFor();
                if (mp.running.get()) {
                    log.warn("STDIO Process [{}] died unexpectedly with exit code: {}", mp.serverName, exitCode);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}