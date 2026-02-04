package org.abstractica.clientserver.impl.serialization;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Encodes and decodes Java records to/from wire format.
 *
 * <p>Supports:</p>
 * <ul>
 *   <li>Primitives: byte, short, int, long, float, double, boolean, char</li>
 *   <li>String: 2-byte length + UTF-8 bytes</li>
 *   <li>byte[]: 4-byte length + raw bytes</li>
 *   <li>List&lt;T&gt;: 2-byte count + serialized elements</li>
 *   <li>Optional&lt;T&gt;: 1-byte presence + value if present</li>
 *   <li>Enum: 2-byte ordinal</li>
 *   <li>Nested records: serialized fields concatenated</li>
 * </ul>
 */
public final class MessageCodec
{
    private MessageCodec() {}

    /**
     * Maximum string length in bytes (2-byte length field).
     */
    public static final int MAX_STRING_LENGTH = 65535;

    /**
     * Maximum list size (2-byte count field).
     */
    public static final int MAX_LIST_SIZE = 65535;

    // ========== Encoding ==========

    /**
     * Encodes a record to bytes.
     *
     * @param record the record to encode
     * @return encoded bytes
     */
    public static byte[] encode(Record record)
    {
        Objects.requireNonNull(record, "record");

        ByteBuffer buffer = ByteBuffer.allocate(calculateSize(record));
        encodeRecord(buffer, record);
        return buffer.array();
    }

    /**
     * Encodes a record into a ByteBuffer.
     *
     * @param buffer the buffer to write to
     * @param record the record to encode
     */
    public static void encodeRecord(ByteBuffer buffer, Record record)
    {
        Class<?> recordClass = record.getClass();
        RecordComponent[] components = recordClass.getRecordComponents();

        for (RecordComponent component : components)
        {
            try
            {
                Object value = component.getAccessor().invoke(record);
                encodeValue(buffer, value, component.getGenericType());
            }
            catch (ReflectiveOperationException e)
            {
                throw new RuntimeException("Failed to encode component: " + component.getName(), e);
            }
        }
    }

    /**
     * Encodes a value based on its type.
     */
    @SuppressWarnings("unchecked")
    private static void encodeValue(ByteBuffer buffer, Object value, Type type)
    {
        Class<?> rawType = getRawType(type);

        // Primitives
        if (rawType == byte.class || rawType == Byte.class)
        {
            buffer.put((Byte) value);
        }
        else if (rawType == short.class || rawType == Short.class)
        {
            buffer.putShort((Short) value);
        }
        else if (rawType == int.class || rawType == Integer.class)
        {
            buffer.putInt((Integer) value);
        }
        else if (rawType == long.class || rawType == Long.class)
        {
            buffer.putLong((Long) value);
        }
        else if (rawType == float.class || rawType == Float.class)
        {
            buffer.putFloat((Float) value);
        }
        else if (rawType == double.class || rawType == Double.class)
        {
            buffer.putDouble((Double) value);
        }
        else if (rawType == boolean.class || rawType == Boolean.class)
        {
            buffer.put((byte) ((Boolean) value ? 1 : 0));
        }
        else if (rawType == char.class || rawType == Character.class)
        {
            buffer.putChar((Character) value);
        }
        // String
        else if (rawType == String.class)
        {
            encodeString(buffer, (String) value);
        }
        // byte[]
        else if (rawType == byte[].class)
        {
            encodeByteArray(buffer, (byte[]) value);
        }
        // List
        else if (rawType == List.class)
        {
            Type elementType = getTypeArgument(type, 0);
            encodeList(buffer, (List<?>) value, elementType);
        }
        // Optional
        else if (rawType == Optional.class)
        {
            Type elementType = getTypeArgument(type, 0);
            encodeOptional(buffer, (Optional<?>) value, elementType);
        }
        // Enum
        else if (rawType.isEnum())
        {
            buffer.putShort((short) ((Enum<?>) value).ordinal());
        }
        // Nested record
        else if (rawType.isRecord())
        {
            encodeRecord(buffer, (Record) value);
        }
        else
        {
            throw new IllegalArgumentException("Unsupported type: " + rawType.getName());
        }
    }

