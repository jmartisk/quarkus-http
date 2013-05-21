package io.undertow.server.handlers;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import io.undertow.UndertowMessages;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.xnio.Bits;

/**
 * Handler that limits the peers that are allowed to connect.
 * <p/>
 * Note: a lot of methods on this class are package private for unit tests
 *
 * @author Stuart Douglas
 */
public class RemoteAccessControlHandler implements HttpHandler {

    /**
     * Standard IP address
     */
    private static final Pattern IP4_EXACT = Pattern.compile("(?:\\d{1,3}\\.){3}\\d{1,3}");

    /**
     * Standard IP address, with some octets replaced by a '*'
     */
    private static final Pattern IP4_WILDCARD = Pattern.compile("(?:(?:\\d{1,3}|\\*)\\.){3}(?:\\d{1,3}|\\*)");

    /**
     * IPv4 address with subnet specified via slash notation
     */
    private static final Pattern IP4_SLASH = Pattern.compile("(?:\\d{1,3}\\.){3}\\d{1,3}\\/\\d\\d?");

    /**
     * Standard full IPv6 address
     */
    private static final Pattern IP6_EXACT = Pattern.compile("(?:[a-zA-Z0-9]{1,4}:){7}[a-zA-Z0-9]{1,4}");

    /**
     * Standard full IPv6 address, with some parts replaced by a '*'
     */
    private static final Pattern IP6_WILDCARD = Pattern.compile("(?:(?:[a-zA-Z0-9]{1,4}|\\*):){7}(?:[a-zA-Z0-9]{1,4}|\\*)");

    /**
     * Standard full IPv6 address with subnet specified via slash notation
     */
    private static final Pattern IP6_SLASH = Pattern.compile("(?:[a-zA-Z0-9]{1,4}:){7}[a-zA-Z0-9]{1,4}\\/\\d{1,3}");

    private volatile HttpHandler next;
    private volatile boolean defaultAllow = false;
    private final List<PeerMatch> acl = new CopyOnWriteArrayList<>();

    public RemoteAccessControlHandler(final HttpHandler next) {
        this.next = next;
    }

    public RemoteAccessControlHandler() {
        this.next = ResponseCodeHandler.HANDLE_404;
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange) throws Exception {
        InetSocketAddress peer = exchange.getSourceAddress();
        if (isAllowed(peer.getAddress())) {
            next.handleRequest(exchange);
        } else {
            exchange.setResponseCode(StatusCodes.FORBIDDEN);
            exchange.endExchange();
        }
    }

    boolean isAllowed(InetAddress address) {
        for (PeerMatch rule : acl) {
            if (rule.matches(address)) {
                return !rule.isDeny();
            }
        }
        return defaultAllow;
    }

    public boolean isDefaultAllow() {
        return defaultAllow;
    }

    public RemoteAccessControlHandler setDefaultAllow(final boolean defaultAllow) {
        this.defaultAllow = defaultAllow;
        return this;
    }

    public HttpHandler getNext() {
        return next;
    }

    public RemoteAccessControlHandler setNext(final HttpHandler next) {
        this.next = next;
        return this;
    }


    /**
     * Adds an allowed peer to the ACL list
     * <p/>
     * Peer can take several forms:
     * <p/>
     * a.b.c.d = Literal IPv4 Address
     * a:b:c:d:e:f:g:h = Literal IPv6 Address
     * a.b.* = Wildcard IPv4 Address
     * a:b:* = Wildcard IPv6 Address
     * a.b.c.0/24 = Classless wildcard IPv4 address
     * a:b:c:d:e:f:g:0/120 = Classless wildcard IPv4 address
     *
     * @param peer The peer to add to the ACL
     */
    public RemoteAccessControlHandler addAllow(final String peer) {
        return addRule(peer, false);
    }

