package org.abstractica.clientserver.impl.protocol;

import java.util.Objects;

/**
 * Graceful disconnect packet (sent encrypted).
 *
 * <p>Wire format:</p>
 * <pre>
 * [type: 1 byte = 0x40]
 * [reasonCode: 1 byte]
 * [messageLength: 2 bytes]
 * [message: variable UTF-8]
 * </pre>
 *
 * @param reasonCode disconnect reason code
 * @param message    human-readable disconnect message
 */
public record Disconnect(
        DisconnectCode reasonCode,
        String message
)
{
    public Disconnect
    {
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(message, "message");
    }

    /**
     * Creates a normal disconnect with optional message.
     *
     * @param message the disconnect message (may be empty)
     * @return Disconnect packet
     */
    public static Disconnect normal(String message)
    {
        return new Disconnect(DisconnectCode.NORMAL, message);
    }

    /**
     * Creates a kicked disconnect.
     *
     * @param message the kick reason
     * @return Disconnect packet
     */
    public static Disconnect kicked(String message)
    {
        return new Disconnect(DisconnectCode.KICKED, message);
    }

    /**
     * Creates a protocol error disconnect.
     *
     * @param message the error details
     * @return Disconnect packet
     */
    public static Disconnect protocolError(String message)
    {
        return new Disconnect(DisconnectCode.PROTOCOL_ERROR, message);
    }

    /**
     * Creates a shutdown disconnect.
     *
     * @return Disconnect packet
     */
    public static Disconnect shutdown()
    {
        return new Disconnect(DisconnectCode.SHUTDOWN, "Server shutting down");
    }

    /**
     * Disconnect reason codes.
     */
    public enum DisconnectCode
    {
        NORMAL(0x00),
        KICKED(0x01),
        PROTOCOL_ERROR(0x02),
        SHUTDOWN(0x03);

        private final int code;

        DisconnectCode(int code)
        {
            this.code = code;
        }

        public int getCode()
        {
            return code;
        }

        public static DisconnectCode fromCode(int code)
        {
            for (DisconnectCode dc : values())
            {
                if (dc.code == code)
                {
                    return dc;
                }
            }
            throw new IllegalArgumentException("Unknown disconnect code: 0x" + Integer.toHexString(code));
        }
    }
}
