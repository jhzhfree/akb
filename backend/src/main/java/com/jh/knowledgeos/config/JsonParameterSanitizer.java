package com.jh.knowledgeos.config;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Keeps blank JSON text out of CAST(... AS JSON) bindings.
 *
 * MySQL rejects CAST('' AS JSON) with "The document is empty.", so one empty
 * JSON text parameter turns an otherwise valid request into a 500. The mapper
 * layer cannot tell an empty document from a missing one, so this plugin
 * normalises blank JSON parameters to an empty document before the statement is
 * bound and logs the statement that produced it, so the caller can be fixed at
 * the source instead of silently depending on this guard.
 */
@Component
@Intercepts(@Signature(type = ParameterHandler.class, method = "setParameters", args = PreparedStatement.class))
public class JsonParameterSanitizer implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(JsonParameterSanitizer.class);

    /**
     * Empty document used when the parameter is a list/array in practice.
     */
    private static final String EMPTY_ARRAY = "[]";

    /**
     * Empty document used when the parameter is an object in practice.
     */
    private static final String EMPTY_OBJECT = "{}";

    /**
     * Substrings that mark a JSON parameter holding a collection.
     */
    private static final String[] ARRAY_HINTS = { "tags", "codes", "indexes", "statuses", "queries", "citations", "gaps", "reasons", "unsupported",
        "entities" };

    private static final int MAX_DEPTH = 4;

    private static final Map<Class<?>, Field[]> JSON_FIELDS = new ConcurrentHashMap<Class<?>, Field[]>();

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        ParameterHandler handler = (ParameterHandler) invocation.getTarget();
        try {
            MetaObject meta = SystemMetaObject.forObject(handler);
            MappedStatement statement = (MappedStatement) meta.getValue("mappedStatement");
            Object parameter = handler.getParameterObject();
            if (statement != null && parameter != null) {
                sanitize(statement.getId(), parameter, 0);
            }
        } catch (Exception ex) {
            log.debug("json parameter guard skipped: {}", ex.getMessage());
        }
        return invocation.proceed();
    }

    /**
     * Walks maps, collections and parameter beans, replacing blank JSON texts in place.
     */
    private void sanitize(String statementId, Object value, int depth) {
        if (value == null || depth > MAX_DEPTH) {
            return;
        }
        if (value instanceof Map) {
            sanitizeMap(statementId, (Map<Object, Object>) value, depth);
            return;
        }
        if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                sanitize(statementId, item, depth + 1);
            }
            return;
        }
        if (isParameterBean(value)) {
            sanitizeBean(statementId, value);
        }
    }

    private void sanitizeMap(String statementId, Map<Object, Object> map, int depth) {
        for (Object key : new ArrayList<Object>(map.keySet())) {
            Object value = map.get(key);
            if (value == null) {
                continue;
            }
            if (value instanceof String && key instanceof String && isJsonName((String) key)) {
                String text = (String) value;
                if (text.trim().isEmpty()) {
                    String fallback = fallbackFor((String) key);
                    try {
                        map.put(key, fallback);
                    } catch (RuntimeException ex) {
                        continue;
                    }
                    warn(statementId, (String) key, fallback);
                }
                continue;
            }
            if (value instanceof Map || value instanceof Collection || isParameterBean(value)) {
                sanitize(statementId, value, depth + 1);
            }
        }
    }

    private void sanitizeBean(String statementId, Object bean) {
        for (Field field : jsonFields(bean.getClass())) {
            try {
                Object value = field.get(bean);
                if (!(value instanceof String) || !((String) value).trim().isEmpty()) {
                    continue;
                }
                String fallback = fallbackFor(field.getName());
                field.set(bean, fallback);
                warn(statementId, bean.getClass().getSimpleName() + "." + field.getName(), fallback);
            } catch (Exception ex) {
                log.debug("json parameter guard cannot rewrite {}: {}", field.getName(), ex.getMessage());
            }
        }
    }

    private void warn(String statementId, String parameter, String fallback) {
        log.warn("blank JSON parameter replaced with {}: statement={}, parameter={}, traceId={}", fallback, statementId, parameter, MDC.get("traceId"));
    }

    /**
     * JSON text parameters are named *Json or *Tags / *Codes in every mapper of this project.
     */
    private static boolean isJsonName(String name) {
        return name.endsWith("Json") || name.endsWith("Tags") || name.endsWith("Codes");
    }

    private static String fallbackFor(String name) {
        String lower = name.toLowerCase();
        for (String hint : ARRAY_HINTS) {
            if (lower.contains(hint)) {
                return EMPTY_ARRAY;
            }
        }
        return EMPTY_OBJECT;
    }

    private static boolean isParameterBean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Map || value instanceof Collection) {
            return false;
        }
        Class<?> type = value.getClass();
        if (type.isPrimitive() || type.getName().startsWith("java.")) {
            return false;
        }
        return !type.isArray();
    }

    /**
     * Cached per class: reflection runs on every statement otherwise.
     */
    private static Field[] jsonFields(Class<?> type) {
        Field[] cached = JSON_FIELDS.get(type);
        if (cached != null) {
            return cached;
        }
        List<Field> fields = new ArrayList<Field>();
        Class<?> current = type;
        while (current != null && !current.getName().startsWith("java.")) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType() == String.class && isJsonName(field.getName())) {
                    fields.add(field);
                }
            }
            current = current.getSuperclass();
        }
        for (Field field : fields) {
            field.setAccessible(true);
        }
        Field[] result = fields.toArray(new Field[0]);
        JSON_FIELDS.put(type, result);
        return result;
    }
}
