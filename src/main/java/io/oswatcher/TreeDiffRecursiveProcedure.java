// Copyright 2021-2026 Mathieu Tarral
// SPDX-License-Identifier: Apache-2.0

package io.oswatcher;

import org.neo4j.graphdb.*;
import org.neo4j.procedure.*;
import org.neo4j.logging.Log;
import java.util.*;
import java.util.stream.Stream;
import java.nio.file.Paths;

enum DiffStatus {
    NEW, MOD, DEL, UNCHANGED;

    public static DiffStatus fromString(String status) {
        try {
            return DiffStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid status: " + status + ". Must be one of: NEW, MOD, DEL, UNCHANGED");
        }
    }
}

public class TreeDiffRecursiveProcedure {

    @Context
    public Transaction tx;

    @Context
    public Log log;

    @Procedure(value = "oswatcher.diffTreesRecursive", mode = Mode.READ)
    @Description("Recursively diff two trees with optional max depth")
    public Stream<DiffResult> diffTreesRecursive(
            @Name("parent_label") String parentLabel,
            @Name("base") String baseHash,
            @Name("diffee") String diffeeHash,
            @Name("base_path") String basePath,
            @Name("filter") List<String> filter,
            @Name(value = "max_depth", defaultValue = "-1") long maxDepth,
            @Name(value = "with_intermediates", defaultValue = "false") boolean withIntermediates,
            @Name(value = "status_filter", defaultValue = "[]") List<String> statusFilter) {

        List<String> effectiveFilter = (filter != null) ? filter : Collections.emptyList();

        // Validate and convert status_filter to Set<DiffStatus>
        Set<DiffStatus> effectiveStatusFilter = new HashSet<>();
        if (statusFilter != null && !statusFilter.isEmpty()) {
            for (String s : statusFilter) {
                effectiveStatusFilter.add(DiffStatus.fromString(s)); // Throws if invalid
            }
        }

        log.info("Starting diff between base hash: " + baseHash + " and diffee hash: " + diffeeHash);

        // max_depth=0 means compare nodes themselves (no children)
        if (maxDepth == 0) {
            return diffLeafNodes(parentLabel, baseHash, diffeeHash, basePath, effectiveStatusFilter).stream();
        }

        // filter should contain the parent label
        effectiveFilter.add(parentLabel);

        List<DiffResult> results = new ArrayList<>();
        diffRecursive(parentLabel, baseHash, diffeeHash, basePath, 0, maxDepth, withIntermediates, effectiveFilter,
                effectiveStatusFilter, results);

        log.info("Diff process completed. Total results: " + results.size());

        return results.stream();
    }

    private void diffRecursive(String parentLabel, String baseHash, String diffeeHash, String path, int depth,
            long maxDepth, boolean withIntermediates, List<String> filter, Set<DiffStatus> statusFilter,
            List<DiffResult> results) {

        Node base = baseHash != null ? tx.findNode(Label.label(parentLabel), "hash", baseHash) : null;
        Node diffee = diffeeHash != null ? tx.findNode(Label.label(parentLabel), "hash", diffeeHash) : null;

        if (base == null && diffee == null) {
            log.warn("Both base and diffee nodes are null for path: " + path);
            return;
        }

        Map<String, NodeInfo> baseEntries = base != null ? collectNodeInfo(base, filter) : new HashMap<>();
        Map<String, NodeInfo> diffeeEntries = diffee != null ? collectNodeInfo(diffee, filter) : new HashMap<>();

        // Create a TreeSet to store all keys in sorted order
        Set<String> allKeys = new TreeSet<>(baseEntries.keySet());
        allKeys.addAll(diffeeEntries.keySet());

        for (String name : allKeys) {
            NodeInfo baseInfo = baseEntries.get(name);
            NodeInfo diffeeInfo = diffeeEntries.get(name);
            String currentPath = Paths.get(path, name).toString();

            if (baseInfo == null && diffeeInfo != null) {
                if (canRecurse(depth, maxDepth) && isRecursableLabel(diffeeInfo.label)) {
                    if (withIntermediates && shouldIncludeStatus(DiffStatus.NEW, statusFilter)) {
                        results.add(new DiffResult("NEW", diffeeInfo.label, currentPath, null, diffeeInfo.properties));
                    }
                    diffRecursive(diffeeInfo.label, null, diffeeInfo.hash, currentPath, depth + 1, maxDepth,
                            withIntermediates, filter, statusFilter,
                            results);
                } else if (shouldIncludeStatus(DiffStatus.NEW, statusFilter)) {
                    results.add(new DiffResult("NEW", diffeeInfo.label, currentPath, null, diffeeInfo.properties));
                }
            } else if (baseInfo != null && diffeeInfo == null) {
                if (canRecurse(depth, maxDepth) && isRecursableLabel(baseInfo.label)) {
                    if (withIntermediates && shouldIncludeStatus(DiffStatus.DEL, statusFilter)) {
                        results.add(new DiffResult("DEL", baseInfo.label, currentPath, baseInfo.properties, null));
                    }
                    diffRecursive(baseInfo.label, baseInfo.hash, null, currentPath, depth + 1, maxDepth,
                            withIntermediates,
                            filter, statusFilter, results);
                } else if (shouldIncludeStatus(DiffStatus.DEL, statusFilter)) {
                    results.add(new DiffResult("DEL", baseInfo.label, currentPath, baseInfo.properties, null));
                }
            } else if (baseInfo != null && diffeeInfo != null && !baseInfo.hash.equals(diffeeInfo.hash)) {
                if (canRecurse(depth, maxDepth) && isRecursableLabel(baseInfo.label)) {
                    if (withIntermediates && shouldIncludeStatus(DiffStatus.MOD, statusFilter)) {
                        results.add(new DiffResult("MOD", baseInfo.label, currentPath, baseInfo.properties,
                                diffeeInfo.properties));
                    }
                    diffRecursive(baseInfo.label, baseInfo.hash, diffeeInfo.hash, currentPath, depth + 1, maxDepth,
                            withIntermediates, filter, statusFilter,
                            results);
                } else if (shouldIncludeStatus(DiffStatus.MOD, statusFilter)) {
                    results.add(
                            new DiffResult("MOD", baseInfo.label, currentPath, baseInfo.properties,
                                    diffeeInfo.properties));
                }
            } else if (baseInfo != null && diffeeInfo != null && baseInfo.hash.equals(diffeeInfo.hash)) {
                // UNCHANGED case - node exists in both with identical hash
                if (shouldIncludeStatus(DiffStatus.UNCHANGED, statusFilter)) {
                    results.add(new DiffResult("UNCHANGED", baseInfo.label, currentPath,
                            baseInfo.properties, diffeeInfo.properties));
                }
                // No recursion needed - children are guaranteed identical by hash
            }
        }
    }

