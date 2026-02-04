package org.abstractica.clientserver.impl.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages session lifecycle and lookup.
 *
 * <p>Thread-safe for concurrent access from receive thread and tick thread.</p>
 */
public class SessionManager
{
    private static final Logger LOG = LoggerFactory.getLogger(SessionManager.class);
    private static final int SESSION_TOKEN_LENGTH = 16;
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(30);

    private final Map<ByteArrayKey, DefaultSession> sessionsByToken;
    private final Map<SocketAddress, DefaultSession> sessionsByAddress;
    private final Map<SocketAddress, PendingHandshake> pendingHandshakes;
    private final SecureRandom random;
    private final Duration sessionTimeout;
    private final int maxConnections;

    /**
     * Creates a new session manager.
     *
     * @param sessionTimeout timeout for disconnected sessions
     * @param maxConnections maximum concurrent connections (0 for unlimited)
     */
    public SessionManager(Duration sessionTimeout, int maxConnections)
    {
        Objects.requireNonNull(sessionTimeout, "sessionTimeout");

        this.sessionsByToken = new ConcurrentHashMap<>();
        this.sessionsByAddress = new ConcurrentHashMap<>();
        this.pendingHandshakes = new ConcurrentHashMap<>();
        this.random = new SecureRandom();
        this.sessionTimeout = sessionTimeout;
        this.maxConnections = maxConnections;
    }

    // ========== Session Lookup ==========

    /**
     * Finds a session by its token.
     *
     * @param token the session token (16 bytes)
     * @return the session, or null if not found
     */
    public DefaultSession findByToken(byte[] token)
    {
        Objects.requireNonNull(token, "token");
        return sessionsByToken.get(new ByteArrayKey(token));
    }

    /**
     * Finds a session by remote address.
     *
     * @param address the remote address
     * @return the session, or null if not found
     */
    public DefaultSession findByAddress(SocketAddress address)
    {
        Objects.requireNonNull(address, "address");
        return sessionsByAddress.get(address);
    }

    /**
     * Returns all active sessions.
     *
     * @return unmodifiable collection of sessions
     */
    public Collection<DefaultSession> getAllSessions()
    {
        return Collections.unmodifiableCollection(sessionsByToken.values());
    }

    /**
     * Returns the number of connected sessions.
     *
     * @return connected session count
     */
    public int getConnectedCount()
    {
        return (int) sessionsByToken.values().stream()
                .filter(s -> s.getState() == SessionState.CONNECTED)
                .count();
    }

    /**
     * Returns the total number of sessions (connected + disconnected).
     *
     * @return total session count
     */
    public int getTotalCount()
    {
        return sessionsByToken.size();
    }

    // ========== Session Registration ==========

    /**
     * Generates a new unique session token.
     *
     * @return 16-byte session token
     */
    public byte[] generateSessionToken()
    {
        byte[] token = new byte[SESSION_TOKEN_LENGTH];
        random.nextBytes(token);
        return token;
    }

    /**
     * Checks if the server can accept more connections.
     *
     * @return true if below max connections limit
     */
    public boolean canAcceptConnection()
    {
        return maxConnections <= 0 || getConnectedCount() < maxConnections;
    }

    /**
     * Registers a new session.
     *
     * @param session the session to register
     */
    public void registerSession(DefaultSession session)
    {
        Objects.requireNonNull(session, "session");

        ByteArrayKey key = new ByteArrayKey(session.getSessionToken());
        sessionsByToken.put(key, session);
        sessionsByAddress.put(session.getRemoteAddress(), session);

        LOG.info("Session registered: id={}, address={}",
                session.getId(), session.getRemoteAddress());
    }

    /**
     * Removes a session.
     *
     * @param session the session to remove
     */
    public void removeSession(DefaultSession session)
    {
        Objects.requireNonNull(session, "session");

        ByteArrayKey key = new ByteArrayKey(session.getSessionToken());
        sessionsByToken.remove(key);
        sessionsByAddress.remove(session.getRemoteAddress());

        session.stop();

        LOG.info("Session removed: id={}", session.getId());
    }

    /**
     * Updates the address mapping for a session (for reconnection).
     *
     * @param session    the session
     * @param oldAddress the old address
     * @param newAddress the new address
     */
    public void updateSessionAddress(DefaultSession session, SocketAddress oldAddress, SocketAddress newAddress)
    {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(newAddress, "newAddress");

        if (oldAddress != null)
        {
            sessionsByAddress.remove(oldAddress);
        }
        sessionsByAddress.put(newAddress, session);
        session.setRemoteAddress(newAddress);

        LOG.debug("Session {} address updated: {} -> {}",
                session.getId(), oldAddress, newAddress);
    }

    // ========== Pending Handshakes ==========

    /**
     * Finds a pending handshake by address.
     *
     * @param address the remote address
     * @return the pending handshake, or null if not found
     */
    public PendingHandshake findPendingHandshake(SocketAddress address)
    {
        Objects.requireNonNull(address, "address");
        return pendingHandshakes.get(address);
    }

    /**
     * Registers a pending handshake.
     *
     * @param handshake the pending handshake
     */
    public void registerPendingHandshake(PendingHandshake handshake)
    {
        Objects.requireNonNull(handshake, "handshake");
        pendingHandshakes.put(handshake.remoteAddress(), handshake);
        LOG.debug("Pending handshake registered: {}", handshake.remoteAddress());
    }

    /**
     * Removes a pending handshake.
     *
     * @param address the remote address
     * @return the removed handshake, or null if not found
     */
    public PendingHandshake removePendingHandshake(SocketAddress address)
    {
        Objects.requireNonNull(address, "address");
        PendingHandshake removed = pendingHandshakes.remove(address);
        if (removed != null)
        {
            LOG.debug("Pending handshake removed: {}", address);
        }
        return removed;
    }

    // ========== Maintenance ==========

    /**
     * Checks for and removes expired sessions.
     *
     * @param nowMs current time in milliseconds
     * @return list of expired sessions
     */
    public List<DefaultSession> checkExpiredSessions(long nowMs)
    {
        List<DefaultSession> expired = new ArrayList<>();

        for (DefaultSession session : sessionsByToken.values())
        {
            if (session.getState() == SessionState.DISCONNECTED)
            {
                long disconnectedTime = nowMs - session.getLastActivityMs();
                if (disconnectedTime > sessionTimeout.toMillis())
                {
                    expired.add(session);
                }
            }
        }

        for (DefaultSession session : expired)
        {
            removeSession(session);
        }

        return expired;
    }

    /**
     * Checks for and removes timed-out pending handshakes.
     *
     * @param nowMs current time in milliseconds
     * @return number of handshakes removed
     */
    public int checkHandshakeTimeouts(long nowMs)
    {
        List<SocketAddress> timedOut = new ArrayList<>();

        for (PendingHandshake handshake : pendingHandshakes.values())
        {
            if (nowMs - handshake.createdAtMs() > HANDSHAKE_TIMEOUT.toMillis())
            {
                timedOut.add(handshake.remoteAddress());
            }
        }

        for (SocketAddress address : timedOut)
        {
            pendingHandshakes.remove(address);
            LOG.debug("Handshake timed out: {}", address);
        }

        return timedOut.size();
    }

    /**
     * Returns the session timeout duration.
     */
    public Duration getSessionTimeout()
    {
        return sessionTimeout;
    }
}
