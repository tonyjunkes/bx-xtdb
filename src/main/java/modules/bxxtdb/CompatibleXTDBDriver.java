package modules.bxxtdb;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * XTDB 2.2.0's plain Statement.getResultSet() incorrectly assumes a non-null
 * result. Guard that single case so database errors survive BoxLang's result
 * traversal. All actual result sets still use XTDB's rich-value decoding.
 */
public final class CompatibleXTDBDriver implements Driver {
    private final Driver delegate = new xtdb.jdbc.Driver();

    @Override
    public Connection connect(String url, Properties properties) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        Connection connection = delegate.connect(url, properties);
        if (connection == null) {
            return null;
        }
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {
                Connection.class
            },
            (proxy, method, args) -> {
                if (method.getName().equals("equals")) {
                    return proxy == args[0];
                }
                Object result = invoke(method, connection, args);
                if (method.getName().equals("createStatement") && result instanceof Statement statement) {
                    return wrapStatement(statement, (Connection) proxy);
                }
                return result;
            });
    }

    private Statement wrapStatement(Statement statement, Connection connection) {
        return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {
                Statement.class
            },
            (proxy, method, args) -> {
                if (method.getName().equals("equals")) {
                    return proxy == args[0];
                }
                if (method.getName().equals("getResultSet") && statement.unwrap(Statement.class).getResultSet() == null) {
                    return null;
                }
                if (method.getName().equals("getConnection")) {
                    return connection;
                }
                return invoke(method, statement, args);
            });
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith("jdbc:xtdb:");
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) throws SQLException {
        return delegate.getPropertyInfo(url, properties);
    }
    @Override
    public int getMajorVersion() {
        return delegate.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        return delegate.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant() {
        return delegate.jdbcCompliant();
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("modules.bxxtdb");
    }
}
