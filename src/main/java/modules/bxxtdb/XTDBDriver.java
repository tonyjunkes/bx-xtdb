package modules.bxxtdb;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import ortus.boxlang.runtime.config.segments.DatasourceConfig;
import ortus.boxlang.runtime.jdbc.drivers.GenericJDBCDriver;
import ortus.boxlang.runtime.scopes.Key;

/** BoxLang datasource configuration for the official XTDB JDBC driver. */
public class XTDBDriver extends GenericJDBCDriver {
    public XTDBDriver() {
        this.name = Key.of("xtdb");
        this.driverClassName = "xtdb.jdbc.Driver";
        this.defaultPort = "5432";
    }

    @Override
    public String buildConnectionURL(DatasourceConfig config) {
        String database = config.properties.getOrDefault(Key.database, "").toString();
        if (database.isBlank()) {
            throw new IllegalArgumentException("The database property is required for the XTDB JDBC driver");
        }
        String host = config.properties.getOrDefault(Key.host, defaultHost).toString();
        if (host.isBlank()) host = defaultHost;
        String port = config.properties.getOrDefault(Key.port, defaultPort).toString();
        if (port.isBlank() || port.equals("0")) port = defaultPort;
        String params = customParamsToQueryString(config);
        return "jdbc:xtdb://" + host + ":" + port + "/"
            + URLEncoder.encode(database, StandardCharsets.UTF_8).replace("+", "%20")
            + (params.isEmpty() ? "" : "?" + params);
    }
}