    /**
     * Adds an denied peer to the ACL list
     * <p/>
     * Peer can take several forms:
     * <p/>
     * a.b.c.d = Literal IPv4 Address
     * a:b:c:d:e:f:g:h = Literal IPv6 Address
     * a.b.* = Wildcard IPv4 Address
     * a:b:* = Wildcard IPv6 Address
     * a.b.c.0/24 = Classless wildcard IPv4 address
     * a:b:c:d:e:f:g:0/120 = Classless wildcard IPv4 address
     *
     * @param peer The peer to add to the ACL
     */
    public RemoteAccessControlHandler addDeny(final String peer) {
        return addRule(peer, true);
    }

    public RemoteAccessControlHandler clearRules() {
        this.acl.clear();
        return this;
    }

    private RemoteAccessControlHandler addRule(final String peer, final boolean deny) {
        if (IP4_EXACT.matcher(peer).matches()) {
            addIpV4ExactMatch(peer, deny);
        } else if (IP4_WILDCARD.matcher(peer).matches()) {
            addIpV4WildcardMatch(peer, deny);
        } else if (IP4_SLASH.matcher(peer).matches()) {
            addIpV4SlashPrefix(peer, deny);
        } else if (IP6_EXACT.matcher(peer).matches()) {
            addIpV6ExactMatch(peer, deny);
        } else if (IP6_WILDCARD.matcher(peer).matches()) {
            addIpV6WildcardMatch(peer, deny);
        } else if (IP6_SLASH.matcher(peer).matches()) {
            addIpV6SlashPrefix(peer, deny);
        } else {
            throw UndertowMessages.MESSAGES.notAValidIpPattern(peer);
        }
        return this;
    }

    private void addIpV6SlashPrefix(final String peer, final boolean deny) {
        String[] components = peer.split("\\/");
        String[] parts = components[0].split("\\:");
        int maskLen = Integer.parseInt(components[1]);
        assert parts.length == 8;


        byte[] pattern = new byte[16];
        byte[] mask = new byte[16];

        for (int i = 0; i < 8; ++i) {
            int val = Integer.parseInt(parts[i], 16);
            pattern[i * 2] = (byte) (val >> 8);
            pattern[i * 2 + 1] = (byte) (val & 0xFF);
        }
        for (int i = 0; i < 16; ++i) {
            if (maskLen > 8) {
                mask[i] = (byte) (0xFF);
                maskLen -= 8;
            } else if (maskLen != 0) {
                mask[i] = (byte) (Bits.intBitMask(8 - maskLen, 7) & 0xFF);
                maskLen = 0;
            } else {
                break;
            }
        }
        acl.add(new PrefixIpV6PeerMatch(deny, peer, mask, pattern));
    }

    private void addIpV4SlashPrefix(final String peer, final boolean deny) {
        String[] components = peer.split("\\/");
        String[] parts = components[0].split("\\.");
        int maskLen = Integer.parseInt(components[1]);
        final int mask = Bits.intBitMask(32 - maskLen, 31);
        int prefix = 0;
        for (int i = 0; i < 4; ++i) {
            prefix <<= 8;
            String part = parts[i];
            int no = Integer.parseInt(part);
            prefix |= no;
        }
        acl.add(new PrefixIpV4PeerMatch(deny, peer, mask, prefix));
    }

    private void addIpV6WildcardMatch(final String peer, final boolean deny) {
        byte[] pattern = new byte[16];
        byte[] mask = new byte[16];
        String[] parts = peer.split("\\:");
        assert parts.length == 8;
        for (int i = 0; i < 8; ++i) {
            if (!parts[i].equals("*")) {
                int val = Integer.parseInt(parts[i], 16);
                pattern[i * 2] = (byte) (val >> 8);
                pattern[i * 2 + 1] = (byte) (val & 0xFF);
                mask[i * 2] = (byte) (0xFF);
                mask[i * 2 + 1] = (byte) (0xFF);
            }
        }
        acl.add(new PrefixIpV6PeerMatch(deny, peer, mask, pattern));
    }

