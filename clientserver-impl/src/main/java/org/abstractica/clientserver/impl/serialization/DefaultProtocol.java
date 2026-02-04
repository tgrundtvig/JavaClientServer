package org.abstractica.clientserver.impl.serialization;

import org.abstractica.clientserver.Protocol;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Default implementation of the Protocol interface.
 *
 * <p>Scans sealed interface hierarchies to build type registries,
 * assign type IDs, and compute protocol hashes.</p>
 */
public final class DefaultProtocol implements Protocol
{
    private final String hash;
    private final Map<Class<?>, Integer> typeToId;
    private final Map<Integer, Class<? extends Record>> idToType;
    private final Class<?> clientMessageType;
    private final Class<?> serverMessageType;

    private DefaultProtocol(
            String hash,
            Map<Class<?>, Integer> typeToId,
            Map<Integer, Class<? extends Record>> idToType,
            Class<?> clientMessageType,
            Class<?> serverMessageType
    )
    {
        this.hash = hash;
        this.typeToId = Map.copyOf(typeToId);
        this.idToType = Map.copyOf(idToType);
        this.clientMessageType = clientMessageType;
        this.serverMessageType = serverMessageType;
    }

    @Override
    public String getHash()
    {
        return hash;
    }

    /**
     * Returns the protocol hash as raw bytes.
     *
     * @return 32-byte hash
     */
    public byte[] getHashBytes()
    {
        return HexFormat.of().parseHex(hash);
    }

    /**
     * Gets the type ID for a message class.
     *
     * @param messageClass the message class
     * @return the type ID
     * @throws IllegalArgumentException if the class is not registered
     */
    public int getTypeId(Class<?> messageClass)
    {
        Integer id = typeToId.get(messageClass);
        if (id == null)
        {
            throw new IllegalArgumentException("Unknown message type: " + messageClass.getName());
        }
        return id;
    }

    /**
     * Gets the message class for a type ID.
     *
     * @param typeId the type ID
     * @return the message class
     * @throws IllegalArgumentException if the ID is not registered
     */
    public Class<? extends Record> getMessageClass(int typeId)
    {
        Class<? extends Record> clazz = idToType.get(typeId);
        if (clazz == null)
        {
            throw new IllegalArgumentException("Unknown type ID: " + typeId);
        }
        return clazz;
    }

    /**
     * Checks if a type ID is a client message.
     *
     * @param typeId the type ID
     * @return true if client message (0x0000-0x7FFF)
     */
    public boolean isClientMessage(int typeId)
    {
        return (typeId & 0x8000) == 0;
    }

    /**
     * Checks if a type ID is a server message.
     *
     * @param typeId the type ID
     * @return true if server message (0x8000-0xFFFF)
     */
    public boolean isServerMessage(int typeId)
    {
        return (typeId & 0x8000) != 0;
    }

    /**
     * Encodes a message to bytes (type ID + payload).
     *
     * @param message the message record
     * @return encoded bytes
     */
    public byte[] encodeMessage(Record message)
    {
        int typeId = getTypeId(message.getClass());
        byte[] payload = MessageCodec.encode(message);

        ByteBuffer buffer = ByteBuffer.allocate(2 + payload.length);
        buffer.putShort((short) typeId);
        buffer.put(payload);
        return buffer.array();
    }

    /**
     * Decodes a message from bytes.
     *
     * @param data the encoded bytes (type ID + payload)
     * @return decoded message record
     */
    public Record decodeMessage(byte[] data)
    {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int typeId = buffer.getShort() & 0xFFFF;

        Class<? extends Record> clazz = getMessageClass(typeId);
        byte[] payload = new byte[buffer.remaining()];
        buffer.get(payload);

        return MessageCodec.decode(payload, clazz);
    }

    /**
     * Decodes a client message from bytes.
     *
     * <p>Use this on the server side when receiving messages from clients.
     * The returned value can be used directly with pattern matching on
     * your sealed client message interface.</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * ClientMessage msg = protocol.decodeClientMessage(data);
     * switch (msg) {
     *     case LoginRequest r -> handleLogin(r);
     *     case ChatMessage c -> handleChat(c);
     * }
     * }</pre>
     *
     * @param data the encoded bytes (type ID + payload)
     * @param <C>  the client message type
     * @return decoded client message
     * @throws IllegalArgumentException if the data contains a server message
     */
    @SuppressWarnings("unchecked")
    public <C> C decodeClientMessage(byte[] data)
    {
        int typeId = peekTypeId(data);
        if (!isClientMessage(typeId))
        {
            throw new IllegalArgumentException(
                    "Expected client message (0x0000-0x7FFF), got type ID: 0x" +
                            Integer.toHexString(typeId));
        }
        return (C) decodeMessage(data);
    }

