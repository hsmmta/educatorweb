package org.example.educatorweb.resourcegen.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
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

            boolean timedOut = !process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;

            String stdout;
            String stderr;
            int exitCode;

            if (timedOut) {
                process.destroyForcibly();
                // Give the process a moment to release handles before reading streams
                process.waitFor(2, TimeUnit.SECONDS);
                stdout = new String(process.getInputStream().readAllBytes()).trim();
                stderr = "Execution timed out after " + TIMEOUT_MS + " ms.\n"
                       + new String(process.getErrorStream().readAllBytes()).trim();
                exitCode = -1;
            } else {
                stdout = new String(process.getInputStream().readAllBytes()).trim();
                stderr = new String(process.getErrorStream().readAllBytes()).trim();
                exitCode = process.exitValue();
            }

            log.info("CodeSandbox: exitCode={} elapsed={}ms timedOut={}",
                exitCode, elapsed, timedOut);

            return new ExecutionResult(stdout, stderr, exitCode, elapsed, timedOut);
        } catch (IOException | InterruptedException e) {
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
     * Resolve the python command to use. Tries python3 first, falls back to python.
     */
    private String pythonCommand() {
        // Try python3 first (common on Unix/macOS), fall back to python (Windows)
        try {
            new ProcessBuilder("python3", "--version").start().waitFor(2, TimeUnit.SECONDS);
            return "python3";
        } catch (IOException | InterruptedException e) {
            return "python";
        }
    }
}
