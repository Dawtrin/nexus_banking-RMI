package com.nexus.banking.server.rmi;

import com.nexus.banking.contract.dto.ChatMessageDTO;
import com.nexus.banking.contract.dto.ClientSessionDTO;
import com.nexus.banking.contract.enums.MessageType;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.remote.ChatRemote;
import com.nexus.banking.contract.rmi.RmiSocketFactories;
import com.nexus.banking.contract.rmi.RmiTimeouts;
import com.nexus.banking.server.config.ServerConfig;
import com.nexus.banking.server.monitoring.ServerMetrics;
import com.nexus.banking.server.monitoring.SystemLogService;
import com.nexus.banking.server.service.ChatServiceImpl;
import com.nexus.banking.server.service.ClientSessionManager;
import com.nexus.banking.server.store.InMemoryChatStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RmiSslTest {
    static {
        RmiTimeouts.install(2000);
    }

    private static final int REGISTRY_PORT = 32000;
    private static final int SERVICE_PORT = 32001;

    private static RmiRegistryManager rmi;
    private static ChatServiceImpl chat;

    @BeforeAll
    static void setUp(@TempDir Path tempDir) throws Exception {
        File keystore = tempDir.resolve("server.p12").toFile();
        Process p = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "test-ssl",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-storetype", "PKCS12",
                "-keystore", keystore.getAbsolutePath(),
                "-storepass", "changeit",
                "-keypass", "changeit",
                "-dname", "CN=localhost, O=Test",
                "-validity", "1",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1"
        ).redirectErrorStream(true).start();
        int rc = p.waitFor();
        if (rc != 0) {
            throw new IllegalStateException("keytool failed to generate test keystore");
        }

        System.setProperty("nexus.ssl.trustSelfSigned", "true");

        ServerConfig config = new ServerConfig(
                Map.of(
                        "server.id", "server-ssl",
                        "server.peer.id", "peer-ssl",
                        "server.rmi.registry.port", String.valueOf(REGISTRY_PORT),
                        "server.rmi.service.port", String.valueOf(SERVICE_PORT),
                        "server.replication.secret", "test-secret-at-least-16-chars",
                        "database.enabled", "false",
                        "server.ssl.enabled", "true",
                        "server.ssl.keystore.path", keystore.getAbsolutePath(),
                        "server.ssl.keystore.password", "changeit"
                ),
                Map.of(), Map.of(), null
        );

        ServerMetrics metrics = new ServerMetrics();
        SystemLogService log = new SystemLogService("server-ssl");
        InMemoryChatStore store = new InMemoryChatStore();
        ClientSessionManager sessions = new ClientSessionManager("server-ssl", log, false);
        com.nexus.banking.server.service.PeerReplicationManager replication =
                new com.nexus.banking.server.service.PeerReplicationManager(config, store, metrics, log, () -> null, false);
        chat = new ChatServiceImpl(config, store, sessions, replication, metrics, log);

        rmi = new RmiRegistryManager(config);
        rmi.start();
        rmi.exportAndBind(RmiRegistryManager.CHAT_SERVICE_NAME, chat, SERVICE_PORT);
    }

    @AfterAll
    static void tearDown() {
        if (rmi != null) {
            rmi.close();
        }
        System.clearProperty("nexus.ssl.trustSelfSigned");
    }

    @Test
    void clientCanConnectAndSendOverTls() throws Exception {
        Registry registry = LocateRegistry.getRegistry("127.0.0.1", REGISTRY_PORT, RmiSocketFactories.clientFactory(true));
        ChatRemote remote = (ChatRemote) registry.lookup(RmiRegistryManager.CHAT_SERVICE_NAME);

        OperationResult<ClientSessionDTO> reg = remote.registerClient("AliceSSL", UUID.randomUUID().toString());
        assertThat(reg.isSuccess()).isTrue();
        String sessId = reg.getData().getSessionId();

        OperationResult<Void> joined = remote.joinRoom(sessId, "general");
        assertThat(joined.isSuccess()).isTrue();

        OperationResult<ChatMessageDTO> sent = remote.sendMessage(
                sessId, "general", "msg-" + UUID.randomUUID(), "Hello encrypted RMI world!"
        );
        assertThat(sent.isSuccess()).isTrue();
        assertThat(sent.getData().getContent()).isEqualTo("Hello encrypted RMI world!");
        assertThat(sent.getData().getSenderName()).isEqualTo("AliceSSL");
    }
}
