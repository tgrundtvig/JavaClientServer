package org.abstractica.clientserver.impl.session;

import org.abstractica.clientserver.Protocol;
import org.abstractica.clientserver.Server;
import org.abstractica.clientserver.ServerFactory;
import org.abstractica.clientserver.impl.serialization.DefaultProtocol;
import org.abstractica.clientserver.impl.transport.Transport;
import org.abstractica.clientserver.impl.transport.UdpTransport;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.Objects;

/**
 * Default implementation of ServerFactory.
 *
 * <p>Creates DefaultServer instances using a builder pattern.</p>
 */
public class DefaultServerFactory implements ServerFactory
{
    @Override
    public Builder builder()
    {
        return new DefaultBuilder();
    }

    public static class DefaultBuilder implements Builder
    {
        private int port;
        private DefaultProtocol protocol;
        private PrivateKey privateKey;
        private InetAddress bindAddress;
        private int maxConnections = 0; // 0 = unlimited
        private Duration sessionTimeout = Duration.ofMinutes(2);
        private Duration heartbeatInterval = Duration.ofSeconds(5);
        private int maxReliableQueueSize = 256;
        private int maxMessageSize = 65536;
        private Transport customTransport; // Optional custom transport for testing

        @Override
        public Builder port(int port)
        {
            if (port < 0 || port > 65535)
            {
                throw new IllegalArgumentException("Port must be 0-65535: " + port);
            }
            this.port = port;
            return this;
        }

        @Override
        public Builder protocol(Protocol protocol)
        {
            Objects.requireNonNull(protocol, "protocol");
            if (!(protocol instanceof DefaultProtocol))
            {
                throw new IllegalArgumentException(
                        "Protocol must be a DefaultProtocol instance");
            }
            this.protocol = (DefaultProtocol) protocol;
            return this;
        }

        @Override
        public Builder privateKey(PrivateKey privateKey)
        {
            this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
            return this;
        }

        @Override
        public Builder bindAddress(InetAddress address)
        {
            this.bindAddress = address;
            return this;
        }

        @Override
        public Builder maxConnections(int maxConnections)
        {
            if (maxConnections < 0)
            {
                throw new IllegalArgumentException("maxConnections must be >= 0: " + maxConnections);
            }
            this.maxConnections = maxConnections;
            return this;
        }

        @Override
        public Builder sessionTimeout(Duration timeout)
        {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero())
            {
                throw new IllegalArgumentException("Session timeout must be positive");
            }
            this.sessionTimeout = timeout;
            return this;
        }

        @Override
        public Builder heartbeatInterval(Duration interval)
        {
            Objects.requireNonNull(interval, "interval");
            if (interval.isNegative() || interval.isZero())
            {
                throw new IllegalArgumentException("Heartbeat interval must be positive");
            }
            this.heartbeatInterval = interval;
            return this;
        }

        @Override
        public Builder maxReliableQueueSize(int size)
        {
            if (size <= 0)
            {
                throw new IllegalArgumentException("maxReliableQueueSize must be positive: " + size);
            }
            this.maxReliableQueueSize = size;
            return this;
        }

        @Override
        public Builder maxMessageSize(int size)
        {
            if (size <= 0)
            {
                throw new IllegalArgumentException("maxMessageSize must be positive: " + size);
            }
            this.maxMessageSize = size;
            return this;
        }

        /**
         * Sets a custom transport for testing purposes.
         *
         * <p>If not set, a UdpTransport will be created automatically.</p>
         *
         * @param transport the transport to use
         * @return this builder
         */
        public DefaultBuilder transport(Transport transport)
        {
            this.customTransport = transport;
            return this;
        }

        @Override
        public Server build()
        {
            // Validate required parameters
            if (port == 0)
            {
                throw new IllegalStateException("Port must be specified");
            }
            if (protocol == null)
            {
                throw new IllegalStateException("Protocol must be specified");
            }
            if (privateKey == null)
            {
                throw new IllegalStateException("Private key must be specified");
            }

            // Create bind address
            InetSocketAddress socketAddress;
            if (bindAddress != null)
            {
                socketAddress = new InetSocketAddress(bindAddress, port);
            }
            else
            {
                socketAddress = new InetSocketAddress(port);
            }

            // Create transport (use custom if provided, otherwise create UDP)
            Transport transport;
            if (customTransport != null)
            {
                transport = customTransport;
            }
            else
            {
                transport = new UdpTransport(socketAddress);
            }

            // Create server
            return new DefaultServer(
                    transport,
                    protocol,
                    privateKey,
                    heartbeatInterval,
                    sessionTimeout,
                    maxConnections
            );
        }
    }
}
