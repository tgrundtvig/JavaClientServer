package org.abstractica.clientserver.impl.client;

import org.abstractica.clientserver.Client;
import org.abstractica.clientserver.ClientFactory;
import org.abstractica.clientserver.Protocol;
import org.abstractica.clientserver.impl.serialization.DefaultProtocol;

import java.net.InetSocketAddress;
import java.security.PublicKey;
import java.util.Objects;

/**
 * Default implementation of ClientFactory.
 */
public class DefaultClientFactory implements ClientFactory
{
    @Override
    public Builder builder()
    {
        return new DefaultBuilder();
    }

    private static class DefaultBuilder implements Builder
    {
        private String host;
        private int port;
        private DefaultProtocol protocol;
        private PublicKey serverPublicKey;

        @Override
        public Builder serverAddress(String host, int port)
        {
            this.host = Objects.requireNonNull(host, "host");
            if (port < 1 || port > 65535)
            {
                throw new IllegalArgumentException("Port must be 1-65535: " + port);
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
        public Builder serverPublicKey(PublicKey publicKey)
        {
            this.serverPublicKey = Objects.requireNonNull(publicKey, "publicKey");
            return this;
        }

        @Override
        public Client build()
        {
            if (host == null || port == 0)
            {
                throw new IllegalStateException("Server address must be specified");
            }
            if (protocol == null)
            {
                throw new IllegalStateException("Protocol must be specified");
            }
            if (serverPublicKey == null)
            {
                throw new IllegalStateException("Server public key must be specified");
            }

            InetSocketAddress address = new InetSocketAddress(host, port);
            return new DefaultClient(address, protocol, serverPublicKey);
        }
    }
}
