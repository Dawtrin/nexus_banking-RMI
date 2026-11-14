package com.nexus.banking.contract.remote;

import com.nexus.banking.contract.dto.AuditEntryDTO;
import com.nexus.banking.contract.dto.MemberDTO;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.RoomSummaryDTO;
import com.nexus.banking.contract.dto.UserAdminDTO;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;
import java.util.Map;

/**
 * Administrative actions on ONE node. Every call needs the admin token; {@code actor} is the operator name
 * typed in NEXUS CONTROL and is recorded in the audit log (it is a label, NOT an authenticated identity: the
 * token is what authenticates). Room changes apply to this node only; NEXUS CONTROL applies them to both nodes.
 */
public interface AdminRemote extends Remote {

    OperationResult<List<RoomSummaryDTO>> listRooms(String adminToken) throws RemoteException;

    OperationResult<Void> createRoom(String adminToken, String actor, String roomId, String roomName, String description)
            throws RemoteException;

    OperationResult<Void> updateRoom(String adminToken, String actor, String roomId, String roomName, String description)
            throws RemoteException;

    /** Only empty rooms (no stored messages) can be deleted; members are removed from the room. */
    OperationResult<Void> deleteRoom(String adminToken, String actor, String roomId) throws RemoteException;

    OperationResult<List<MemberDTO>> getRoomMembers(String adminToken, String roomId) throws RemoteException;

    OperationResult<List<UserAdminDTO>> listUsers(String adminToken) throws RemoteException;

    /** Ends one live session (the user may reconnect). */
    OperationResult<Void> disconnectSession(String adminToken, String actor, String sessionId) throws RemoteException;

    OperationResult<List<AuditEntryDTO>> getAuditLog(String adminToken, int limit) throws RemoteException;

    /** Moves dead-lettered replication items back to PENDING; returns how many were requeued. */
    OperationResult<Integer> requeueDeadLetters(String adminToken, String actor) throws RemoteException;

    /** Runs a replication cycle now instead of waiting for the next poll. */
    OperationResult<Void> syncNow(String adminToken, String actor) throws RemoteException;

    /** Effective, non-secret configuration of the node (secrets are never included). */
    OperationResult<Map<String, String>> getServerConfig(String adminToken) throws RemoteException;
}
