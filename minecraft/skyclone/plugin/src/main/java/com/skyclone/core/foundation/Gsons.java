package com.skyclone.core.foundation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The one Gson used for profile data. Pure (no Bukkit) so it is unit-testable.
 *
 * <p>Leniency is the point: profile JSON outlives the code that wrote it. When a module renames
 * or deletes an enum constant, old saves must still load:
 * <ul>
 *   <li>enum values match case-insensitively; an unknown name reads as {@code null}
 *       (never throws);</li>
 *   <li>maps keyed by an enum (EnumMap or Map&lt;SomeEnum, V&gt;) silently DROP entries whose key
 *       is no longer a constant — the default Gson would put a null key into an EnumMap and throw,
 *       losing the whole object;</li>
 *   <li>NaN / Infinity serialise instead of throwing (a skill multiplier gone wrong must not stop
 *       a save);</li>
 *   <li>the reader is lenient about trailing commas, comments and unquoted names.</li>
 * </ul>
 */
public final class Gsons {

    private Gsons() {}

    public static final Gson PROFILE = new GsonBuilder()
            .registerTypeAdapterFactory(new LenientEnumFactory())
            .registerTypeAdapterFactory(new EnumKeyMapFactory())
            .serializeSpecialFloatingPointValues()
            .disableHtmlEscaping()
            .setStrictness(Strictness.LENIENT)
            .create();

    // ---- enums --------------------------------------------------------------------------------

    static final class LenientEnumFactory implements TypeAdapterFactory {
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            Class<? super T> raw = type.getRawType();
            if (!Enum.class.isAssignableFrom(raw) || raw == Enum.class) return null;
            if (!raw.isEnum()) raw = raw.getSuperclass(); // constant-specific class bodies
            return (TypeAdapter<T>) new LenientEnumAdapter(raw);
        }
    }

    static final class LenientEnumAdapter<E extends Enum<E>> extends TypeAdapter<E> {
        private final Map<String, E> byName = new HashMap<>();
        private final E[] constants;

        LenientEnumAdapter(Class<E> cls) {
            constants = cls.getEnumConstants();
            for (E c : constants) byName.put(c.name().toLowerCase(Locale.ROOT), c);
        }

        E lookup(String s) {
            if (s == null) return null;
            return byName.get(s.trim().toLowerCase(Locale.ROOT));
        }

        @Override public void write(JsonWriter out, E value) throws IOException {
            if (value == null) out.nullValue(); else out.value(value.name());
        }

        @Override public E read(JsonReader in) throws IOException {
            JsonToken t = in.peek();
            if (t == JsonToken.NULL) { in.nextNull(); return null; }
            if (t == JsonToken.NUMBER) {
                int ord = in.nextInt();
                return ord >= 0 && ord < constants.length ? constants[ord] : null;
            }
            if (t == JsonToken.STRING) return lookup(in.nextString());
            in.skipValue();
            return null;
        }
    }

    // ---- maps keyed by an enum ----------------------------------------------------------------

    static final class EnumKeyMapFactory implements TypeAdapterFactory {
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            Class<? super T> raw = type.getRawType();
            if (!Map.class.isAssignableFrom(raw)) return null;
            Type t = type.getType();
            Type keyType;
            Type valueType;
            if (t instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 2) {
                keyType = pt.getActualTypeArguments()[0];
                valueType = pt.getActualTypeArguments()[1];
            } else {
                return null;
            }
            if (!(keyType instanceof Class<?> keyRaw) || !keyRaw.isEnum()) return null;
            if (!(raw == Map.class || raw == EnumMap.class || raw == HashMap.class || raw == LinkedHashMap.class
                    || raw == java.util.AbstractMap.class || raw == TreeMap.class || raw == SortedMap.class
                    || raw == ConcurrentHashMap.class || raw == ConcurrentMap.class)) return null;
            TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
            TypeAdapter<Object> valueAdapter = (TypeAdapter<Object>) gson.getAdapter(TypeToken.get(valueType));
            LenientEnumAdapter keyAdapter = new LenientEnumAdapter(keyRaw);
            Class<?> rawF = raw;
            return new TypeAdapter<T>() {
                @Override public void write(JsonWriter out, T value) throws IOException { delegate.write(out, value); }

                @Override public T read(JsonReader in) throws IOException {
                    if (in.peek() == JsonToken.NULL) { in.nextNull(); return null; }
                    Map<Object, Object> map;
                    if (rawF == EnumMap.class) map = new EnumMap(keyRaw);
                    else if (rawF == TreeMap.class || rawF == SortedMap.class) map = new TreeMap<>();
                    else if (rawF == ConcurrentHashMap.class || rawF == ConcurrentMap.class) map = new ConcurrentHashMap<>();
                    else if (rawF == HashMap.class) map = new HashMap<>();
                    else map = new LinkedHashMap<>();
                    if (in.peek() == JsonToken.BEGIN_ARRAY) {
                        // complex-key form: [[key, value], ...]
                        in.beginArray();
                        while (in.hasNext()) {
                            in.beginArray();
                            Object k = keyAdapter.read(in);
                            Object v = valueAdapter.read(in);
                            in.endArray();
                            if (k != null && v != null) map.put(k, v);
                        }
                        in.endArray();
                        return (T) map;
                    }
                    JsonElement tree = com.google.gson.JsonParser.parseReader(in);
                    if (!(tree instanceof JsonObject obj)) return (T) map;
                    for (Map.Entry<String, JsonElement> en : obj.entrySet()) {
                        Object k = keyAdapter.lookup(en.getKey());
                        if (k == null) continue; // constant removed since this was saved
                        Object v = valueAdapter.fromJsonTree(en.getValue());
                        if (v == null && (map instanceof ConcurrentMap || map instanceof TreeMap)) continue;
                        map.put(k, v);
                    }
                    return (T) map;
                }
            };
        }
    }
}
