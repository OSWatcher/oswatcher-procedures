# OSWatcher Procedures

Neo4j stored procedures for tree diffing operations.

## Procedures

### `example.diffTreesRecursive`

Recursively diff two nodes in the graph.

```cypher
CALL example.diffTreesRecursive(
    parent_label,      -- String: Node label (e.g., 'Tree', 'Blob')
    base,              -- String: Base node hash (or null)
    diffee,            -- String: Diffee node hash (or null)
    base_path,         -- String: Starting path (e.g., '/')
    filter,            -- List<String>: Child labels to include (empty = all)
    max_depth,         -- Long: Depth control (default: -1)
    with_intermediates,-- Boolean: Include intermediate nodes (default: false)
    status_filter      -- List<String>: Filter by status (default: [])
)
YIELD status, type, path, old_props, new_props
```

#### `max_depth` Parameter

Controls the depth of comparison:

| Value | Behavior |
|-------|----------|
| `0` | Compare nodes themselves only (no children) |
| `1` | Immediate children only |
| `2` | Children + grandchildren |
| `n` | n levels deep |
| `-1` | Unlimited recursion (default) |

#### `status_filter` Parameter

Filter results by diff status:
- `NEW` - Node exists only in diffee
- `MOD` - Node exists in both with different hash
- `DEL` - Node exists only in base
- `UNCHANGED` - Node exists in both with same hash (requires explicit opt-in)

Empty list = all statuses except UNCHANGED.

#### Examples

**Diff Symbols between two Blobs:**
```cypher
CALL example.diffTreesRecursive('Blob', 'hash1', 'hash2', '/', ['Symbol'], 1, false, [])
```

**Full recursive Tree diff:**
```cypher
CALL example.diffTreesRecursive('Tree', 'hash1', 'hash2', '/', [], -1, false, [])
```

**Compare two nodes directly (git log style):**
```cypher
CALL example.diffTreesRecursive('Tree', 'hash1', 'hash2', '/', [], 0, false, [])
```

**Include only MOD and NEW:**
```cypher
CALL example.diffTreesRecursive('Tree', 'hash1', 'hash2', '/', [], -1, false, ['MOD', 'NEW'])
```
