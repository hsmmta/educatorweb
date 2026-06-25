package org.example.educatorweb.resourcegen.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs Python code in a sandboxed subprocess with timeout.
 * Uses ProcessBuilder with local python interpreter (no Docker until Testcontainers is wired).
 */
@Service
public class CodeSandboxService {

    private static final Logger log = LoggerFactory.getLogger(CodeSandboxService.class);
    private static final long TIMEOUT_MS = 30_000;

    /**
     * Third-party libraries that are banned in the sandbox.
     * Only Python standard library is allowed for portability across environments.
     */
    private static final Set<String> BANNED_IMPORTS = Set.of(
        "numpy", "pandas", "matplotlib", "scipy", "sklearn", "seaborn",
        "plotly", "tensorflow", "torch", "keras", "pillow", "PIL", "cv2",
        "requests", "bs4", "beautifulsoup4", "lxml", "nltk", "spacy",
        "transformers", "django", "flask", "fastapi", "sqlalchemy",
        "pymongo", "redis", "celery", "scrapy", "selenium", "pygame",
        "PyQt5", "PyQt6", "tkinter", "wx", "kivy", "streamlit", "gradio",
        "networkx", "sympy", "statsmodels", "pytest", "jupyter",
        "ipython", "notebook", "bokeh", "altair", "dash", "folium"
    );

    /** Pattern to match import statements: "import foo" or "from foo import bar" */
    private static final Pattern IMPORT_PATTERN =
        Pattern.compile("^(?:import\\s+([\\w.]+)|from\\s+([\\w.]+)\\s+import)", Pattern.MULTILINE);

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
        // Validate imports before execution — ban third-party libraries
        List<String> violations = validateImports(code);
        if (!violations.isEmpty()) {
            String msg = "代码包含禁止的第三方库，已被拦截以确保跨环境兼容性。\n"
                       + "违规导入: " + String.join(", ", violations) + "\n"
                       + "提示: 代码只能使用 Python 标准库，禁止使用 numpy/pandas/matplotlib 等第三方包。\n"
                       + "如需可视化，请使用 print() 输出文字或 ASCII 图表。";
            log.warn("CodeSandbox: blocked execution due to banned imports: {}", violations);
            return new ExecutionResult("", msg, -1, 0, false);
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("codesandbox-");
            Path scriptFile = tempDir.resolve("script.py");
            Files.writeString(scriptFile, code);

            ProcessBuilder pb = new ProcessBuilder(pythonCommand(), scriptFile.toString());
            pb.directory(tempDir.toFile());
            pb.redirectErrorStream(false);
            // Force Python to use UTF-8 for stdout/stderr on Windows (avoids GBK garbling)
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.environment().put("PYTHONUTF8", "1");

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
                stdout = outBuf.toString(StandardCharsets.UTF_8).trim();
                stderr = "Execution timed out after " + TIMEOUT_MS + " ms.\n"
                       + errBuf.toString(StandardCharsets.UTF_8).trim();
                exitCode = -1;
            } else {
                // Process completed — wait for reader threads to finish
                outThread.join(5000);
                errThread.join(5000);
                stdout = outBuf.toString(StandardCharsets.UTF_8).trim();
                stderr = errBuf.toString(StandardCharsets.UTF_8).trim();
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
     * Scan code for banned third-party imports.
     * Returns a list of violation descriptions (empty if all imports are stdlib).
     */
    List<String> validateImports(String code) {
        List<String> violations = new ArrayList<>();
        Matcher m = IMPORT_PATTERN.matcher(code);
        while (m.find()) {
            String module = m.group(1) != null ? m.group(1) : m.group(2);
            if (module == null) continue;
            // Get the top-level package name (e.g., "matplotlib.pyplot" → "matplotlib")
            String topLevel = module.split("\\.")[0];
            if (BANNED_IMPORTS.contains(topLevel)) {
                violations.add(topLevel + " (import " + module + ")");
            }
        }
        return violations;
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
