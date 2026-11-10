package io.github.insideranh.stellarprotect.gson;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Gson has no built-in support for the {@code java.util.Optional} family:
 * its reflective adapter tries to access the private {@code value} field,
 * which the module system forbids on Java 17+ (java.base does not open
 * java.util to unnamed modules). Any serialisation that walks into an
 * Optional then fails with InaccessibleObjectException — for example a
 * Paper ItemStack, whose component map stores Optional values, inside a
 * kill log's entity data.
 *
 * This factory unwraps present values on write (empty becomes JSON null)
 * and wraps them back on read, so the shared Gson instance can serialise
 * anything containing Optionals.
 */
public class OptionalTypeAdapterFactory implements TypeAdapterFactory {

    @Override
    @SuppressWarnings("unchecked")
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        Class<?> raw = type.getRawType();
        if (raw == Optional.class) {
            TypeAdapter<Object> valueAdapter = adapterFor(gson, firstTypeArgument(type.getType()));
            return (TypeAdapter<T>) new TypeAdapter<Optional<Object>>() {
                @Override
                public void write(JsonWriter out, Optional<Object> value) throws IOException {
                    if (value == null || value.isEmpty()) {
                        out.nullValue();
                        return;
                    }
                    valueAdapter.write(out, value.get());
                }

                @Override
                public Optional<Object> read(JsonReader in) throws IOException {
                    return Optional.ofNullable(valueAdapter.read(in));
                }
            };
        }
        if (raw == OptionalInt.class) {
            return (TypeAdapter<T>) new TypeAdapter<OptionalInt>() {
                @Override
                public void write(JsonWriter out, OptionalInt value) throws IOException {
                    if (value == null || value.isEmpty()) out.nullValue();
                    else out.value(value.getAsInt());
                }

                @Override
                public OptionalInt read(JsonReader in) throws IOException {
                    if (in.peek() == JsonToken.NULL) {
                        in.nextNull();
                        return OptionalInt.empty();
                    }
                    return OptionalInt.of(in.nextInt());
                }
            };
        }
        if (raw == OptionalLong.class) {
            return (TypeAdapter<T>) new TypeAdapter<OptionalLong>() {
                @Override
                public void write(JsonWriter out, OptionalLong value) throws IOException {
                    if (value == null || value.isEmpty()) out.nullValue();
                    else out.value(value.getAsLong());
                }

                @Override
                public OptionalLong read(JsonReader in) throws IOException {
                    if (in.peek() == JsonToken.NULL) {
                        in.nextNull();
                        return OptionalLong.empty();
                    }
                    return OptionalLong.of(in.nextLong());
                }
            };
        }
        if (raw == OptionalDouble.class) {
            return (TypeAdapter<T>) new TypeAdapter<OptionalDouble>() {
                @Override
                public void write(JsonWriter out, OptionalDouble value) throws IOException {
                    if (value == null || value.isEmpty()) out.nullValue();
                    else out.value(value.getAsDouble());
                }

                @Override
                public OptionalDouble read(JsonReader in) throws IOException {
                    if (in.peek() == JsonToken.NULL) {
                        in.nextNull();
                        return OptionalDouble.empty();
                    }
                    return OptionalDouble.of(in.nextDouble());
                }
            };
        }
        return null;
    }

    private static Type firstTypeArgument(Type type) {
        if (type instanceof ParameterizedType) {
            Type[] args = ((ParameterizedType) type).getActualTypeArguments();
            if (args.length == 1) return args[0];
        }
        return Object.class;
    }

    @SuppressWarnings("unchecked")
    private static TypeAdapter<Object> adapterFor(Gson gson, Type valueType) {
        return (TypeAdapter<Object>) gson.getAdapter(TypeToken.get(valueType));
    }
}
