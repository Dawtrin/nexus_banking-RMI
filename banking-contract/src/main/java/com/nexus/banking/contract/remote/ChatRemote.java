package com.nexus.banking.contract.remote;

import com.nexus.banking.contract.dto.ChatMessageDTO;
import com.nexus.banking.contract.dto.ChatRoomDTO;
import com.nexus.banking.contract.dto.ClientSessionDTO;
import com.nexus.banking.contract.dto.MemberDTO;
import com.nexus.banking.contract.dto.MessagePage;
import com.nexus.banking.contract.dto.RoomSummaryDTO;
import com.nexus.banking.contract.dto.UserProfileDTO;
import com.nexus.banking.contract.dto.OperationResult;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Client-facing chat API. Every method except {@link #registerClient} and {@link #getServerId} requires
 * a valid session; room operations additionally require that the session joined the room.
 * All failures are reported through {@link OperationResult#getErrorCode()} (see ErrorCodes).
 *
 * <p>Contract v2: not backward compatible with v1 (OperationResult everywhere, clientMessageId,
 * bounded history/paging).</p>
 */
public interface ChatRemote extends Remote {

    /** Username-only demo registration (NOT a secure identity check). */
    OperationResult<ClientSessionDTO> registerClient(String username, String clientInstanceId) throws RemoteException;

    OperationResult<List<ChatRoomDTO>> getRooms(String sessionId) throws RemoteException;

    /** Rooms with the number of users currently joined on this node. */
    OperationResult<List<RoomSummaryDTO>> getRoomSummaries(String sessionId) throws RemoteException;

    OperationResult<Void> joinRoom(String sessionId, String roomId) throws RemoteException;

    OperationResult<Void> leaveRoom(String sessionId, String roomId) throws RemoteException;

    /** Users currently joined to the room on this node (the caller must be a member). */
    OperationResult<List<MemberDTO>> getRoomMembers(String sessionId, String roomId) throws RemoteException;

    OperationResult<UserProfileDTO> getProfile(String sessionId) throws RemoteException;

    /** Updates display name (2-40 chars), optional email and presence; applies to later messages and member lists. */
    OperationResult<UserProfileDTO> updateProfile(String sessionId, String displayName, String email, String presence)
            throws RemoteException;

    /** Updates display name (2-40 chars), optional email, presence and custom/preset avatar. */
    OperationResult<UserProfileDTO> updateProfile(String sessionId, String displayName, String email, String presence, String avatar)
            throws RemoteException;

    /**
     * Sends a message. {@code clientMessageId} (1-64 chars, client generated, e.g. a UUID) makes the
     * call idempotent: retrying with the same id returns the original message instead of a duplicate.
     */
    OperationResult<ChatMessageDTO> sendMessage(String sessionId, String roomId, String clientMessageId, String content)
            throws RemoteException;

    /**
     * Edits an existing message sent by the caller. Updates content and appends "(edited)".
     */
    OperationResult<ChatMessageDTO> editMessage(String sessionId, String messageId, String newContent)
            throws RemoteException;

    /** Latest messages (up to {@code limit}, in display order) plus the cursor to continue with {@link #getMessages}. */
    OperationResult<MessagePage> getHistory(String sessionId, String roomId, int limit) throws RemoteException;

    /** Messages ingested after {@code afterCursor}, oldest ingestion first, at most {@code limit}. */
    OperationResult<MessagePage> getMessages(String sessionId, String roomId, long afterCursor, int limit)
            throws RemoteException;

    OperationResult<Void> heartbeat(String sessionId) throws RemoteException;

    OperationResult<Void> disconnectClient(String sessionId) throws RemoteException;

    String getServerId() throws RemoteException;
}
