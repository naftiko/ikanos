package io.ikanos.utils;

import java.io.IOException;
import java.net.ServerSocket;
import tools.jackson.databind.ObjectMapper;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;

public class TestUtils {

    public static IkanosSpec parseYaml(String yaml) throws Exception {
        ObjectMapper mapper = IkanosMappers.lenientYaml();
        return mapper.readValue(yaml, IkanosSpec.class);
    }

    /**
     * Returns {@code count} distinct free ports. Each socket stays open until every port is read:
     * two separate "open port 0, read it, close it" calls can return the same port, and two
     * adapters on one port then refuse to load.
     */
    public static int[] freePorts(int count) throws IOException {
        ServerSocket[] sockets = new ServerSocket[count];
        try {
            int[] ports = new int[count];
            for (int i = 0; i < count; i++) {
                sockets[i] = new ServerSocket(0);
                ports[i] = sockets[i].getLocalPort();
            }
            return ports;
        } finally {
            for (ServerSocket socket : sockets) {
                if (socket != null) {
                    socket.close();
                }
            }
        }
    }
}
