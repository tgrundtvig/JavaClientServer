package org.abstractica.clientserver.impl.protocol;

import java.util.Objects;

/**
 * Server's session rejection response (sent encrypted).
 *
 * <p>Wire format:</p>
 * <pre>
 * [type: 1 byte = 0x12]
 * [reasonCode: 1 byte]
 * [messageLength: 2 bytes]
 * [message: variable UTF-8]
 * </pre>
 *
 * @param reasonCode rejection reason code
 * @param message    human-readable rejection message
 */
public record Reject(
        RejectReason reasonCode,
        String message
)
{
    public Reject
    {
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(message, "message");
    }

    /**
     * Rejection reason codes.
     */
    public enum RejectReason
    {
        PROTOCOL_MISMATCH(0x01),
        SERVER_FULL(0x02),
        SESSION_EXPIRED(0x03),
        INVALID_TOKEN(0x04),
        AUTHENTICATION_FAILED(0x05);

        private final int code;

        RejectReason(int code)
        {
            this.code = code;
        }

        public int getCode()
        {
            return code;
        }

        public static RejectReason fromCode(int code)
        {
            for (RejectReason reason : values())
            {
                if (reason.code == code)
                {
                    return reason;
                }
            }
            throw new IllegalArgumentException("Unknown reject reason code: 0x" + Integer.toHexString(code));
        }
    }
}
