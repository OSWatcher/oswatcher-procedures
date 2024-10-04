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
            @Name(value = "max_depth", defaultValue = "-1") long maxDepth) {

        List<String> effectiveFilter = (filter != null) ? filter : Collections.emptyList();
        log.info("Starting diff between base hash: " + baseHash + " and diffee hash: " + diffeeHash);
        // filter should contain the parent label
        effectiveFilter.add(parentLabel);

        List<DiffResult> results = new ArrayList<>();
        diffRecursive(parentLabel, baseHash, diffeeHash, basePath, 0, maxDepth, effectiveFilter, results);

        log.info("Diff process completed. Total results: " + results.size());

        return results.stream();
    }

    private void diffRecursive(String parentLabel, String baseHash, String diffeeHash, String path, int depth,
            long maxDepth, List<String> filter, List<DiffResult> results) {
        if (!canRecurse(depth, maxDepth)) {
            return;
        }

        Node base = baseHash != null ? tx.findNode(Label.label(parentLabel), "hash", baseHash) : null;
        Node diffee = diffeeHash != null ? tx.findNode(Label.label(parentLabel), "hash", diffeeHash) : null;

        if (base == null && diffee == null) {
            log.warn("Both base and diffee nodes are null for path: " + path);
            return;
        }

        Map<String, NodeInfo> baseEntries = base != null ? collectNodeInfo(base, filter) : new HashMap<>();
        Map<String, NodeInfo> diffeeEntries = diffee != null ? collectNodeInfo(diffee, filter) : new HashMap<>();

        Set<String> allKeys = new HashSet<>(baseEntries.keySet());
        allKeys.addAll(diffeeEntries.keySet());

        for (String name : allKeys) {
            NodeInfo baseInfo = baseEntries.get(name);
            NodeInfo diffeeInfo = diffeeEntries.get(name);
            String currentPath = Paths.get(path, name).toString();

            if (baseInfo == null && diffeeInfo != null) {
                results.add(new DiffResult("NEW", diffeeInfo.type, currentPath, null, diffeeInfo.properties));
                if (isRecursableLabel(diffeeInfo.type)) {
                    diffRecursive(parentLabel, null, diffeeInfo.hash, currentPath, depth + 1, maxDepth, filter,
                            results);
                }
            } else if (baseInfo != null && diffeeInfo == null) {
                results.add(new DiffResult("DEL", baseInfo.type, currentPath, baseInfo.properties, null));
                if (isRecursableLabel(baseInfo.type)) {
                    diffRecursive(parentLabel, baseInfo.hash, null, currentPath, depth + 1, maxDepth, filter, results);
                }
            } else if (baseInfo != null && diffeeInfo != null && !baseInfo.hash.equals(diffeeInfo.hash)) {
                results.add(
                        new DiffResult("MOD", baseInfo.type, currentPath, baseInfo.properties, diffeeInfo.properties));
                if (isRecursableLabel(baseInfo.type)) {
                    diffRecursive(parentLabel, baseInfo.hash, diffeeInfo.hash, currentPath, depth + 1, maxDepth, filter,
                            results);
                }
            }
        }
    }

    private boolean canRecurse(int currentDepth, long maxDepth) {
        return maxDepth == -1 || currentDepth <= maxDepth;
    }

    private boolean isRecursableLabel(String type) {
        return "Tree".equals(type) || "WinRegKey".equals(type);
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
                            propertiesToMap(child)));
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

    private Map<String, Object> propertiesToMap(Node node) {
        Map<String, Object> props = new HashMap<>();
        for (String key : node.getPropertyKeys()) {
            props.put(key, node.getProperty(key));
        }
        return props;
    }

    private static class NodeInfo {
        String type;
        String hash;
        Map<String, Object> properties;

        NodeInfo(String type, String hash, Map<String, Object> properties) {
            this.type = type;
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