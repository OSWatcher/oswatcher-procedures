package example;

import org.neo4j.graphdb.*;
import org.neo4j.procedure.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import org.neo4j.logging.Log;
import java.util.concurrent.atomic.AtomicInteger;

public class TreeDiffRecursiveProcedure {

    @Context
    public Transaction tx;

    @Context
    public Log log;

    // Custom thread pool for parallel processing
    private static final ForkJoinPool forkJoinPool = new ForkJoinPool();

    @Procedure(value = "example.diffTreesRecursive")
    @Description("Recursively diff two trees with optional max depth")
    public Stream<DiffResult> diffTreesRecursive(
            @Name("base") String baseHash,
            @Name("diffee") String diffeeHash,
            @Name("base_path") String basePath,
            @Name(value = "max_depth", defaultValue = "-1") long maxDepth) {

        // Use a thread-safe queue to collect results from all parallel tasks
        ConcurrentLinkedQueue<DiffResult> results = new ConcurrentLinkedQueue<>();
        
        log.info("Starting diff between base hash: " + baseHash + " and diffee hash: " + diffeeHash);
        
        // Start the recursive diff process using ForkJoinPool
        forkJoinPool.invoke(new DiffTask(baseHash, diffeeHash, basePath, 0, maxDepth, results));

        log.info("Diff process completed. Total results: " + results.size());
        
        // Return the results as a stream
        return results.stream();
    }

    // RecursiveAction for parallel processing of tree diffs
    private class DiffTask extends RecursiveAction {
        private String baseHash;
        private String diffeeHash;
        private String path;
        private int depth;
        private long maxDepth;
        private ConcurrentLinkedQueue<DiffResult> results;

        DiffTask(String baseHash, String diffeeHash, String path, int depth, long maxDepth, ConcurrentLinkedQueue<DiffResult> results) {
            this.baseHash = baseHash;
            this.diffeeHash = diffeeHash;
            this.path = path;
            this.depth = depth;
            this.maxDepth = maxDepth;
            this.results = results;
        }

        @Override
        protected void compute() {
            // Check if we've reached the maximum depth
            if (maxDepth != -1 && depth > maxDepth) {
                return;
            }

            // Find the nodes in the database
            Node base = baseHash != null ? tx.findNode(Label.label("Tree"), "hash", baseHash) : null;
            Node diffee = diffeeHash != null ? tx.findNode(Label.label("Tree"), "hash", diffeeHash) : null;

            if (base == null && diffee == null) {
                return;
            }

            // Collect information about child nodes
            Map<String, NodeInfo> baseEntries = base != null ? collectNodeInfo(base) : new HashMap<>();
            Map<String, NodeInfo> diffeeEntries = diffee != null ? collectNodeInfo(diffee) : new HashMap<>();

            Set<String> allKeys = new HashSet<>(baseEntries.keySet());
            allKeys.addAll(diffeeEntries.keySet());

            List<DiffTask> subTasks = new ArrayList<>();

            for (String name : allKeys) {
                NodeInfo baseInfo = baseEntries.get(name);
                NodeInfo diffeeInfo = diffeeEntries.get(name);
                String currentPath = path.isEmpty() ? name : path + "/" + name;

                if (baseInfo == null && diffeeInfo != null) {
                    // New node
                    results.add(new DiffResult("NEW", diffeeInfo.type, currentPath, null, diffeeInfo.properties));
                    if (isRecursableLabel(diffeeInfo.type)) {
                        subTasks.add(new DiffTask(null, diffeeInfo.hash, currentPath, depth + 1, maxDepth, results));
                    }
                } else if (baseInfo != null && diffeeInfo == null) {
                    // Deleted node
                    results.add(new DiffResult("DEL", baseInfo.type, currentPath, baseInfo.properties, null));
                    if (isRecursableLabel(baseInfo.type)) {
                        subTasks.add(new DiffTask(baseInfo.hash, null, currentPath, depth + 1, maxDepth, results));
                    }
                } else if (baseInfo != null && diffeeInfo != null && !baseInfo.hash.equals(diffeeInfo.hash)) {
                    // Modified node
                    results.add(new DiffResult("MOD", baseInfo.type, currentPath, baseInfo.properties, diffeeInfo.properties));
                    if (isRecursableLabel(baseInfo.type)) {
                        subTasks.add(new DiffTask(baseInfo.hash, diffeeInfo.hash, currentPath, depth + 1, maxDepth, results));
                    }
                }
            }

            // Process subtasks in parallel
            invokeAll(subTasks);
        }
    }

    // Helper method to determine if a node type should be recursively processed
    private boolean isRecursableLabel(String type) {
        return "Tree".equals(type) || "WinRegKey".equals(type);
    }

    // Helper method to convert node properties to a Map
    private Map<String, Object> propertiesToMap(Node node) {
        Map<String, Object> props = new HashMap<>();
        for (String key : node.getPropertyKeys()) {
            props.put(key, node.getProperty(key));
        }
        return props;
    }

    private Map<String, NodeInfo> collectNodeInfo(Node root) {
        Map<String, NodeInfo> info = new HashMap<>();
        for (Relationship r : root.getRelationships(Direction.OUTGOING)) {
            Node child = r.getEndNode();
            try {
                String name = (String) r.getProperty("name");
                info.put(name, new NodeInfo(
                    child.getLabels().iterator().next().name(),
                    (String) child.getProperty("hash"),
                    propertiesToMap(child)
                ));
            } catch (NotFoundException e) {
                log.error("Property 'name' not found on relationship. Details: " +
                        "Root node ID: " + root.getId() +
                        ", Child node ID: " + child.getId() +
                        ", Relationship type: " + r.getType().name() +
                        ", Root node labels: " + root.getLabels() +
                        ", Child node labels: " + child.getLabels());
                throw e; // Re-throw the exception after logging
            }
        }
        return info;
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