    private boolean canRecurse(int currentDepth, long maxDepth) {
        // maxDepth semantics (shifted by 1):
        // 0 = compare nodes themselves (handled at entry, never reaches here)
        // 1 = immediate children only (no recursion)
        // 2 = children + grandchildren
        // -1 = unlimited
        return maxDepth == -1 || currentDepth < maxDepth - 1;
    }

    private boolean isRecursableLabel(String type) {
        return "Tree".equals(type) || "WinRegKey".equals(type) || "Struct".equals(type)
                || "StructField".equals(type);
    }

    private List<DiffResult> diffLeafNodes(String parentLabel, String baseHash, String diffeeHash,
            String path, Set<DiffStatus> statusFilter) {
        List<DiffResult> results = new ArrayList<>();

        Node base = baseHash != null ? tx.findNode(Label.label(parentLabel), "hash", baseHash) : null;
        Node diffee = diffeeHash != null ? tx.findNode(Label.label(parentLabel), "hash", diffeeHash) : null;

        if (base == null && diffee == null) {
            log.warn("Both base and diffee leaf nodes are null for path: " + path);
            return results;
        }

        Map<String, Object> baseProps = base != null ? base.getAllProperties() : null;
        Map<String, Object> diffeeProps = diffee != null ? diffee.getAllProperties() : null;

        DiffStatus status;
        if (base == null && diffee != null) {
            status = DiffStatus.NEW;
        } else if (base != null && diffee == null) {
            status = DiffStatus.DEL;
        } else if (!baseHash.equals(diffeeHash)) {
            status = DiffStatus.MOD;
        } else {
            status = DiffStatus.UNCHANGED;
        }

        if (shouldIncludeStatus(status, statusFilter)) {
            results.add(new DiffResult(status.name(), parentLabel, path, baseProps, diffeeProps));
        }

        return results;
    }

    private boolean shouldIncludeStatus(DiffStatus status, Set<DiffStatus> statusFilter) {
        if (status == DiffStatus.UNCHANGED) {
            // UNCHANGED requires explicit opt-in (not included in empty filter)
            return statusFilter.contains(status);
        }
        return statusFilter.isEmpty() || statusFilter.contains(status);
    }

    private Map<String, NodeInfo> collectNodeInfo(Node root, List<String> filter) {
        Map<String, NodeInfo> info = new HashMap<>();
        for (Relationship r : root.getRelationships(Direction.OUTGOING)) {
            Node child = r.getEndNode();
            try {
                String childLabel = child.getLabels().iterator().next().name();

                if (filter.isEmpty() || filter.contains(childLabel)) {
                    String name = (String) r.getProperty("name");
                    info.put(name, new NodeInfo(
                            childLabel,
                            (String) child.getProperty("hash"),
                            child.getAllProperties()));
                }
            } catch (Exception e) {
                log.error("Error collecting node info: " + e.getMessage() +
                        ", Root node: " + root +
                        ", Relationship type: " + r.getType().name() +
                        ", Child node: " + child);
            }
        }
        return info;
    }

    private static class NodeInfo {
        String label;
        String hash;
        Map<String, Object> properties;

        NodeInfo(String type, String hash, Map<String, Object> properties) {
            this.label = type;
            this.hash = hash;
            this.properties = properties;
        }
    }

    public static class DiffResult {
        public String status;
        public String type;
        public String path;
        public Map<String, Object> old_props;
        public Map<String, Object> new_props;

        public DiffResult(String status, String type, String path,
                Map<String, Object> old_props, Map<String, Object> new_props) {
            this.status = status;
            this.type = type;
            this.path = path;
            this.old_props = old_props;
            this.new_props = new_props;
        }
    }
}
