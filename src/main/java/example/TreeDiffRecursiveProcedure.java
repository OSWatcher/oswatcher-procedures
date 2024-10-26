package example;

import org.neo4j.graphdb.*;
import org.neo4j.procedure.*;
import org.neo4j.logging.Log;
import java.util.*;
import java.util.stream.Stream;
import java.nio.file.Paths;

public class TreeDiffRecursiveProcedure {

    @Context
    public Transaction tx;

    @Context
    public Log log;

    @Procedure(value = "example.diffTreesRecursive", mode = Mode.READ)
    @Description("Recursively diff two trees with optional max depth")
    public Stream<DiffResult> diffTreesRecursive(
            @Name("parent_label") String parentLabel,
            @Name("base") String baseHash,
            @Name("diffee") String diffeeHash,
            @Name("base_path") String basePath,
            @Name("filter") List<String> filter,
            @Name(value = "max_depth", defaultValue = "-1") long maxDepth,
            @Name(value = "with_intermediates", defaultValue = "false") boolean withIntermediates) {

        List<String> effectiveFilter = (filter != null) ? filter : Collections.emptyList();
        log.info("Starting diff between base hash: " + baseHash + " and diffee hash: " + diffeeHash);
        // filter should contain the parent label
        effectiveFilter.add(parentLabel);

        List<DiffResult> results = new ArrayList<>();
        diffRecursive(parentLabel, baseHash, diffeeHash, basePath, 0, maxDepth, withIntermediates, effectiveFilter,
                results);

        log.info("Diff process completed. Total results: " + results.size());

        return results.stream();
    }

    private void diffRecursive(String parentLabel, String baseHash, String diffeeHash, String path, int depth,
            long maxDepth, boolean withIntermediates, List<String> filter, List<DiffResult> results) {

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
                    if (withIntermediates) {
                        results.add(new DiffResult("NEW", diffeeInfo.label, currentPath, null, diffeeInfo.properties));
                    }
                    diffRecursive(diffeeInfo.label, null, diffeeInfo.hash, currentPath, depth + 1, maxDepth,
                            withIntermediates, filter,
                            results);
                } else {
                    results.add(new DiffResult("NEW", diffeeInfo.label, currentPath, null, diffeeInfo.properties));
                }
            } else if (baseInfo != null && diffeeInfo == null) {
                if (canRecurse(depth, maxDepth) && isRecursableLabel(baseInfo.label)) {
                    if (withIntermediates) {
                        results.add(new DiffResult("DEL", baseInfo.label, currentPath, baseInfo.properties, null));
                    }
                    diffRecursive(baseInfo.label, baseInfo.hash, null, currentPath, depth + 1, maxDepth,
                            withIntermediates,
                            filter, results);
                } else {
                    results.add(new DiffResult("DEL", baseInfo.label, currentPath, baseInfo.properties, null));
                }
            } else if (baseInfo != null && diffeeInfo != null && !baseInfo.hash.equals(diffeeInfo.hash)) {
                if (canRecurse(depth, maxDepth) && isRecursableLabel(baseInfo.label)) {
                    if (withIntermediates) {
                        results.add(new DiffResult("MOD", baseInfo.label, currentPath, baseInfo.properties,
                                diffeeInfo.properties));
                    }
                    diffRecursive(baseInfo.label, baseInfo.hash, diffeeInfo.hash, currentPath, depth + 1, maxDepth,
                            withIntermediates, filter,
                            results);
                } else {
                    results.add(
                            new DiffResult("MOD", baseInfo.label, currentPath, baseInfo.properties,
                                    diffeeInfo.properties));
                }
            }
        }
    }

    private boolean canRecurse(int currentDepth, long maxDepth) {
        return maxDepth == -1 || currentDepth < maxDepth;
    }

    private boolean isRecursableLabel(String type) {
        return "Tree".equals(type) || "WinRegKey".equals(type) || "WinStruct".equals(type)
                || "WinStructField".equals(type);
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
