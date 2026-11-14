package com.nexus.banking.server.e2e;

import com.nexus.banking.contract.dto.ChatMessageDTO;
import com.nexus.banking.contract.dto.ClientSessionDTO;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.ReplicationStatusDTO;
import com.nexus.banking.contract.remote.ChatRemote;
import com.nexus.banking.contract.remote.MonitoringRemote;
import com.nexus.banking.contract.rmi.RmiTimeouts;
import com.nexus.banking.contract.rmi.TimeoutClientSocketFactory;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.UUID;

/**
 * Command-line RMI client used by scripts/e2e-local.sh against REAL server processes.
 * Usage:
 *   send    host port user count prefix   - register, join "general", send count messages
 *   history host port user                - print the room history (one message per line)
 *   status  host port adminToken          - print replication status
 */
public final class E2eClient {
    private E2eClient() {
    }

    private static Registry registry(String host, int port) throws Exception {
        return LocateRegistry.getRegistry(host, port, new TimeoutClientSocketFactory());
    }

    public static void main(String[] a) throws Exception {
        RmiTimeouts.install();
        String cmd = a[0];
        String host = a[1];
        int port = Integer.parseInt(a[2]);
        switch (cmd) {
            case "send", "history" -> {
                ChatRemote chat = (ChatRemote) registry(host, port).lookup("NexusChatService");
                OperationResult<ClientSessionDTO> reg = chat.registerClient(a[3], "e2e-" + a[3] + "-instance");
                check(reg);
                String sid = reg.getData().getSessionId();
                check(chat.joinRoom(sid, "general"));
                if (cmd.equals("send")) {
                    int n = Integer.parseInt(a[4]);
                    for (int i = 1; i <= n; i++) {
                        OperationResult<ChatMessageDTO> r = chat.sendMessage(sid, "general", UUID.randomUUID().toString(), a[5] + " " + i);
                        check(r);
                    }
                    System.out.println("SENT " + n + " via " + chat.getServerId());
                } else {
                    var page = chat.getHistory(sid, "general", 200);
                    check(page);
                    for (ChatMessageDTO m : page.getData().getMessages()) {
                        System.out.println("MSG " + m.getOriginServerId() + "#" + m.getOriginSequence() + " " + m.getContent());
                    }
                    System.out.println("TOTAL " + page.getData().getMessages().size());
                }
            }
            case "status" -> {
                MonitoringRemote mon = (MonitoringRemote) registry(host, port).lookup("NexusMonitoringService");
                OperationResult<ReplicationStatusDTO> r = mon.getReplicationStatus(a[3]);
                check(r);
                ReplicationStatusDTO s = r.getData();
                System.out.println("STATUS peer=" + s.getPeerStatus() + " sync=" + s.getSyncStatus() + " pending=" + s.getPendingCount()
                        + " dead=" + s.getDeadLetterCount() + " checkpoint=" + s.getInboundCheckpoint());
            }
            case "admin-room" -> {
                // admin-room host port token operator roomId name description
                com.nexus.banking.contract.remote.AdminRemote admin = (com.nexus.banking.contract.remote.AdminRemote) registry(host, port).lookup("NexusAdminService");
                OperationResult<Void> r = admin.createRoom(a[3], a[4], a[5], a[6], a[7]);
                System.out.println("ROOM " + a[5] + " " + (r.isSuccess() ? "created" : r.getMessage()));
            }
            default -> throw new IllegalArgumentException("unknown command " + cmd);
        }
    }

    private static void check(OperationResult<?> r) {
        if (!r.isSuccess()) throw new IllegalStateException(r.getErrorCode() + ": " + r.getMessage());
    }
}
