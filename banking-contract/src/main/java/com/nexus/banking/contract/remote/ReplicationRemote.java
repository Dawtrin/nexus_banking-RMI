package com.nexus.banking.contract.remote;

import com.nexus.banking.contract.dto.ChatMessageDTO;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.ReplicationAck;
import com.nexus.banking.contract.dto.ServerStatusDTO;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Node-to-node replication API. Every call carries the caller's server id and a shared secret; the
 * receiver validates both against its configured peer. Contract v2 (v1 had no authentication and a
 * separate single-message call, which is removed).
 */
public interface ReplicationRemote extends Remote {

    /** Durably accepts a batch; the ack lists only messages that are committed on the receiver. */
    OperationResult<ReplicationAck> replicateBatch(String sourceServerId, String replicationSecret,
                                                   List<ChatMessageDTO> messages) throws RemoteException;

    /** Messages originated by the answering node with originSequence greater than {@code afterOriginSequence}. */
    OperationResult<List<ChatMessageDTO>> getMessagesAfter(String requesterServerId, String replicationSecret,
                                                           long afterOriginSequence, int limit) throws RemoteException;

    OperationResult<ServerStatusDTO> getPeerStatus(String requesterServerId, String replicationSecret)
            throws RemoteException;

    /**
     * Evicts any active session for the given username on this peer node (distributed single-active-session).
     */
    default OperationResult<Integer> evictUserSession(String requesterServerId, String replicationSecret,
                                                      String username, String reason) throws RemoteException {
        return OperationResult.success(0);
    }
}
