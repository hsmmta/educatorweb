package org.example.educatorweb.resourcegen.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Runs Python code in a sandboxed subprocess with timeout.
 * Uses ProcessBuilder with local python interpreter (no Docker until Testcontainers is wired).
 */
@Service
public class CodeSandboxService {

    private static final Logger log = LoggerFactory.getLogger(CodeSandboxService.class);
    private static final long TIMEOUT_MS = 30_000;

    public record ExecutionResult(String stdout, String stderr, int exitCode,
                                  long executionTimeMs, boolean timedOut) {

        public boolean succeeded() {
            return exitCode == 0 && !timedOut;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Exit code: ").append(exitCode);
            sb.append(", Time: ").append(executionTimeMs).append(" ms");
            if (timedOut) sb.append(" (TIMED OUT)");
            if (stdout != null && !stdout.isEmpty()) {
                sb.append("\n--- stdout ---\n").append(stdout);
            }
            if (stderr != null && !stderr.isEmpty()) {
                sb.append("\n--- stderr ---\n").append(stderr);
            }
            return sb.toString();
        }
    }

    /**
     * Execute the given Python code string. A temporary directory is created to
     * isolate the execution; the code is written to a file and run via ProcessBuilder.
     *
     * @param code Python source code
     * @return ExecutionResult with stdout, stderr, exitCode, timing, and timeout flag
     */
    public ExecutionResult execute(String code) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("codesandbox-");
            Path scriptFile = tempDir.resolve("script.py");
            Files.writeString(scriptFile, code);

            ProcessBuilder pb = new ProcessBuilder(pythonCommand(), scriptFile.toString());
            pb.directory(tempDir.toFile());
            pb.redirectErrorStream(false);

            long start = System.currentTimeMillis();
            Process process = pb.start();

            // Read stdout and stderr in separate threads BEFORE waitFor().
            // On Windows, pipes close when the child process exits; reading
            // concurrently avoids "管道为空" (pipe has been ended) errors.
            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

            Thread outThread = drainAsync(process.getInputStream(), outBuf);
            Thread errThread = drainAsync(process.getErrorStream(), errBuf);

            boolean timedOut = !process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;

            int exitCode;
            String stdout;
            String stderr;

            if (timedOut) {
                process.destroyForcibly();
                // Wait briefly for threads to finish draining remaining data
                outThread.join(2000);
                errThread.join(2000);
                stdout = outBuf.toString().trim();
                stderr = "Execution timed out after " + TIMEOUT_MS + " ms.\n"
                       + errBuf.toString().trim();
                exitCode = -1;
            } else {
                // Process completed — wait for reader threads to finish
                outThread.join(5000);
                errThread.join(5000);
                stdout = outBuf.toString().trim();
                stderr = errBuf.toString().trim();
                exitCode = process.exitValue();
            }

            log.info("CodeSandbox: exitCode={} elapsed={}ms timedOut={}",
                exitCode, elapsed, timedOut);

            return new ExecutionResult(stdout, stderr, exitCode, elapsed, timedOut);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("CodeSandbox execution failed: {}", e.getMessage(), e);
            return new ExecutionResult("",
                "Sandbox error: " + e.getMessage(), -1, 0, false);
        } finally {
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.delete(p); } catch (IOException ignored) { }
                        });
                } catch (IOException ignored) {
                    log.warn("Failed to clean up temp directory: {}", tempDir);
                }
            }
        }
    }

    /**
     * Drain an InputStream into a ByteArrayOutputStream on a daemon thread.
     * This runs concurrently with process waitFor() to avoid pipe buffer
     * deadlocks and Windows "pipe has been ended" errors.
     */
    private Thread drainAsync(InputStream in, ByteArrayOutputStream out) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            } catch (IOException ignored) {
                // Stream closed or process terminated
            }
        }, "sandbox-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Resolve the python command to use. On Windows, checks known install locations
     * first (Anaconda, standard Python) to avoid the Microsoft Store stub.
     * Falls back to python3/python command-line lookup.
     */
    private String pythonCommand() {
        // 1. Check known Windows installation paths
        String[] knownPaths = {
            "E:\\anaconda\\python.exe",
            System.getProperty("user.home") + "\\anaconda3\\python.exe",
            "C:\\ProgramData\\anaconda3\\python.exe",
            "C:\\Python312\\python.exe",
            "C:\\Python311\\python.exe",
        };
        for (String path : knownPaths) {
            if (Files.exists(Path.of(path))) {
                log.info("CodeSandbox: using python at {}", path);
                return path;
            }
        }

        // 2. Try python3 first (Unix/macOS), then python
        try {
            new ProcessBuilder("python3", "--version").start().waitFor(2, TimeUnit.SECONDS);
            return "python3";
        } catch (IOException | InterruptedException e) {
            return "python";
        }
    }
}
