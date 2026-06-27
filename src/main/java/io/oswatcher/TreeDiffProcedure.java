// Copyright 2021-2026 Mathieu Tarral
// SPDX-License-Identifier: Apache-2.0

package io.oswatcher;

import org.neo4j.graphdb.*;
import org.neo4j.procedure.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.neo4j.logging.Log;

public class TreeDiffProcedure {

	@Context
	public Transaction tx;

    @Context
    public Log log;

    @Procedure(value = "oswatcher.treeDiff")
    @Description("A simple procedure that takes two parameters and returns a hello world message")
    public Stream<DiffResult> treeDiff(
            @Name("base") String baseHash,
            @Name("diffee") String diffeeHash) {

        Node base = tx.findNode(Label.label("Tree"), "hash", baseHash);
        Node diffee = tx.findNode(Label.label("Tree"), "hash", diffeeHash);

        if (base == null || diffee == null) {
            throw new RuntimeException("One or both of the specified trees not found");
        }

        Map<String, NodeInfo> baseEntries = collectNodeInfo(base);
        Map<String, NodeInfo> diffeeEntries = collectNodeInfo(diffee);

        ConcurrentLinkedQueue<DiffResult> results = new ConcurrentLinkedQueue<>();
        AtomicInteger processedCount = new AtomicInteger(0);

        int totalEntries = baseEntries.size() + diffeeEntries.size();

        Stream.concat(baseEntries.keySet().stream(), diffeeEntries.keySet().stream())
            .distinct()
            .parallel()
            .forEach(name -> {
                NodeInfo baseInfo = baseEntries.get(name);
                NodeInfo diffeeInfo = diffeeEntries.get(name);

                if (baseInfo == null && diffeeInfo != null) {
                    results.add(new DiffResult("NEW", diffeeInfo.type, name, null, diffeeInfo.properties));
                } else if (baseInfo != null && diffeeInfo == null) {
                    results.add(new DiffResult("DEL", baseInfo.type, name, baseInfo.properties, null));
                } else if (baseInfo != null && diffeeInfo != null && !baseInfo.hash.equals(diffeeInfo.hash)) {
                    results.add(new DiffResult("MOD", baseInfo.type, name, baseInfo.properties, diffeeInfo.properties));
                }

                int processed = processedCount.incrementAndGet();
                if (processed % 1000 == 0 || processed == totalEntries) {
                    log.info("Processed " + processed + " out of " + totalEntries + " entries");
                }
            });

        return results.stream();
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
            String name = (String) r.getProperty("name");
            info.put(name, new NodeInfo(
                child.getLabels().iterator().next().name(),
                (String) child.getProperty("hash"),
                propertiesToMap(child)
            ));
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
