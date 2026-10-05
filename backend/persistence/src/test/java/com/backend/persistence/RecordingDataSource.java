package com.backend.persistence;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

/**
 * A data source that records each statement its connections execute, with the values bound to
 * it, in the order executed: what a call locks, and in what order, read without the PROCESS
 * privilege the tests' account lacks (06 §6, D-37).
 */
final class RecordingDataSource {

    /** Each statement executed: its SQL, then the values bound, by position. */
    final List<String> executed = new CopyOnWriteArrayList<>();
    final DataSource dataSource;

    RecordingDataSource(DataSource real) {
        dataSource = proxy(DataSource.class, real, (m, args) ->
                m.getName().equals("getConnection") ? connection((Connection) call(real, m, args)) : NOT_MINE);
    }

    private Connection connection(Connection real) {
        return proxy(Connection.class, real, (m, args) -> m.getName().equals("prepareStatement")
                ? statement((PreparedStatement) call(real, m, args), (String) args[0]) : NOT_MINE);
    }

    private PreparedStatement statement(PreparedStatement real, String sql) {
        Map<Integer, Object> bound = new TreeMap<>();
        return proxy(PreparedStatement.class, real, (m, args) -> {
            String name = m.getName();
            if (name.startsWith("set") && args != null && args.length == 2 && args[0] instanceof Integer at) {
                bound.put(at, args[1]);
            } else if (name.equals("clearParameters")) {
                bound.clear();
            } else if (name.startsWith("execute") && (args == null || args.length == 0)) {
                executed.add(sql + " " + bound.values());
            }
            return NOT_MINE;
        });
    }

    private static final Object NOT_MINE = new Object();

    private interface Handler {
        /** The call's result, or {@link #NOT_MINE} to pass it to the real object. */
        Object on(Method m, Object[] args) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, T real, Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, args) -> {
            Object mine = handler.on(m, args);
            return mine != NOT_MINE ? mine : call(real, m, args);
        }));
    }

    private static Object call(Object real, Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(real, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
