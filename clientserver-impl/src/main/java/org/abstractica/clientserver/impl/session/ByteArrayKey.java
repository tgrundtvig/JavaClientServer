package org.abstractica.clientserver.impl.session;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Wrapper for byte[] to use as a map key with proper equals/hashCode.
 *
 * <p>Used for session token lookups in maps.</p>
 *
 * @param bytes the byte array to wrap
 */
public record ByteArrayKey(byte[] bytes)
{
    public ByteArrayKey
    {
        Objects.requireNonNull(bytes, "bytes");
    }

    @Override
    public boolean equals(Object o)
    {
        return o instanceof ByteArrayKey other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode()
    {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString()
    {
        return HexFormat.of().formatHex(bytes);
    }
}
