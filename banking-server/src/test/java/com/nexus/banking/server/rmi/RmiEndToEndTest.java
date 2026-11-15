package com.nexus.banking.server.rmi;

import com.nexus.banking.contract.dto.AccountDTO;
import com.nexus.banking.contract.dto.ErrorCodes;
import com.nexus.banking.contract.dto.LoginResultDTO;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.ServerStatusDTO;
import com.nexus.banking.contract.dto.TransactionDTO;
import com.nexus.banking.contract.enums.AccountStatus;
import com.nexus.banking.contract.remote.BankingAdminService;
import com.nexus.banking.contract.remote.BankingCallback;
import com.nexus.banking.contract.remote.BankingMonitoringService;
import com.nexus.banking.contract.remote.BankingReplicationService;
import com.nexus.banking.contract.remote.BankingService;
import com.nexus.banking.contract.rmi.TimeoutClientSocketFactory;
import com.nexus.banking.server.service.PeerReplicationManager;
import com.nexus.banking.server.support.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.nexus.banking.server.support.Node.m;
import static com.nexus.banking.server.support.Node.req;
import static org.assertj.core.api.Assertions.assertThat;

/** Real Java RMI on localhost: two registries, real stubs, real serialization, a real client-exported callback. */
class RmiEndToEndTest {
    static {
        com.nexus.banking.contract.rmi.RmiTimeouts.install(1500);
    }

    private static final AtomicInteger NEXT = new AtomicInteger(0);
    private int aReg, aSvc, bReg, bSvc;
    private Node a;
    private Node b;
    private RmiRegistryManager rmiA;
    private RmiRegistryManager rmiB;

    private static RmiRegistryManager publish(Node n) throws Exception {
        RmiRegistryManager r = new RmiRegistryManager(n.config);
        r.start();
        int p = n.config.getRmiServicePort();
        r.exportAndBind(RmiRegistryManager.BANKING_SERVICE_NAME, n.bank, p);
        r.exportAndBind(RmiRegistryManager.REPLICATION_SERVICE_NAME, n.repl, p);
        r.exportAndBind(RmiRegistryManager.MONITORING_SERVICE_NAME, n.monitoring, p);
        r.exportAndBind(RmiRegistryManager.ADMIN_SERVICE_NAME, n.admin, p);
        return r;
    }

