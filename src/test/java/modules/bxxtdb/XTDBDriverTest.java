package modules.bxxtdb;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import ortus.boxlang.runtime.config.segments.DatasourceConfig;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;

class XTDBDriverTest {
    private final XTDBDriver driver = new XTDBDriver();

    private DatasourceConfig config(IStruct properties) {
        var config = new DatasourceConfig();
        config.properties.putAll(properties);
        return config;
    }

    @Test void buildsDefaultAndConfiguredUrls() {
        assertEquals("jdbc:xtdb://localhost:5432/xtdb", driver.buildConnectionURL(config(Struct.of("database", "xtdb"))));
        assertEquals("jdbc:xtdb://db.example:6432/my%20db?sslmode=verify-full",
            driver.buildConnectionURL(config(Struct.of("database", "my db", "host", "db.example", "port", 6432,
                "custom", Struct.of("sslmode", "verify-full")))));
        assertEquals("jdbc:xtdb://localhost:5432/xtdb", driver.buildConnectionURL(config(
            Struct.of("database", "xtdb", "host", "", "port", 0))));
    }

    @Test void requiresDatabaseOnlyWhenBuildingUrl() {
        assertThrows(IllegalArgumentException.class, () -> driver.buildConnectionURL(config(Struct.of())));
        assertThrows(IllegalArgumentException.class, () -> driver.buildConnectionURL(config(Struct.of("database", " "))));
    }
}
