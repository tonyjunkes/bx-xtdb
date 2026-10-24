package modules.bxxtdb;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Types;

import ortus.boxlang.runtime.config.segments.DatasourceConfig;
import ortus.boxlang.runtime.context.IBoxContext;
import ortus.boxlang.runtime.jdbc.BoxConnection;
import ortus.boxlang.runtime.jdbc.BoxStatement;
import ortus.boxlang.runtime.jdbc.drivers.GenericJDBCDriver;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.QueryColumnType;

/** BoxLang datasource configuration for the official XTDB JDBC driver. */
public class XTDBDriver extends GenericJDBCDriver {
    public XTDBDriver() {
        this.name = Key.of("xtdb");
        this.driverClassName = CompatibleXTDBDriver.class.getName();
        this.defaultPort = "5432";
        XTDBValues.initializeSerializer();
    }

    @Override
    public String buildConnectionURL(DatasourceConfig config) {
        String database = config.properties.getOrDefault(Key.database, "").toString();
        if (database.isBlank()) {
            throw new IllegalArgumentException("The database property is required for the XTDB JDBC driver");
        }
        String host = config.properties.getOrDefault(Key.host, defaultHost).toString();
        if (host.isBlank()) {
            host = defaultHost;
        }
        String port = config.properties.getOrDefault(Key.port, defaultPort).toString();
        if (port.isBlank() || port.equals("0")) {
            port = defaultPort;
        }
        String params = customParamsToQueryString(config);
        return "jdbc:xtdb://" + host + ":" + port + "/"
            + URLEncoder.encode(database, StandardCharsets.UTF_8).replace("+", "%20")
            + (params.isEmpty() ? "" : "?" + params);
    }

    @Override
    public QueryColumnType mapSQLTypeToQueryColumnType(int sqlType) {
        return sqlType == Types.BIT ? QueryColumnType.BOOLEAN : super.mapSQLTypeToQueryColumnType(sqlType);
    }

    @Override
    public Object transformValue(int sqlType, Object value, BoxStatement statement) {
        // PostgreSQL reports bool as BIT; the generic adapter converts BIT to 0/1.
        if (value instanceof Boolean) return value;
        return XTDBValues.toBoxLang(super.transformValue(sqlType, value, statement));
    }

    @Override
    public Object transformParamValue(QueryColumnType type, Object value, IBoxContext context, BoxConnection connection) {
        return type == QueryColumnType.OTHER
            ? XTDBValues.toJdbc(value)
            : super.transformParamValue(type, value, context, connection);
    }
}
