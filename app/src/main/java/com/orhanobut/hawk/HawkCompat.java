package com.orhanobut.hawk;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps Hawk 2.0.1 compatible with Gson versions that reject captured type variables. */
public final class HawkCompat {
    private HawkCompat() {
    }

    public static void init(Context context) {
        Hawk.init(context).setConverter(new CompatibleConverter()).build();
    }

    private static final class CompatibleConverter implements Converter {
        private static final Type LIST = TypeToken.getParameterized(List.class, Object.class).getType();
        private static final Type SET = TypeToken.getParameterized(Set.class, Object.class).getType();
        private static final Type MAP = TypeToken.getParameterized(Map.class, Object.class, Object.class).getType();
        private final Gson gson = new Gson();

        @Override
        public <T> String toString(T value) {
            return value == null ? null : gson.toJson(value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T fromString(String value, DataInfo info) {
            if (value == null) return null;
            switch (info.dataType) {
                case '0':
                    return (T) gson.fromJson(value, info.keyClazz);
                case '1':
                    return (T) list(value, info.keyClazz);
                case '2':
                    return (T) map(value, info.keyClazz, info.valueClazz);
                case '3':
                    return (T) set(value, info.keyClazz);
                default:
                    return null;
            }
        }

        private List<Object> list(String value, Class<?> elementClass) {
            if (elementClass == null) return new ArrayList<>();
            List<?> decoded = gson.fromJson(value, LIST);
            List<Object> result = new ArrayList<>(decoded.size());
            for (Object item : decoded) result.add(convert(item, elementClass));
            return result;
        }

        private Set<Object> set(String value, Class<?> elementClass) {
            Set<Object> result = new HashSet<>();
            if (elementClass == null) return result;
            Set<?> decoded = gson.fromJson(value, SET);
            for (Object item : decoded) result.add(convert(item, elementClass));
            return result;
        }

        private Map<Object, Object> map(String value, Class<?> keyClass, Class<?> valueClass) {
            Map<Object, Object> result = new HashMap<>();
            if (keyClass == null || valueClass == null) return result;
            Map<?, ?> decoded = gson.fromJson(value, MAP);
            for (Map.Entry<?, ?> entry : decoded.entrySet()) {
                result.put(convert(entry.getKey(), keyClass), convert(entry.getValue(), valueClass));
            }
            return result;
        }

        private Object convert(Object value, Class<?> target) {
            return gson.fromJson(gson.toJson(value), target);
        }
    }
}
