package com.nexus.banking.contract.remote;

import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.ReplicationAck;
import com.nexus.banking.contract.dto.ServerStatusDTO;
import com.nexus.banking.contract.dto.TransactionDTO;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Branch-to-branch replication API. Every call carries the caller's server id and a shared secret; the receiver
 * validates both against its configured peer.
 */
public interface BankingReplicationService extends Remote {

    /** Durably applies a batch of ledger entries (updating balances); the ack lists only committed entries. */
    OperationResult<ReplicationAck> replicateBatch(String sourceServerId, String replicationSecret,
                                                   List<TransactionDTO> entries) throws RemoteException;

    /** Entries originated by the answering node with originSequence greater than {@code afterOriginSequence}. */
    OperationResult<List<TransactionDTO>> getTransactionsAfter(String requesterServerId, String replicationSecret,
                                                               long afterOriginSequence, int limit) throws RemoteException;

    OperationResult<ServerStatusDTO> getPeerStatus(String requesterServerId, String replicationSecret)
            throws RemoteException;

    /**
     * Ends any session of {@code accountNumber} on this node (cluster-wide single session). Sessions whose device id
     * equals {@code exceptClientInstanceId} are kept: that is the same installation reconnecting.
     */
    default OperationResult<Integer> evictAccountSession(String requesterServerId, String replicationSecret,
                                                         String accountNumber, String exceptClientInstanceId,
                                                         String reason) throws RemoteException {
        return OperationResult.success(0);
    }
}