    @BeforeEach
    void setUp() throws Exception {
        int base = 33000 + NEXT.getAndIncrement() * 10;
        aReg = base; aSvc = base + 1; bReg = base + 2; bSvc = base + 3;
        a = Node.ram("server-a", "server-b", Map.of("server.rmi.registry.port", "" + aReg, "server.rmi.service.port", "" + aSvc,
                "server.peer.registry.port", "" + bReg));
        b = Node.ram("server-b", "server-a", Map.of("server.rmi.registry.port", "" + bReg, "server.rmi.service.port", "" + bSvc,
                "server.peer.registry.port", "" + aReg));
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

    /** A phone/ATM exports its own callback object exactly like the customer app does. */
    private static BankingCallback export(Node.RecordingCallback impl) throws Exception {
        return (BankingCallback) UnicastRemoteObject.exportObject(impl, 0, new TimeoutClientSocketFactory(), null);
    }

    private static void waitFor(java.util.function.BooleanSupplier c) throws Exception {
        long end = System.currentTimeMillis() + 4000;
        while (!c.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(20);
    }

    @Test
    void customerWorksThroughRealStubsAndMoneyReplicatesOverRmi() throws Exception {
        BankingService bankA = (BankingService) registry(aReg).lookup(RmiRegistryManager.BANKING_SERVICE_NAME);
        BankingService bankB = (BankingService) registry(bReg).lookup(RmiRegistryManager.BANKING_SERVICE_NAME);
        assertThat(bankA.getServerId()).isEqualTo("server-a");

        LoginResultDTO login = bankA.login(Node.AN, Node.PIN, "device-atm-hanoi1", null).getData();
        String s = login.getSession().getSessionId();
        OperationResult<TransactionDTO> w = bankA.withdraw(s, Node.AN, req(), m(2_000_000), "ATM Hà Nội");
        assertThat(w.isSuccess()).isTrue();
        assertThat(w.getData().getAmount()).isEqualByComparingTo("2000000");      // BigDecimal survives serialization

        a.replication.runOnce();                                                  // A -> B over RMI
        LoginResultDTO onB = bankB.login(Node.CUONG, Node.PIN, "device-atm-hcm-01", null).getData();
        assertThat(onB.getAccount().getBalance()).isEqualByComparingTo("80000000");
        assertThat(b.balance(Node.AN)).isEqualByComparingTo("48000000");
        assertThat(a.store.outboxStats("server-b").pending()).isZero();

        BankingReplicationService replB = (BankingReplicationService) registry(bReg).lookup(RmiRegistryManager.REPLICATION_SERVICE_NAME);
        assertThat(replB.getTransactionsAfter("server-a", "guess-guess-guess-guess", 0, 10).getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
    }

    @Test
    void balancePushArrivesOverARealCallbackStub() throws Exception {
        BankingService bankA = (BankingService) registry(aReg).lookup(RmiRegistryManager.BANKING_SERVICE_NAME);
        Node.RecordingCallback phone = new Node.RecordingCallback();
        BankingCallback stub = export(phone);
        try {
            LoginResultDTO phoneLogin = bankA.login(Node.CUONG, Node.PIN, "device-phone-cuong1", stub).getData();
            assertThat(phoneLogin.isCallbackRegistered()).isTrue();
            String sa = a.login(Node.AN);
            a.bank.transfer(sa, Node.AN, Node.CUONG, req(), m(1_000_000), "qua RMI callback");
            waitFor(() -> !phone.balances.isEmpty());
            assertThat(phone.balances).hasSize(1);
            assertThat(phone.balances.get(0).getDelta()).isEqualByComparingTo("1000000");
            assertThat(phone.balances.get(0).getNewBalance()).isEqualByComparingTo("81000000");

            // single session: a second device logs in elsewhere -> this phone is told immediately
            BankingService bankB = (BankingService) registry(bReg).lookup(RmiRegistryManager.BANKING_SERVICE_NAME);
            bankB.login(Node.CUONG, Node.PIN, "device-atm-hcm-02", null);
            waitFor(() -> !phone.evictions.isEmpty());
            assertThat(phone.evictions).hasSize(1);
            OperationResult<AccountDTO> old = bankA.getAccount(phoneLogin.getSession().getSessionId(), Node.CUONG);
            assertThat(old.getErrorCode()).isEqualTo(ErrorCodes.SESSION_EVICTED);
        } finally {
            UnicastRemoteObject.unexportObject(phone, true);
        }
    }

    @Test
    void adminAndMonitoringNeedTheTokenOverRmi() throws Exception {
        BankingMonitoringService mon = (BankingMonitoringService) registry(aReg).lookup(RmiRegistryManager.MONITORING_SERVICE_NAME);
        assertThat(mon.getServerStatus("not-the-token-at-all").getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        OperationResult<ServerStatusDTO> ok = mon.getServerStatus(Node.ADMIN_TOKEN);
        assertThat(ok.isSuccess()).isTrue();
        assertThat(ok.getData().getRegistryPort()).isEqualTo(aReg);

        BankingAdminService adm = (BankingAdminService) registry(aReg).lookup(RmiRegistryManager.ADMIN_SERVICE_NAME);
        assertThat(adm.listAccounts("wrong-token-wrong-token").getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        List<AccountDTO> accounts = adm.listAccounts(Node.ADMIN_TOKEN).getData();
        assertThat(accounts).hasSize(7);
        assertThat(adm.setAccountStatus(Node.ADMIN_TOKEN, "risk-officer", Node.AN, AccountStatus.LOCKED, "nghi ngờ gian lận").isSuccess()).isTrue();
        assertThat(a.bank.login(Node.AN, Node.PIN, "device-lockedtest1", null).getErrorCode()).isEqualTo(ErrorCodes.ACCOUNT_LOCKED);
        var verify = adm.verifyAuditChain(Node.ADMIN_TOKEN).getData();
        assertThat(verify.isValid()).isTrue();
        assertThat(adm.getAuditLog(Node.ADMIN_TOKEN, 10).getData()).extracting(x -> x.getAction()).contains("LOCK_ACCOUNT");
    }
}