    /**
     * Decodes a server message from bytes.
     *
     * <p>Use this on the client side when receiving messages from the server.
     * The returned value can be used directly with pattern matching on
     * your sealed server message interface.</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * ServerMessage msg = protocol.decodeServerMessage(data);
     * switch (msg) {
     *     case LoginResponse r -> handleLoginResponse(r);
     *     case ChatBroadcast b -> displayChat(b);
     * }
     * }</pre>
     *
     * @param data the encoded bytes (type ID + payload)
     * @param <S>  the server message type
     * @return decoded server message
     * @throws IllegalArgumentException if the data contains a client message
     */
    @SuppressWarnings("unchecked")
    public <S> S decodeServerMessage(byte[] data)
    {
        int typeId = peekTypeId(data);
        if (!isServerMessage(typeId))
        {
            throw new IllegalArgumentException(
                    "Expected server message (0x8000-0xFFFF), got type ID: 0x" +
                            Integer.toHexString(typeId));
        }
        return (S) decodeMessage(data);
    }

    /**
     * Peeks at the type ID without fully decoding the message.
     *
     * @param data the encoded message bytes
     * @return the type ID
     */
    public int peekTypeId(byte[] data)
    {
        if (data.length < 2)
        {
            throw new IllegalArgumentException("Data too short to contain type ID");
        }
        return ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
    }

    /**
     * Returns the client message base type.
     *
     * @return client message sealed interface
     */
    public Class<?> getClientMessageType()
    {
        return clientMessageType;
    }

    /**
     * Returns the server message base type.
     *
     * @return server message sealed interface
     */
    public Class<?> getServerMessageType()
    {
        return serverMessageType;
    }

    // ========== Builder ==========

    /**
     * Builder for constructing a DefaultProtocol.
     */
    public static class Builder implements Protocol.Builder
    {
        private Class<?> clientMessageType;
        private Class<?> serverMessageType;

        @Override
        public Builder clientMessages(Class<?> sealedInterface)
        {
            this.clientMessageType = Objects.requireNonNull(sealedInterface, "sealedInterface");
            return this;
        }

        @Override
        public Builder serverMessages(Class<?> sealedInterface)
        {
            this.serverMessageType = Objects.requireNonNull(sealedInterface, "sealedInterface");
            return this;
        }

        @Override
        public DefaultProtocol build()
        {
            if (clientMessageType == null)
            {
                throw new IllegalStateException("Client message type not set");
            }
            if (serverMessageType == null)
            {
                throw new IllegalStateException("Server message type not set");
            }

            validateSealedInterface(clientMessageType);
            validateSealedInterface(serverMessageType);

            List<Class<? extends Record>> clientTypes = collectPermittedTypes(clientMessageType);
            List<Class<? extends Record>> serverTypes = collectPermittedTypes(serverMessageType);

            Map<Class<?>, Integer> typeToId = new HashMap<>();
            Map<Integer, Class<? extends Record>> idToType = new HashMap<>();

            // Assign client IDs (0x0000 - 0x7FFF)
            int clientId = 0;
            for (Class<? extends Record> type : clientTypes)
            {
                if (clientId > 0x7FFF)
                {
                    throw new IllegalArgumentException("Too many client message types (max 32768)");
                }
                typeToId.put(type, clientId);
                idToType.put(clientId, type);
                clientId++;
            }

            // Assign server IDs (0x8000 - 0xFFFF)
            int serverId = 0x8000;
            for (Class<? extends Record> type : serverTypes)
            {
                if (serverId > 0xFFFF)
                {
                    throw new IllegalArgumentException("Too many server message types (max 32768)");
                }
                typeToId.put(type, serverId);
                idToType.put(serverId, type);
                serverId++;
            }

            String hash = computeHash(clientTypes, serverTypes);

            return new DefaultProtocol(hash, typeToId, idToType, clientMessageType, serverMessageType);
        }

        private void validateSealedInterface(Class<?> clazz)
        {
            if (!clazz.isSealed())
            {
                throw new IllegalArgumentException("Not a sealed interface: " + clazz.getName());
            }
            if (!clazz.isInterface())
            {
                throw new IllegalArgumentException("Not an interface: " + clazz.getName());
            }
        }

        @SuppressWarnings("unchecked")
        private List<Class<? extends Record>> collectPermittedTypes(Class<?> sealedInterface)
        {
            List<Class<? extends Record>> types = new ArrayList<>();
            collectPermittedTypesRecursive(sealedInterface, types);

            // Sort by fully-qualified name for deterministic ordering
            types.sort(Comparator.comparing(Class::getName));

            return types;
        }

