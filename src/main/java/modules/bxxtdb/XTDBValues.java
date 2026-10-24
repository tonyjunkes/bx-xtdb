package modules.bxxtdb;

import clojure.java.api.Clojure;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ortus.boxlang.runtime.types.Array;
import ortus.boxlang.runtime.types.DateTime;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;

/** Lossless conversions at the BoxLang/XTDB value interface. */
final class XTDBValues {
    private XTDBValues() {
    }

    static void initializeSerializer() {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            // Clojure initializes resources/namespaces through the context loader.
            // Load them once within this module, before application query threads use them.
            thread.setContextClassLoader(XTDBValues.class.getClassLoader());
            Clojure.var("clojure.core", "require").invoke(Clojure.read("xtdb.serde"));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    static Object toBoxLang(Object value) {
        if (value instanceof ZonedDateTime date) {
            return new DateTime(date);
        }
        if (value instanceof OffsetDateTime date) {
            return new DateTime(date);
        }
        if (value instanceof Map<?, ?> map && map.keySet().stream().allMatch(String.class::isInstance)) {
            var result = new Struct(Struct.TYPES.LINKED_CASE_SENSITIVE);
            map.forEach((key, item) -> result.put((String) key, toBoxLang(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            var result = new Array();
            list.forEach(item -> result.add(toBoxLang(item)));
            return result;
        }
        if (value instanceof Object[] array) {
            var result = new Array();
            for (Object item : array) {
                result.add(toBoxLang(item));
            }
            return result;
        }
        return value;
    }

    static Object toJdbc(Object value) {
        return toJdbc(value, new IdentityHashMap<>());
    }

    private static Object toJdbc(Object value, IdentityHashMap<Object, Boolean> active) {
        if (value instanceof DateTime date) {
            return date.getWrapped();
        }
        boolean collection = value instanceof Map || value instanceof List || value instanceof Set || value instanceof Object[];
        if (!collection) {
            return value;
        }
        if (active.put(value, true) != null) {
            throw new IllegalArgumentException("Cyclic collections cannot be bound as XTDB parameters");
        }
        try {
            if (value instanceof IStruct struct) {
                var result = new LinkedHashMap<String, Object>();
                // get() unwraps BoxLang's internal null sentinel.
                for (var key : struct.keySet()) {
                    result.put(key.getName(), toJdbc(struct.get(key), active));
                }
                return result;
            }
            if (value instanceof Map<?, ?> map) {
                var result = new LinkedHashMap<Object, Object>();
                map.forEach((key, item) -> result.put(key, toJdbc(item, active)));
                return result;
            }
            if (value instanceof Set<?> set) {
                var result = new LinkedHashSet<Object>();
                set.forEach(item -> result.add(toJdbc(item, active)));
                return result;
            }
            var result = new ArrayList<Object>();
            if (value instanceof List<?> list) {
                list.forEach(item -> result.add(toJdbc(item, active)));
            } else {
                for (Object item : (Object[]) value) {
                    result.add(toJdbc(item, active));
                }
            }
            return result;
        } finally {
            active.remove(value);
        }
    }
}
