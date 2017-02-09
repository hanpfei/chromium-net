package org.chromium.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

public interface HostResolver {
    /**
     * Returns the IP addresses of {@code hostname}, in the order they will be attempted by Cronet.
     * If a connection to an address fails, Cronet will retry the connection with the next address
     * until either a connection is made, the set of IP addresses is exhausted, or a limit is
     * exceeded.
     */
    List<InetAddress> resolve(String hostname) throws UnknownHostException;

}