        @SuppressWarnings("unchecked")
        private void collectPermittedTypesRecursive(Class<?> sealedInterface, List<Class<? extends Record>> types)
        {
            Class<?>[] permitted = sealedInterface.getPermittedSubclasses();
            if (permitted == null)
            {
                return;
            }

            for (Class<?> subclass : permitted)
            {
                if (subclass.isRecord())
                {
                    validateRecordType((Class<? extends Record>) subclass);
                    types.add((Class<? extends Record>) subclass);
                }
                else if (subclass.isSealed())
                {
                    // Nested sealed interface - recurse
                    collectPermittedTypesRecursive(subclass, types);
                }
                else
                {
                    throw new IllegalArgumentException(
                            "Permitted type must be a record or sealed interface: " + subclass.getName());
                }
            }
        }

        private void validateRecordType(Class<? extends Record> recordClass)
        {
            RecordComponent[] components = recordClass.getRecordComponents();
            for (RecordComponent component : components)
            {
                validateComponentType(component.getGenericType(), recordClass.getName() + "." + component.getName());
            }
        }

        private void validateComponentType(Type type, String context)
        {
            Class<?> rawType = getRawType(type);

            // Primitives
            if (rawType.isPrimitive()) return;
            if (rawType == Byte.class || rawType == Short.class || rawType == Integer.class ||
                    rawType == Long.class || rawType == Float.class || rawType == Double.class ||
                    rawType == Boolean.class || rawType == Character.class) return;

            // String, byte[]
            if (rawType == String.class || rawType == byte[].class) return;

            // Enum
            if (rawType.isEnum()) return;

            // Nested record
            if (rawType.isRecord())
            {
                validateRecordType((Class<? extends Record>) rawType);
                return;
            }

            // List<T>
            if (rawType == List.class)
            {
                Type elementType = getTypeArgument(type, 0);
                validateComponentType(elementType, context + " (list element)");
                return;
            }

            // Optional<T>
            if (rawType == Optional.class)
            {
                Type elementType = getTypeArgument(type, 0);
                validateComponentType(elementType, context + " (optional element)");
                return;
            }

            throw new IllegalArgumentException("Unsupported type in " + context + ": " + rawType.getName());
        }

        private String computeHash(List<Class<? extends Record>> clientTypes, List<Class<? extends Record>> serverTypes)
        {
            try
            {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");

                for (Class<? extends Record> type : clientTypes)
                {
                    appendTypeToDigest(digest, type);
                }
                for (Class<? extends Record> type : serverTypes)
                {
                    appendTypeToDigest(digest, type);
                }

                byte[] hash = digest.digest();
                return HexFormat.of().formatHex(hash);
            }
            catch (NoSuchAlgorithmException e)
            {
                throw new RuntimeException("SHA-256 not available", e);
            }
        }

        private void appendTypeToDigest(MessageDigest digest, Class<? extends Record> type)
        {
            // Append type name
            digest.update(type.getName().getBytes(StandardCharsets.UTF_8));

            // Append each component
            RecordComponent[] components = type.getRecordComponents();
            for (RecordComponent component : components)
            {
                digest.update(component.getName().getBytes(StandardCharsets.UTF_8));
                String descriptor = getTypeDescriptor(component.getGenericType());
                digest.update(descriptor.getBytes(StandardCharsets.UTF_8));
            }
        }

        private String getTypeDescriptor(Type type)
        {
            Class<?> rawType = getRawType(type);

            // Primitives
            if (rawType == byte.class) return "B";
            if (rawType == short.class) return "S";
            if (rawType == int.class) return "I";
            if (rawType == long.class) return "J";
            if (rawType == float.class) return "F";
            if (rawType == double.class) return "D";
            if (rawType == boolean.class) return "Z";
            if (rawType == char.class) return "C";

            // Boxed primitives map to primitives for hash
            if (rawType == Byte.class) return "B";
            if (rawType == Short.class) return "S";
            if (rawType == Integer.class) return "I";
            if (rawType == Long.class) return "J";
            if (rawType == Float.class) return "F";
            if (rawType == Double.class) return "D";
            if (rawType == Boolean.class) return "Z";
            if (rawType == Character.class) return "C";

            // String
            if (rawType == String.class) return "Ljava/lang/String;";

            // byte[]
            if (rawType == byte[].class) return "[B";

            // List
            if (rawType == List.class)
            {
                Type elementType = getTypeArgument(type, 0);
                return "Ljava/util/List<" + getTypeDescriptor(elementType) + ">;";
            }

            // Optional
            if (rawType == Optional.class)
            {
                Type elementType = getTypeArgument(type, 0);
                return "Ljava/util/Optional<" + getTypeDescriptor(elementType) + ">;";
            }

            // Enum or Record
            if (rawType.isEnum() || rawType.isRecord())
            {
                return "L" + rawType.getName().replace('.', '/') + ";";
            }

            throw new IllegalArgumentException("Unsupported type: " + rawType.getName());
        }

        private Class<?> getRawType(Type type)
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

        private Type getTypeArgument(Type type, int index)
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
}