    private void addIpV4WildcardMatch(final String peer, final boolean deny) {
        String[] parts = peer.split("\\.");
        int mask = 0;
        int prefix = 0;
        for (int i = 0; i < 4; ++i) {
            mask <<= 8;
            prefix <<= 8;
            String part = parts[i];
            if (!part.equals("*")) {
                int no = Integer.parseInt(part);
                mask |= 0xFF;
                prefix |= no;
            }
        }
        acl.add(new PrefixIpV4PeerMatch(deny, peer, mask, prefix));
    }

    private void addIpV6ExactMatch(final String peer, final boolean deny) {
        byte[] bytes = new byte[16];
        String[] parts = peer.split("\\:");
        assert parts.length == 8;
        for (int i = 0; i < 8; ++i) {
            int val = Integer.parseInt(parts[i], 16);
            bytes[i * 2] = (byte) (val >> 8);
            bytes[i * 2 + 1] = (byte) (val & 0xFF);
        }
        acl.add(new ExactIpV6PeerMatch(deny, peer, bytes));
    }

    private void addIpV4ExactMatch(final String peer, final boolean deny) {
        String[] parts = peer.split("\\.");
        byte[] bytes = {(byte) Integer.parseInt(parts[0]), (byte) Integer.parseInt(parts[1]), (byte) Integer.parseInt(parts[2]), (byte) Integer.parseInt(parts[3])};
        acl.add(new ExactIpV4PeerMatch(deny, peer, bytes));
    }


    abstract static class PeerMatch {

        private final boolean deny;
        private final String pattern;

        protected PeerMatch(final boolean deny, final String pattern) {
            this.deny = deny;
            this.pattern = pattern;
        }

        abstract boolean matches(final InetAddress address);

        boolean isDeny() {
            return deny;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "{" +
                    "deny=" + deny +
                    ", pattern='" + pattern + '\'' +
                    '}';
        }
    }

    static class ExactIpV4PeerMatch extends PeerMatch {

        private final byte[] address;

        protected ExactIpV4PeerMatch(final boolean deny, final String pattern, final byte[] address) {
            super(deny, pattern);
            this.address = address;
        }

        @Override
        boolean matches(final InetAddress address) {
            return Arrays.equals(address.getAddress(), this.address);
        }
    }

    static class ExactIpV6PeerMatch extends PeerMatch {

        private final byte[] address;

        protected ExactIpV6PeerMatch(final boolean deny, final String pattern, final byte[] address) {
            super(deny, pattern);
            this.address = address;
        }

        @Override
        boolean matches(final InetAddress address) {
            return Arrays.equals(address.getAddress(), this.address);
        }
    }

    private static class PrefixIpV4PeerMatch extends PeerMatch {

        private final int mask;
        private final int prefix;

        protected PrefixIpV4PeerMatch(final boolean deny, final String pattern, final int mask, final int prefix) {
            super(deny, pattern);
            this.mask = mask;
            this.prefix = prefix;
        }

        @Override
        boolean matches(final InetAddress address) {
            byte[] bytes = address.getAddress();
            if (bytes == null) {
                return false;
            }
            int addressInt = ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16) | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
            return (addressInt & mask) == prefix;
        }
    }

    static class PrefixIpV6PeerMatch extends PeerMatch {

        private final byte[] mask;
        private final byte[] prefix;

        protected PrefixIpV6PeerMatch(final boolean deny, final String pattern, final byte[] mask, final byte[] prefix) {
            super(deny, pattern);
            this.mask = mask;
            this.prefix = prefix;
            assert mask.length == prefix.length;
        }

        @Override
        boolean matches(final InetAddress address) {
            byte[] bytes = address.getAddress();
            if (bytes == null) {
                return false;
            }
            if (bytes.length != mask.length) {
                return false;
            }
            for (int i = 0; i < mask.length; ++i) {
                if ((bytes[i] & mask[i]) != prefix[i]) {
                    return false;
                }
            }
            return true;
        }
    }

}