    private static void encodeString(ByteBuffer buffer, String value)
    {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_LENGTH)
        {
            throw new IllegalArgumentException("String too long: " + bytes.length + " bytes (max " + MAX_STRING_LENGTH + ")");
        }
        buffer.putShort((short) bytes.length);
        buffer.put(bytes);
    }

    private static void encodeByteArray(ByteBuffer buffer, byte[] value)
    {
        buffer.putInt(value.length);
        buffer.put(value);
    }

    private static void encodeList(ByteBuffer buffer, List<?> list, Type elementType)
    {
        if (list.size() > MAX_LIST_SIZE)
        {
            throw new IllegalArgumentException("List too large: " + list.size() + " elements (max " + MAX_LIST_SIZE + ")");
        }
        buffer.putShort((short) list.size());
        for (Object element : list)
        {
            encodeValue(buffer, element, elementType);
        }
    }

    private static void encodeOptional(ByteBuffer buffer, Optional<?> optional, Type elementType)
    {
        if (optional.isPresent())
        {
            buffer.put((byte) 1);
            encodeValue(buffer, optional.get(), elementType);
        }
        else
        {
            buffer.put((byte) 0);
        }
    }

    // ========== Decoding ==========

    /**
     * Decodes bytes to a record.
     *
     * @param data  the bytes to decode
     * @param clazz the record class
     * @param <T>   the record type
     * @return decoded record
     */
    public static <T extends Record> T decode(byte[] data, Class<T> clazz)
    {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(clazz, "clazz");

        ByteBuffer buffer = ByteBuffer.wrap(data);
        return decodeRecord(buffer, clazz);
    }

    /**
     * Decodes a record from a ByteBuffer.
     *
     * @param buffer the buffer to read from
     * @param clazz  the record class
     * @param <T>    the record type
     * @return decoded record
     */
    public static <T extends Record> T decodeRecord(ByteBuffer buffer, Class<T> clazz)
    {
        if (!clazz.isRecord())
        {
            throw new IllegalArgumentException("Not a record class: " + clazz.getName());
        }

        RecordComponent[] components = clazz.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] argTypes = new Class<?>[components.length];

        for (int i = 0; i < components.length; i++)
        {
            RecordComponent component = components[i];
            argTypes[i] = component.getType();
            args[i] = decodeValue(buffer, component.getGenericType());
        }

        try
        {
            Constructor<T> constructor = clazz.getDeclaredConstructor(argTypes);
            return constructor.newInstance(args);
        }
        catch (ReflectiveOperationException e)
        {
            throw new RuntimeException("Failed to construct record: " + clazz.getName(), e);
        }
    }

    /**
     * Decodes a value based on its type.
     */
    @SuppressWarnings("unchecked")
    private static Object decodeValue(ByteBuffer buffer, Type type)
    {
        Class<?> rawType = getRawType(type);

        // Primitives
        if (rawType == byte.class || rawType == Byte.class)
        {
            return buffer.get();
        }
        else if (rawType == short.class || rawType == Short.class)
        {
            return buffer.getShort();
        }
        else if (rawType == int.class || rawType == Integer.class)
        {
            return buffer.getInt();
        }
        else if (rawType == long.class || rawType == Long.class)
        {
            return buffer.getLong();
        }
        else if (rawType == float.class || rawType == Float.class)
        {
            return buffer.getFloat();
        }
        else if (rawType == double.class || rawType == Double.class)
        {
            return buffer.getDouble();
        }
        else if (rawType == boolean.class || rawType == Boolean.class)
        {
            return buffer.get() != 0;
        }
        else if (rawType == char.class || rawType == Character.class)
        {
            return buffer.getChar();
        }
        // String
        else if (rawType == String.class)
        {
            return decodeString(buffer);
        }
        // byte[]
        else if (rawType == byte[].class)
        {
            return decodeByteArray(buffer);
        }
        // List
        else if (rawType == List.class)
        {
            Type elementType = getTypeArgument(type, 0);
            return decodeList(buffer, elementType);
        }
        // Optional
        else if (rawType == Optional.class)
        {
            Type elementType = getTypeArgument(type, 0);
            return decodeOptional(buffer, elementType);
        }
        // Enum
        else if (rawType.isEnum())
        {
            int ordinal = buffer.getShort() & 0xFFFF;
            Object[] constants = rawType.getEnumConstants();
            if (ordinal >= constants.length)
            {
                throw new IllegalArgumentException("Invalid enum ordinal: " + ordinal + " for " + rawType.getName());
            }
            return constants[ordinal];
        }
        // Nested record
        else if (rawType.isRecord())
        {
            return decodeRecord(buffer, (Class<? extends Record>) rawType);
        }
        else
        {
            throw new IllegalArgumentException("Unsupported type: " + rawType.getName());
        }
    }

    private static String decodeString(ByteBuffer buffer)
    {
        int length = buffer.getShort() & 0xFFFF;
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] decodeByteArray(ByteBuffer buffer)
    {
        int length = buffer.getInt();
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return bytes;
    }

    private static List<?> decodeList(ByteBuffer buffer, Type elementType)
    {
        int count = buffer.getShort() & 0xFFFF;
        List<Object> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            list.add(decodeValue(buffer, elementType));
        }
        return list;
    }

    private static Optional<?> decodeOptional(ByteBuffer buffer, Type elementType)
    {
        byte present = buffer.get();
        if (present != 0)
        {
            return Optional.of(decodeValue(buffer, elementType));
        }
        return Optional.empty();
    }

    // ========== Size calculation ==========

    /**
     * Calculates the encoded size of a record.
     *
     * @param record the record
     * @return size in bytes
     */
    public static int calculateSize(Record record)
    {
        int size = 0;
        Class<?> recordClass = record.getClass();
        RecordComponent[] components = recordClass.getRecordComponents();

        for (RecordComponent component : components)
        {
            try
            {
                Object value = component.getAccessor().invoke(record);
                size += calculateValueSize(value, component.getGenericType());
            }
            catch (ReflectiveOperationException e)
            {
                throw new RuntimeException("Failed to calculate size for component: " + component.getName(), e);
            }
        }

        return size;
    }

    @SuppressWarnings("unchecked")
    private static int calculateValueSize(Object value, Type type)
    {
        Class<?> rawType = getRawType(type);

        if (rawType == byte.class || rawType == Byte.class) return 1;
        if (rawType == short.class || rawType == Short.class) return 2;
        if (rawType == int.class || rawType == Integer.class) return 4;
        if (rawType == long.class || rawType == Long.class) return 8;
        if (rawType == float.class || rawType == Float.class) return 4;
        if (rawType == double.class || rawType == Double.class) return 8;
        if (rawType == boolean.class || rawType == Boolean.class) return 1;
        if (rawType == char.class || rawType == Character.class) return 2;

        if (rawType == String.class)
        {
            return 2 + ((String) value).getBytes(StandardCharsets.UTF_8).length;
        }

        if (rawType == byte[].class)
        {
            return 4 + ((byte[]) value).length;
        }

        if (rawType == List.class)
        {
            Type elementType = getTypeArgument(type, 0);
            int size = 2;
            for (Object element : (List<?>) value)
            {
                size += calculateValueSize(element, elementType);
            }
            return size;
        }

        if (rawType == Optional.class)
        {
            Optional<?> optional = (Optional<?>) value;
            Type elementType = getTypeArgument(type, 0);
            if (optional.isPresent())
            {
                return 1 + calculateValueSize(optional.get(), elementType);
            }
            return 1;
        }

        if (rawType.isEnum())
        {
            return 2;
        }

        if (rawType.isRecord())
        {
            return calculateSize((Record) value);
        }

        throw new IllegalArgumentException("Unsupported type: " + rawType.getName());
    }

    // ========== Type utilities ==========

    private static Class<?> getRawType(Type type)
    {
        if (type instanceof Class<?>)
        {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType pt)
        {
            return (Class<?>) pt.getRawType();
        }
        throw new IllegalArgumentException("Cannot get raw type from: " + type);
    }

    private static Type getTypeArgument(Type type, int index)
    {
        if (type instanceof ParameterizedType pt)
        {
            Type[] args = pt.getActualTypeArguments();
            if (index < args.length)
            {
                return args[index];
            }
        }
        throw new IllegalArgumentException("No type argument at index " + index + " for: " + type);
    }
}
