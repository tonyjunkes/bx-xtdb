# bx-xtdb

Use XTDB through BoxLang's native datasources, `queryExecute()`, `bx:query`, and
`bx:transaction`. Requires **BoxLang 1.18+ and Java 21+**. The initial tested server
and official JDBC driver are **XTDB 2.2.0**.

## Install

After publication, install the compiled module from ForgeBox:

```sh
box install bx-xtdb
```

For a source checkout, build first and install the resulting ZIP into your app:

```sh
./gradlew build
box install ID=/absolute/path/to/bx-xtdb/build/distributions/bx-xtdb-0.1.0.zip directory=boxlang_modules production=true save=false
```

The distribution contains the module and its Java dependencies under `libs/`.
The source checkout itself is not the compiled installable module.

## Configure a datasource

In your application's `Application.bx`:

```javascript
class {
    this.name = "my-xtdb-app";
    this.datasources.xtdb = {
        driver: "xtdb",
        host: "localhost",
        port: 5432,
        database: "xtdb",
        username: getSystemSetting("XTDB_USER", "xtdb"),
        password: getSystemSetting("XTDB_PASSWORD")
    };
}
```

Alternatively, provide `connectionString: "jdbc:xtdb://host:5432/database"`.
BoxLang handles explicit connection strings and custom properties using its
normal precedence rules. When constructing the URL, this driver defaults to
`localhost:5432` and requires `database`. Credentials have no module defaults.

Use standard BoxLang datasource options such as `maxConnections`,
`connectionTimeout`, and `custom`. For production TLS, for example:

```javascript
custom: {
    sslmode: "verify-full",
    sslrootcert: "/path/to/root.crt"
}
```

Runtime-wide configuration is supported too; see the tested
[runtime configuration](https://github.com/tonyjunkes/bx-xtdb/blob/main/src/test/runtime/boxlang.json). Its environment placeholders
use BoxLang's `${env.NAME:default}` syntax. Set `defaultDatasource` to select a
runtime default, or pass `{datasource: "xtdb"}` explicitly for each query.

## Query and bind values

```javascript
queryExecute(
    "INSERT INTO users (_id, name) VALUES (?, ?)",
    ["user-1", "Tony"],
    {datasource: "xtdb"}
);

users = queryExecute(
    "SELECT _id, name FROM users WHERE _id = :id",
    {id: "user-1"},
    {datasource: "xtdb", returntype: "array"}
);
```

XTDB requires `_id`. Re-inserting an existing ID replaces its document over the
specified valid-time range; it is not a relational uniqueness violation.
Use XTDB `ASSERT` when a write must enforce a condition.

For nested documents and lossless zoned timestamps, use an explicit `other` parameter:

```javascript
queryExecute(
    "INSERT INTO users (_id, profile, observed_at) VALUES (?, ?, ?)",
    [
        "user-2",
        {value: {roles: ["reader", "writer"], preferences: {theme: "dark"}}, sqltype: "other"},
        {value: now(), sqltype: "other"}
    ],
    {datasource: "xtdb"}
);
```

Ordinary SQL parameter types keep BoxLang's normal coercions. In particular,
`timestamp` binding follows the runtime's JDBC timestamp conversion; choose
`other` for preserving a `DateTime`'s zone and fractional precision.

| Result value | BoxLang representation |
| --- | --- |
| Strings, numbers, booleans, null, binary | Original scalar value and precision |
| Records with string keys | Native ordered, case-sensitive structs, recursively |
| Lists and object arrays | Native arrays, recursively |
| Zoned/offset timestamps | Native `DateTime`, preserving zone/offset and precision |
| Local dates/times, timezone-free timestamps | Original Java temporal value |
| UUIDs, intervals, sets, non-string-keyed maps | Original Java value |

Nested structs distinguish `Name` and `name`. Query column naming continues to
follow BoxLang's native query behavior. Input collections are copied; cyclic
collections are rejected. Specialized Java values remain accessible through
BoxLang's Java interoperability. No automatic JSON conversion is performed.

## Transactions and history

Use native `bx:transaction` blocks for atomic write groups and read-only groups.
XTDB does not support mixing query results and writes in a single transaction.
Synchronous commit is the XTDB default. See [transactions and time](docs/transactions.md)
and the runnable [temporal example](examples/Temporal.bxs).

BoxLang manages connection pooling. Independent `queryExecute()` calls may use
different physical connections. Synchronous commit completes indexing on the
connected node; it does not provide an automatic await-token protocol across
nodes. SQLState is available on the original database exception's `cause` in
BoxLang 1.18; the runtime's top-level `sqlstate` field can be empty.

## Development and verification

Install a JDK 21, CommandBox, and a Docker-compatible runtime. On macOS:

```sh
brew install openjdk@21 docker colima
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
colima start --cpu 4 --memory 6
```

Then, from this checkout:

```sh
box install
bash scripts/xtdb.sh start
./gradlew build consumerTest runtimeConsumerTest testBox exampleTest
bash tests/ci/release-workflow-test.sh
bash scripts/xtdb.sh stop
```

The disposable container binds only to localhost and uses the development
password `xtdb`. Override `XTDB_PASSWORD`, `XTDB_PORT`, or `XTDB_HEALTH_PORT` if
needed. Readiness is bounded to 120 seconds; inspect logs with
`bash scripts/xtdb.sh logs`. Stop the container after testing. Colima can be
stopped separately with `colima stop`.

To use an existing disposable development database, omit the container commands
and set `XTDB_JDBC_URL`, `XTDB_USER`, and `XTDB_PASSWORD`. A custom published
container port also requires a matching `XTDB_JDBC_URL`. Tests and examples
write uniquely identified documents into `bxxtdb_*` tables.

JUnit covers adapter-owned conversion logic. TestBox exercises the installed
module against real XTDB, with bx-postgresql present to check classloader
coexistence. Consumer processes expose only the BoxLang runtime on their outer
classpath. Test reports are under `build/reports/tests/` and `build/testbox/`.
The build creates the compiled ZIP and SHA-256 checksum in `build/distributions/`.

## Compatibility and releases

CI builds once and tests that distribution on BoxLang 1.18.0, latest stable, and
snapshot. All must pass. XTDB 1.x, older XTDB 2.x releases, embedded nodes,
Arrow/ADBC, and complete PostgreSQL compatibility are outside this release's
support contract.

The module initializes XTDB's Clojure serializer with its module classloader and
restores the caller's context classloader afterward. A small internal JDBC
wrapper guards XTDB 2.2.0's plain-statement null-result-set bug, preserving
database diagnostics while keeping XTDB's value decoding. Re-evaluate that
wrapper when upgrading the upstream driver.

Update `box.json`, `gradle.properties`, `ModuleConfig.bx`, and the manifest's
release ZIP URL together. Runtime dependencies are locked in `gradle.lockfile`;
update deliberately with `./gradlew dependencies --configuration runtimeClasspath --write-locks`.
See [release operation](docs/releases.md) before merging a release version.

References: [XTDB Java driver](https://docs.xtdb.com/drivers/java.html),
[BoxLang module development](https://boxlang.ortusbooks.com/boxlang-framework/module-development/getting-started).
