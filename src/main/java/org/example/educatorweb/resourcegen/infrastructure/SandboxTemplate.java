package org.example.educatorweb.resourcegen.infrastructure;

/**
 * Constants describing available sandbox capabilities.
 * Used by generators to understand what the execution environment supports.
 */
public final class SandboxTemplate {

    private SandboxTemplate() { }

    /**
     * Returns a human-readable description of the sandbox capabilities
     * that generators can embed in prompts so the LLM understands constraints.
     */
    public static String availableFeatures() {
        return """
            ## Sandbox Capabilities

            ### Python Code Execution
            - Python 3.x interpreter available
            - Standard library modules: math, random, datetime, json, re, collections,
              itertools, functools, statistics, decimal, fractions, heapq, bisect,
              itertools, operator, typing, dataclasses, enum
            - Timeout: 30 seconds per execution
            - No network access (localhost only, no external calls)
            - No file system persistence outside the temp directory
            - No subprocess spawning
            - Output is captured via stdout/stderr

            ### HTML Sandbox
            - Self-contained HTML documents only (no external resources)
            - All CSS must be inline in <style> blocks
            - All JavaScript must be inline in <script> blocks
            - No external image sources (use data: URIs or inline SVG)
            - Content-Security-Policy is injected automatically
            - No inline event handlers (onclick, onload, etc.) — use addEventListener
            - No eval(), document.write(), or innerHTML assignments
            - No iframes with external sources
            """;
    }

    /**
     * Returns brief constraints suitable for injecting into generator prompts.
     */
    public static String promptConstraints() {
        return """
            ### Sandbox Constraints
            - Python: only standard library, 30s timeout, no network, no subprocess
            - HTML: self-contained, no external resources, CSP enforced,
              no inline event handlers, no eval/document.write/innerHTML
            """;
    }
}
