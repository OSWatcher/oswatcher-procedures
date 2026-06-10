// Copyright 2021-2026 Mathieu Tarral
// SPDX-License-Identifier: Apache-2.0

package example;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TreeDiffRecursiveProcedureTest {

    private Neo4j embeddedDatabaseServer;

    @BeforeAll
    void initializeNeo4j() {
        this.embeddedDatabaseServer = Neo4jBuilders.newInProcessBuilder()
                .withProcedure(TreeDiffRecursiveProcedure.class)
                .build();
    }

    @AfterAll
    void closeNeo4j() {
        this.embeddedDatabaseServer.close();
    }

    @Test
    void diffLeafNodes_sameHash_unchanged() {
        try (
                var driver = GraphDatabase.driver(embeddedDatabaseServer.boltURI());
                var session = driver.session()) {
            // Create two Blob nodes with the same hash
            session.run("CREATE (:Blob {hash: 'abc123', content: 'hello world', size: 11})");

            // max_depth=0 means compare nodes themselves (no children)
            var records = session
                    .run("""
                            CALL example.diffTreesRecursive('Blob', 'abc123', 'abc123', '/test/path', [], 0, false, ['UNCHANGED'])
                            YIELD status, type, path, old_props, new_props
                            RETURN status, type, path, old_props, new_props
                            """)
                    .list();

            assertThat(records).hasSize(1);
            Record record = records.get(0);
            assertThat(record.get("status").asString()).isEqualTo("UNCHANGED");
            assertThat(record.get("type").asString()).isEqualTo("Blob");
            assertThat(record.get("path").asString()).isEqualTo("/test/path");
            assertThat(record.get("old_props").asMap()).containsEntry("hash", "abc123");
            assertThat(record.get("new_props").asMap()).containsEntry("hash", "abc123");
        }
    }

    @Test
    void diffLeafNodes_differentHash_modified() {
        try (
                var driver = GraphDatabase.driver(embeddedDatabaseServer.boltURI());
                var session = driver.session()) {
            // Create two Blob nodes with different hashes
            session.run("CREATE (:Blob {hash: 'hash1', content: 'old content', size: 11})");
            session.run("CREATE (:Blob {hash: 'hash2', content: 'new content', size: 11})");

            // max_depth=0 means compare nodes themselves (no children)
            var records = session.run("""
                    CALL example.diffTreesRecursive('Blob', 'hash1', 'hash2', '/modified/blob', [], 0, false, [])
                    YIELD status, type, path, old_props, new_props
                    RETURN status, type, path, old_props, new_props
                    """).list();

            assertThat(records).hasSize(1);
            Record record = records.get(0);
            assertThat(record.get("status").asString()).isEqualTo("MOD");
            assertThat(record.get("type").asString()).isEqualTo("Blob");
            assertThat(record.get("path").asString()).isEqualTo("/modified/blob");
            assertThat(record.get("old_props").asMap()).containsEntry("content", "old content");
            assertThat(record.get("new_props").asMap()).containsEntry("content", "new content");
        }
    }

    @Test
    void diffLeafNodes_baseOnly_deleted() {
        try (
                var driver = GraphDatabase.driver(embeddedDatabaseServer.boltURI());
                var session = driver.session()) {
            // Create only a base Blob node
            session.run("CREATE (:Blob {hash: 'deleted_hash', content: 'will be deleted', size: 15})");

            // max_depth=0 means compare nodes themselves (no children)
            var records = session.run("""
                    CALL example.diffTreesRecursive('Blob', 'deleted_hash', null, '/deleted/blob', [], 0, false, [])
                    YIELD status, type, path, old_props, new_props
                    RETURN status, type, path, old_props, new_props
                    """).list();

            assertThat(records).hasSize(1);
            Record record = records.get(0);
            assertThat(record.get("status").asString()).isEqualTo("DEL");
            assertThat(record.get("type").asString()).isEqualTo("Blob");
            assertThat(record.get("path").asString()).isEqualTo("/deleted/blob");
            assertThat(record.get("old_props").asMap()).containsEntry("hash", "deleted_hash");
            assertThat(record.get("new_props").isNull()).isTrue();
        }
    }

    @Test
    void diffLeafNodes_diffeeOnly_new() {
        try (
                var driver = GraphDatabase.driver(embeddedDatabaseServer.boltURI());
                var session = driver.session()) {
            // Create only a diffee Blob node
            session.run("CREATE (:Blob {hash: 'new_hash', content: 'brand new', size: 9})");

            // max_depth=0 means compare nodes themselves (no children)
            var records = session.run("""
                    CALL example.diffTreesRecursive('Blob', null, 'new_hash', '/new/blob', [], 0, false, [])
                    YIELD status, type, path, old_props, new_props
                    RETURN status, type, path, old_props, new_props
                    """).list();

            assertThat(records).hasSize(1);
            Record record = records.get(0);
            assertThat(record.get("status").asString()).isEqualTo("NEW");
            assertThat(record.get("type").asString()).isEqualTo("Blob");
            assertThat(record.get("path").asString()).isEqualTo("/new/blob");
            assertThat(record.get("old_props").isNull()).isTrue();
            assertThat(record.get("new_props").asMap()).containsEntry("hash", "new_hash");
        }
    }

    @Test
    void diffLeafNodes_statusFilter_excludesNonMatching() {
        try (
                var driver = GraphDatabase.driver(embeddedDatabaseServer.boltURI());
                var session = driver.session()) {
            // Create two Blob nodes with different hashes (would be MOD)
            session.run("CREATE (:Blob {hash: 'filter_hash1', content: 'content1'})");
            session.run("CREATE (:Blob {hash: 'filter_hash2', content: 'content2'})");

            // max_depth=0 means compare nodes themselves (no children)
            // Filter for only NEW status - should return empty since it's a MOD
            var records = session
                    .run("""
                            CALL example.diffTreesRecursive('Blob', 'filter_hash1', 'filter_hash2', '/filtered', [], 0, false, ['NEW'])
                            YIELD status, type, path, old_props, new_props
                            RETURN status, type, path, old_props, new_props
                            """)
                    .list();

            assertThat(records).isEmpty();
        }
    }

    @Test
    void diffLeafNodes_unchangedNotIncludedByDefault() {
        try (
                var driver = GraphDatabase.driver(embeddedDatabaseServer.boltURI());
                var session = driver.session()) {
            // Create a Blob node (same hash for base and diffee)
            session.run("CREATE (:Blob {hash: 'unchanged_default', content: 'same'})");

            // max_depth=0 means compare nodes themselves (no children)
            // Empty status filter should NOT include UNCHANGED
            var records = session
                    .run("""
                            CALL example.diffTreesRecursive('Blob', 'unchanged_default', 'unchanged_default', '/unchanged', [], 0, false, [])
                            YIELD status, type, path, old_props, new_props
                            RETURN status, type, path, old_props, new_props
                            """)
                    .list();

            assertThat(records).isEmpty();
        }
    }

    @Test
    void diffLeafNodes_bothNull_returnsEmpty() {
        try (
                var driver = GraphDatabase.driver(embeddedDatabaseServer.boltURI());
                var session = driver.session()) {
            // max_depth=0 means compare nodes themselves (no children)
            var records = session.run("""
                    CALL example.diffTreesRecursive('Blob', null, null, '/empty', [], 0, false, [])
                    YIELD status, type, path, old_props, new_props
                    RETURN status, type, path, old_props, new_props
                    """).list();

            assertThat(records).isEmpty();
        }
    }
}
