package com.nexus.banking.server.rmi;

import com.nexus.banking.contract.dto.ChatMessageDTO;
import com.nexus.banking.contract.dto.ClientSessionDTO;
import com.nexus.banking.contract.dto.ErrorCodes;
import com.nexus.banking.contract.dto.MessagePage;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.ServerStatusDTO;
import com.nexus.banking.contract.remote.ChatRemote;
import com.nexus.banking.contract.remote.MonitoringRemote;
import com.nexus.banking.contract.remote.ReplicationRemote;
import com.nexus.banking.contract.rmi.TimeoutClientSocketFactory;
import com.nexus.banking.server.service.PeerReplicationManager;
import com.nexus.banking.server.support.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real Java RMI on localhost: two registries/service ports, real stubs, real serialization. */
class RmiEndToEndTest {
    static {
        com.nexus.banking.contract.rmi.RmiTimeouts.install(1500);
    }

    /** Test-only remote whose method outlasts the read timeout. */
    public interface Slow extends java.rmi.Remote {
        String slow() throws java.rmi.RemoteException;
    }

    private static final java.util.concurrent.atomic.AtomicInteger NEXT = new java.util.concurrent.atomic.AtomicInteger(0);
    private int A_REG, A_SVC, B_REG, B_SVC;   // distinct ports per test: closing a registry releases its port asynchronously
    private Node a;
    private Node b;
    private RmiRegistryManager rmiA;
    private RmiRegistryManager rmiB;

    private static RmiRegistryManager publish(Node n) throws Exception {
        RmiRegistryManager m = new RmiRegistryManager(n.config);
        m.start();
        m.exportAndBind(RmiRegistryManager.CHAT_SERVICE_NAME, n.chat, n.config.getRmiServicePort());
        m.exportAndBind(RmiRegistryManager.REPLICATION_SERVICE_NAME, n.repl, n.config.getRmiServicePort());
        m.exportAndBind(RmiRegistryManager.MONITORING_SERVICE_NAME, n.monitoring, n.config.getRmiServicePort());
        return m;
    }

    @BeforeEach
    void setUp() throws Exception {
        int base = 31000 + NEXT.getAndIncrement() * 10;
        A_REG = base;
        A_SVC = base + 1;
        B_REG = base + 2;
        B_SVC = base + 3;
        a = Node.ram("server-a", "server-b", Map.of("server.rmi.registry.port", "" + A_REG, "server.rmi.service.port", "" + A_SVC,
                "server.peer.registry.port", "" + B_REG));
        b = Node.ram("server-b", "server-a", Map.of("server.rmi.registry.port", "" + B_REG, "server.rmi.service.port", "" + B_SVC,
                "server.peer.registry.port", "" + A_REG));
        rmiA = publish(a);
        rmiB = publish(b);
        a.link.set(PeerReplicationManager.rmiConnector(a.config).connect());
        b.link.set(PeerReplicationManager.rmiConnector(b.config).connect());
    }

    @AfterEach
    void tearDown() {
        rmiA.close();
        rmiB.close();
        a.close();
        b.close();
    }

    private static Registry registry(int port) throws Exception {
        return LocateRegistry.getRegistry("127.0.0.1", port, new TimeoutClientSocketFactory());
    }

    @Test
    void clientsCanChatThroughRealStubsAndMessagesReplicateOverRmi() throws Exception {
        ChatRemote chatA = (ChatRemote) registry(A_REG).lookup(RmiRegistryManager.CHAT_SERVICE_NAME);
        ChatRemote chatB = (ChatRemote) registry(B_REG).lookup(RmiRegistryManager.CHAT_SERVICE_NAME);
        assertThat(chatA.getServerId()).isEqualTo("server-a");

        ClientSessionDTO sa = chatA.registerClient("alice", "instance-alice-0001").getData();
        ClientSessionDTO sb = chatB.registerClient("bob", "instance-bob-00001").getData();
        assertThat(chatA.joinRoom(sa.getSessionId(), "general").isSuccess()).isTrue();
        assertThat(chatB.joinRoom(sb.getSessionId(), "general").isSuccess()).isTrue();
        OperationResult<ChatMessageDTO> sent = chatA.sendMessage(sa.getSessionId(), "general", UUID.randomUUID().toString(), "hello over rmi");
        assertThat(sent.isSuccess()).isTrue();

        a.replication.runOnce();                               // A pushes to B over RMI
        MessagePage onB = chatB.getHistory(sb.getSessionId(), "general", 50).getData();
        assertThat(onB.getMessages()).extracting(ChatMessageDTO::getContent).containsExactly("hello over rmi");
        assertThat(onB.getMessages().get(0).getOriginServerId()).isEqualTo("server-a");
        assertThat(a.replication.getStatus().getPendingCount()).isZero();

        // an unauthenticated client cannot reach protected data through the same registry
        ReplicationRemote replB = (ReplicationRemote) registry(B_REG).lookup(RmiRegistryManager.REPLICATION_SERVICE_NAME);
        assertThat(replB.getMessagesAfter("server-a", "guess-guess-guess-guess", 0, 10).getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(replB.replicateBatch("server-a", "guess-guess-guess-guess", java.util.List.of()).getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
    }

    @Test
    void adminApiOverRmiNeedsTheToken() throws Exception {
        MonitoringRemote mon = (MonitoringRemote) registry(A_REG).lookup(RmiRegistryManager.MONITORING_SERVICE_NAME);
        assertThat(mon.getServerStatus("not-the-token-at-all").getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        OperationResult<ServerStatusDTO> ok = mon.getServerStatus(Node.ADMIN_TOKEN);
        assertThat(ok.isSuccess()).isTrue();
        assertThat(ok.getData().getRegistryPort()).isEqualTo(A_REG);
        assertThat(ok.getData().getServicePort()).isEqualTo(A_SVC);
        assertThat(mon.getReplicationStatus(Node.ADMIN_TOKEN).getData().getPeerServerId()).isEqualTo("server-b");
    }

    @Test
    void callsToAnUnresponsivePeerFailWithinTheConfiguredTimeout() throws Exception {
        try (ServerSocket blackHole = new ServerSocket(0)) {      // accepts TCP, never speaks RMI
            int port = blackHole.getLocalPort();
            Registry stuck = LocateRegistry.getRegistry("127.0.0.1", port, new TimeoutClientSocketFactory(500, 800));
            long t0 = System.nanoTime();
            assertThatThrownBy(stuck::list).isInstanceOf(java.rmi.RemoteException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test
    void aRemoteCallThatOutlastsTheReadTimeoutFailsInsteadOfHanging() throws Exception {
        Slow impl = () -> {
            try {
                Thread.sleep(6000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return "late";
        };
        Slow stub = (Slow) java.rmi.server.UnicastRemoteObject.exportObject(impl, 0, new TimeoutClientSocketFactory(500, 800), null);
        try {
            long t0 = System.nanoTime();
            assertThatThrownBy(stub::slow).isInstanceOf(java.rmi.RemoteException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(4));
        } finally {
            java.rmi.server.UnicastRemoteObject.unexportObject(impl, true);
        }
    }
}
