# Third-party software

The distribution includes the unmodified XTDB API 2.2.0 JAR and its resolved
runtime dependencies. XTDB is published under MPL-2.0; source is available at
https://github.com/xtdb/xtdb/tree/v2.2.0. This module's MIT license applies to its
own source. Upstream license and notice resources remain inside their JARs.

Dependencies include PostgreSQL JDBC (BSD-2-Clause), Clojure and spec.alpha
(EPL-1.0), Cognitect Transit (Apache-2.0), Kotlin, kotlinx.serialization,
Apache Arrow, Netty, Protocol Buffers, Jackson, Caffeine, and their dependencies.
Use `./gradlew dependencies --configuration runtimeClasspath` for the resolved graph.
