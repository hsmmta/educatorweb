package org.example.educatorweb.resourcegen.orchestration;

import java.util.*;

public class GenerationGraph {
    final Map<String, AgentNode> nodes;
    final Map<String, Router> routers;
    final Map<String, List<String>> edges;
    final Map<String, List<String>> fanOuts;
    final Set<String> retrySources;

    private GenerationGraph() {
        this.nodes = new LinkedHashMap<>();
        this.routers = new HashMap<>();
        this.edges = new HashMap<>();
        this.fanOuts = new HashMap<>();
        this.retrySources = new HashSet<>();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final GenerationGraph graph = new GenerationGraph();

        public Builder node(String name, AgentNode agent) {
            graph.nodes.put(name, agent);
            return this;
        }

        public Builder edge(String from, String to) {
            graph.edges.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
            return this;
        }

        public Builder fanOut(String from, List<String> toNodes) {
            graph.fanOuts.put(from, toNodes);
            for (String to : toNodes) {
                graph.nodes.putIfAbsent(to, null);
            }
            return this;
        }

        public Builder router(String node, Router router) {
            graph.routers.put(node, router);
            return this;
        }

        public Builder retryEdge(String from, String to) {
            graph.retrySources.add(from);
            return edge(from, to);
        }

        public GenerationGraph build() {
            validate();
            return graph;
        }

        private void validate() {
            Set<String> allNodes = new HashSet<>(graph.nodes.keySet());
            allNodes.add("DONE");
            allNodes.add("FALLBACK");
            // FanOut source nodes are valid edge targets even without agents
            allNodes.addAll(graph.fanOuts.keySet());

            for (var entry : graph.edges.entrySet()) {
                for (String target : entry.getValue()) {
                    if (!allNodes.contains(target)) {
                        throw new IllegalStateException(
                            "Edge target '" + target + "' not found in graph nodes");
                    }
                }
            }

            for (var entry : graph.fanOuts.entrySet()) {
                for (String target : entry.getValue()) {
                    if (!graph.nodes.containsKey(target)) {
                        throw new IllegalStateException(
                            "FanOut target '" + target + "' not found in graph nodes");
                    }
                }
            }

            String startNode = findStartNode(graph);
            if (startNode == null) {
                throw new IllegalStateException("Graph has no start node (all nodes have incoming edges)");
            }

            if (hasCycle(graph)) {
                throw new IllegalStateException("Graph contains a cycle");
            }
        }

        private static boolean hasCycle(GenerationGraph graph) {
            Set<String> visited = new HashSet<>();
            Set<String> inPath = new HashSet<>();

            for (String node : graph.nodes.keySet()) {
                if (!visited.contains(node)) {
                    if (cycleDfs(graph, node, visited, inPath)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static boolean cycleDfs(GenerationGraph graph, String node,
                                         Set<String> visited, Set<String> inPath) {
            visited.add(node);
            inPath.add(node);

            List<String> neighbors = new ArrayList<>();
            // Skip edges from retry sources to allow retry loops
            if (graph.edges.containsKey(node) && !graph.retrySources.contains(node)) {
                neighbors.addAll(graph.edges.get(node));
            }
            if (graph.fanOuts.containsKey(node)) {
                neighbors.addAll(graph.fanOuts.get(node));
            }

            for (String neighbor : neighbors) {
                // "DONE" and "FALLBACK" are terminal, no need to traverse
                if ("DONE".equals(neighbor) || "FALLBACK".equals(neighbor)) {
                    continue;
                }
                if (inPath.contains(neighbor)) {
                    return true;
                }
                if (!visited.contains(neighbor)) {
                    if (cycleDfs(graph, neighbor, visited, inPath)) {
                        return true;
                    }
                }
            }

            inPath.remove(node);
            return false;
        }
    }

    static String findStartNode(GenerationGraph graph) {
        Set<String> targets = new HashSet<>();
        for (List<String> edgeList : graph.edges.values()) targets.addAll(edgeList);
        for (List<String> fanOutList : graph.fanOuts.values()) targets.addAll(fanOutList);
        for (String node : graph.nodes.keySet()) {
            if (!targets.contains(node) && !node.equals("DONE") && !node.equals("FALLBACK")) {
                return node;
            }
        }
        return null;
    }
}
