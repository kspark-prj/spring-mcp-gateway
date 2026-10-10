package com.mcp.gateway.transport.stdio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manager for local STDIO MCP server process execution and lifecycle monitoring.
 * <p>
 * Isolates blocking stdin/stdout/stderr I/O operations into dedicated Virtual Threads,
 * provides watchdog health checking with exponential backoff auto-restart, and implements
 * dual-tier Graceful Shutdown (process destroy -> timeout -> destroyForcibly) to avoid zombie processes.
 * </p>
 */
@Component
public class StdioProcessManager {

    private static final Logger log = LoggerFactory.getLogger(StdioProcessManager.class);

    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<UUID, ManagedProcess> activeProcesses = new ConcurrentHashMap<>();

    /**
     * Structure representing a running STDIO server process.
     */
    public static class ManagedProcess {
        public final UUID serverId;
        public final String serverName;
        public final String commandLine;
        public final Map<String, String> environment;
        public Process process;
        public BufferedWriter writer;
        public BufferedReader reader;
        public final AtomicBoolean running = new AtomicBoolean(true);
        public final ConcurrentLinkedQueue<String> responseQueue = new ConcurrentLinkedQueue<>();
        public int restartCount = 0;

        public ManagedProcess(UUID serverId, String serverName, String commandLine, Map<String, String> environment) {
            this.serverId = serverId;
            this.serverName = serverName;
            this.commandLine = commandLine;
            this.environment = environment != null ? environment : Collections.emptyMap();
        }
    }

    /**
     * Starts a managed STDIO process with Virtual Thread I/O isolation.
     *
     * @param serverId server UUID
     * @param serverName server identifier name
     * @param commandLine command line string to execute
     * @param envMap environment variables
     * @return ManagedProcess holder
     */
    public ManagedProcess startProcess(UUID serverId, String serverName, String commandLine, Map<String, String> envMap) throws IOException {
        stopProcess(serverId); // Stop existing if running

        ManagedProcess mp = new ManagedProcess(serverId, serverName, commandLine, envMap);
        launchProcess(mp);
        activeProcesses.put(serverId, mp);

        // Spawn Virtual Thread Watchdog monitor loop
        virtualThreadExecutor.submit(() -> monitorProcessHealth(mp));

        return mp;
    }

    private void launchProcess(ManagedProcess mp) throws IOException {
        List<String> commandList = parseCommandLine(mp.commandLine);
        ProcessBuilder pb = new ProcessBuilder(commandList);
        if (!mp.environment.isEmpty()) {
            pb.environment().putAll(mp.environment);
        }

        Process process = pb.start();
        mp.process = process;
        mp.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        mp.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        // Virtual Thread stdout reader loop
        virtualThreadExecutor.submit(() -> {
            try {
                String line;
                while (mp.running.get() && (line = mp.reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        mp.responseQueue.add(line);
                    }
                }
            } catch (IOException e) {
                if (mp.running.get()) {
                    log.error("Error reading stdout from STDIO server [{}]", mp.serverName, e);
                }
            }
        });

        // Virtual Thread stderr reader loop
        virtualThreadExecutor.submit(() -> {
            try (BufferedReader stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String errLine;
                while (mp.running.get() && (errLine = stderrReader.readLine()) != null) {
                    log.warn("STDIO Server [{}] stderr: {}", mp.serverName, errLine);
                }
            } catch (IOException e) {
                // Ignore during shutdown
            }
        });

        log.info("Successfully launched STDIO process [{}] with PID: {}", mp.serverName, process.pid());
    }

    /**
     * Sends JSON-RPC command string to STDIO process stdin on a Virtual Thread.
     */
    public void sendInput(UUID serverId, String jsonPayload) throws IOException {
        ManagedProcess mp = activeProcesses.get(serverId);
        if (mp == null || !mp.running.get() || mp.process == null || !mp.process.isAlive()) {
            throw new IOException("STDIO process for server is not alive");
        }

        synchronized (mp.writer) {
            mp.writer.write(jsonPayload);
            mp.writer.newLine();
            mp.writer.flush();
        }
    }

    /**
     * Polls response queue for pending JSON-RPC response from STDIO stdout.
     */
    public String pollResponse(UUID serverId, long timeoutMs) throws InterruptedException {
        ManagedProcess mp = activeProcesses.get(serverId);
        if (mp == null) return null;

        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            String resp = mp.responseQueue.poll();
            if (resp != null) {
                return resp;
            }
            Thread.sleep(10);
        }
        return null;
    }

    /**
     * Watchdog loop monitoring process health with exponential backoff auto-restart.
     */
    private void monitorProcessHealth(ManagedProcess mp) {
        try {
            if (mp.process != null) {
                int exitCode = mp.process.waitFor();
                if (mp.running.get()) {
                    log.warn("STDIO Process [{}] died unexpectedly with exit code: {}", mp.serverName, exitCode);
                    attemptAutoRestart(mp);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void attemptAutoRestart(ManagedProcess mp) {
        if (!mp.running.get()) return;

        mp.restartCount++;
        long backoffMs = Math.min(1000L * (1L << Math.min(mp.restartCount, 5)), 30000L); // Max 30s
        log.info("Attempting auto-restart #{} for STDIO server [{}] after {} ms delay", mp.restartCount, mp.serverName, backoffMs);

        try {
            Thread.sleep(backoffMs);
            if (mp.running.get()) {
                launchProcess(mp);
                virtualThreadExecutor.submit(() -> monitorProcessHealth(mp));
            }
        } catch (Exception e) {
            log.error("Failed auto-restart for STDIO server [{}]", mp.serverName, e);
        }
    }

    /**
     * Stops a managed STDIO process using Graceful Shutdown combination (SIGTERM -> Wait -> SIGKILL).
     */
    public void stopProcess(UUID serverId) {
        ManagedProcess mp = activeProcesses.remove(serverId);
        if (mp == null) return;

        mp.running.set(false);
        if (mp.process != null && mp.process.isAlive()) {
            log.info("Gracefully stopping STDIO process [{}] (PID: {})", mp.serverName, mp.process.pid());
            mp.process.destroy(); // SIGTERM
            try {
                if (!mp.process.waitFor(5, TimeUnit.SECONDS)) {
                    log.warn("STDIO process [{}] did not terminate within 5s. Force killing (SIGKILL)...", mp.serverName);
                    mp.process.destroyForcibly(); // SIGKILL
                }
            } catch (InterruptedException e) {
                mp.process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    @PreDestroy
    public void shutdownAll() {
        log.info("Shutting down all STDIO processes...");
        activeProcesses.keySet().forEach(this::stopProcess);
        virtualThreadExecutor.shutdownNow();
    }

    private List<String> parseCommandLine(String cmd) {
        if (cmd == null || cmd.isBlank()) return List.of();
        List<String> list = new ArrayList<>();
        Matcher m = Pattern.compile("([^\"]\\S*|\"[^\"]*\")").matcher(cmd);
        while (m.find()) {
            list.add(m.group(1).replace("\"", ""));
        }
        return list;
    }
}
