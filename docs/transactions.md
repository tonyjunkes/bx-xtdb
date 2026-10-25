# Transactions and time

These examples use a configured default datasource. Otherwise pass
`{datasource: "xtdb"}` to every `queryExecute()` call.

## Atomic writes

```javascript
bx:transaction {
    queryExecute("ASSERT NOT EXISTS (SELECT 1 FROM users WHERE _id = ?)", ["new-user"]);
    queryExecute("INSERT INTO users (_id, name) VALUES (?, ?)", ["new-user", "Tony"]);
}
```

The table in an assertion must already exist. XTDB 2.2 reports unknown tables as
errors. An initial insert can create a table automatically; explicit table
declaration is also available through XTDB SQL.

An assertion failure aborts the write group. Native rollback is supported:

```javascript
bx:transaction {
    queryExecute("INSERT INTO users (_id, name) VALUES (?, ?)", ["discard", "Temporary"]);
    bx:transaction action="rollback";
}
```

XTDB evaluates writes sequentially in an atomic transaction. Read-dependent
updates and assertions are supported, but a separate `SELECT` returning results
cannot be mixed with DML in that transaction. Query after the write block has
committed. Savepoints and interactive read/write transactions are not promised.

## Read-only transactions

```javascript
bx:transaction {
    queryExecute("SET TRANSACTION READ ONLY");
    first = queryExecute("SELECT * FROM users WHERE _id = ?", ["user-1"]);
    second = queryExecute("SELECT * FROM users WHERE _id = ?", ["user-2"]);
}
```

XTDB fixes the read snapshot at the first query. Use XTDB's explicit snapshot
token facilities when you need reproducibility across separate transactions.
The module does not manage tokens, retry writes, or change isolation semantics.

## Valid time and system time

Valid time describes when a document is true in the modeled world. System time
describes when XTDB recorded that version. XTDB maintains both histories.

```javascript
history = queryExecute(
    "SELECT _id, name, _valid_from, _valid_to, _system_from, _system_to
     FROM users FOR ALL SYSTEM_TIME FOR ALL VALID_TIME WHERE _id = ?",
    ["user-1"]
);

asOf = queryExecute(
    "SELECT name FROM users
     FOR VALID_TIME AS OF CAST(? AS TIMESTAMPTZ) WHERE _id = ?",
    ["2020-06-01T00:00:00Z", "user-1"]
);
```

`FOR ALL VALID_TIME` alone returns the valid-time history visible in the current
system snapshot. Add `FOR ALL SYSTEM_TIME` to include superseded versions.
For a system-time as-of query, bind the recorded timestamp with `sqltype: "other"`:

```javascript
earlier = queryExecute(
    "SELECT name FROM users
     FOR SYSTEM_TIME AS OF CAST(? AS TIMESTAMPTZ)
     FOR VALID_TIME AS OF TIMESTAMP '2020-06-01Z' WHERE _id = ?",
    [{value: recordedTimestamp, sqltype: "other"}, "user-1"]
);
```

Backdated corrections use XTDB's native SQL:

```javascript
queryExecute(
    "UPDATE users FOR VALID_TIME FROM TIMESTAMP '2020-01-01Z'
     TO TIMESTAMP '2021-01-01Z' SET name = ? WHERE _id = ?",
    ["Corrected name", "user-1"]
);
```

Without an explicit valid-time range, insert/update/delete operate from now
onward. A re-insert replaces the document over its effective range; `UPDATE`
changes selected fields. The module leaves these distinctions to XTDB.

## Pooling and failures

Native write blocks use JDBC commit, which is synchronous by default in XTDB.
When all pooled connections target the same node, a query after commit sees the
indexed write. With multiple nodes or asynchronous transaction options, use
XTDB's documented consistency protocol. Do not assume separate pooled query
calls share session settings or a physical connection.

Database exceptions retain their original cause. In BoxLang 1.18, access the
original SQLState with `error.cause.getSQLState()` when the top-level field is
empty. A failed query does not trigger an automatic write retry.

See [XTDB transactions and consistency](https://docs.xtdb.com/about/txs-in-xtdb.html)
and [SQL transaction reference](https://docs.xtdb.com/reference/main/sql/txs.html).
