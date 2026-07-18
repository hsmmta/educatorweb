package org.example.educatorweb.resourcegen.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Executes the whiteboard rendering pipeline (calibration → board package → video render)
 * via a Python bridge script, then reads the resulting preview.mp4 bytes.
 *
 * <p>This is a ProcessBuilder wrapper — all heavy lifting happens in Python/Node land.
 */
public class WhiteboardPipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(WhiteboardPipelineRunner.class);
    private static final long TIMEOUT_MINUTES = 10;

    private final String pythonPath;
    private final String bridgeScript;
    private final String whiteboardRoot;

    public WhiteboardPipelineRunner(String pythonPath, String bridgeScript, String whiteboardRoot) {
        this.pythonPath = pythonPath;
        this.bridgeScript = bridgeScript;
        this.whiteboardRoot = whiteboardRoot;
        log.info("WhiteboardPipelineRunner: python={}, bridge={}, whiteboard={}",
            pythonPath, bridgeScript, whiteboardRoot);
    }

    /**
     * Run the full whiteboard pipeline on a prepared working directory.
     *
     * @param workDir directory containing boards.json, script.json, and images/ subdirectory
     * @return bytes of the output preview.mp4
     * @throws IOException if the pipeline fails or the output file is missing
     */
    public byte[] run(Path workDir) throws IOException, InterruptedException {
        Path videoPath = workDir.resolve("video/preview.mp4");

        log.info("Whiteboard pipeline starting for {}", workDir);
        long start = System.currentTimeMillis();

        ProcessBuilder pb = new ProcessBuilder(
            pythonPath, bridgeScript,
            "--work-dir", workDir.toAbsolutePath().toString(),
            "--whiteboard-root", whiteboardRoot
        );
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);  // merge stderr into stdout for capture

        Process process = pb.start();
        boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Whiteboard pipeline timed out after " + TIMEOUT_MINUTES + " minutes");
        }

        int exitCode = process.exitValue();

        // Read output for diagnostics
        String output = new String(process.getInputStream().readAllBytes());
        if (exitCode != 0) {
            log.error("Whiteboard pipeline failed (exit={}): {}", exitCode, output);
            throw new IOException("Whiteboard pipeline failed with exit code " + exitCode);
        }

        if (!Files.exists(videoPath) || Files.size(videoPath) == 0) {
            throw new IOException("Whiteboard pipeline completed but preview.mp4 is missing or empty");
        }

        long elapsed = System.currentTimeMillis() - start;
        byte[] bytes = Files.readAllBytes(videoPath);
        log.info("Whiteboard pipeline finished: {} bytes in {} ms", bytes.length, elapsed);
        return bytes;
    }

    /**
     * Check whether the whiteboard runtime dependencies are available.
     * Returns true if python + bridge script exist (fast sanity check).
     */
    public boolean isAvailable() {
        try {
            // Quick check: does the bridge script exist?
            if (!Files.exists(Path.of(bridgeScript))) {
                log.warn("Whiteboard bridge script not found: {}", bridgeScript);
                return false;
            }
            // Check python can at least be invoked
            ProcessBuilder pb = new ProcessBuilder(pythonPath, "--version");
            Process p = pb.start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            log.warn("Whiteboard not available: {}", e.getMessage());
            return false;
        }
    }
}
