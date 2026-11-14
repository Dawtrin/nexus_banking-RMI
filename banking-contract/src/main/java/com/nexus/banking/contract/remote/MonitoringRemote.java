package com.nexus.banking.contract.remote;

import com.nexus.banking.contract.dto.ClientSessionDTO;
import com.nexus.banking.contract.dto.DatabaseHealthDTO;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.ReplicationStatusDTO;
import com.nexus.banking.contract.dto.ServerEventDTO;
import com.nexus.banking.contract.dto.ServerStatusDTO;
import com.nexus.banking.contract.dto.ThroughputPointDTO;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/** Administrative monitoring API. Every method requires the configured admin token (contract v2). */
public interface MonitoringRemote extends Remote {

    OperationResult<ServerStatusDTO> getServerStatus(String adminToken) throws RemoteException;

    OperationResult<List<ClientSessionDTO>> getActiveSessions(String adminToken) throws RemoteException;

    OperationResult<List<ServerEventDTO>> getRecentEvents(String adminToken, int limit) throws RemoteException;

    OperationResult<DatabaseHealthDTO> getDatabaseHealth(String adminToken) throws RemoteException;

    OperationResult<ReplicationStatusDTO> getReplicationStatus(String adminToken) throws RemoteException;

    /** Message counters per time bucket for the last {@code minutes} minutes (1-60). */
    OperationResult<List<ThroughputPointDTO>> getThroughput(String adminToken, int minutes) throws RemoteException;
}
