package com.nexus.banking.client.core;

import com.nexus.banking.contract.dto.ChatMessageDTO;
import com.nexus.banking.contract.dto.ChatRoomDTO;
import com.nexus.banking.contract.dto.ClientSessionDTO;
import com.nexus.banking.contract.dto.ErrorCodes;
import com.nexus.banking.contract.dto.MemberDTO;
import com.nexus.banking.contract.dto.MessagePage;
import com.nexus.banking.contract.dto.RoomSummaryDTO;
import com.nexus.banking.contract.dto.UserProfileDTO;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.enums.MessageType;
import com.nexus.banking.contract.enums.SessionStatus;
import com.nexus.banking.contract.remote.ChatRemote;

import java.rmi.RemoteException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Minimal in-memory implementation of the ChatRemote contract with failure switches. */
final class FakeServer implements ChatRemote {
    final String id;
    volatile boolean down;
    volatile boolean dropNextSendResponse;
    int disconnects;
    int registrations;
    final List<ChatMessageDTO> messages = new ArrayList<>();
    final Map<String, String> sessions = new HashMap<>();                 // sessionId -> username
    final Set<String> evictedSessions = new HashSet<>();
    final Map<String, Set<String>> joined = new HashMap<>();
    final Map<String, ChatMessageDTO> byKey = new HashMap<>();
    long local = 0;

    FakeServer(String id) {
        this.id = id;
    }

    private void check() throws RemoteException {
        if (down) throw new RemoteException("connection refused (fake)");
    }

    /** Server-side event: a message arrives from another user / replication. */
    synchronized ChatMessageDTO inject(String room, String text) {
        ChatMessageDTO m = new ChatMessageDTO(UUID.randomUUID().toString(), room, "other#instance-zzzz-0001", "other", text,
                Instant.now(), id, local + 1, MessageType.CHAT, ++local);
        messages.add(m);
        return m;
    }

    /** Simulates a server restart: every session is forgotten. */
    synchronized void restart() {
        sessions.clear();
        joined.clear();
    }

    @Override
    public synchronized OperationResult<ClientSessionDTO> registerClient(String username, String instance) throws RemoteException {
        check();
        registrations++;
        String sid = UUID.randomUUID().toString();
        sessions.put(sid, username + "#" + instance);
        joined.put(sid, new HashSet<>());
        return OperationResult.success(new ClientSessionDTO(sid, username, instance, "127.0.0.1", 0, Instant.now(), Instant.now(),
                SessionStatus.ACTIVE, id));
    }

    @Override
    public synchronized OperationResult<List<ChatRoomDTO>> getRooms(String s) throws RemoteException {
        check();
        if (!sessions.containsKey(s)) return OperationResult.failure(ErrorCodes.INVALID_SESSION, "invalid");
        return OperationResult.success(List.of(new ChatRoomDTO("general", "General", "", Instant.now()),
                new ChatRoomDTO("random", "Random", "", Instant.now())));
    }

    @Override
    public synchronized OperationResult<Void> joinRoom(String s, String room) throws RemoteException {
        check();
        if (!sessions.containsKey(s)) return OperationResult.failure(ErrorCodes.INVALID_SESSION, "invalid");
        joined.get(s).add(room);
        return OperationResult.ok();
    }

    @Override
    public synchronized OperationResult<ChatMessageDTO> sendMessage(String s, String room, String cmid, String content) throws RemoteException {
        check();
        if (!sessions.containsKey(s)) return OperationResult.failure(ErrorCodes.INVALID_SESSION, "invalid");
        if (content.length() > 4000) return OperationResult.failure(ErrorCodes.VALIDATION, "too long");
        String senderId = sessions.get(s);
        String key = senderId + "|" + cmid;
        ChatMessageDTO m = byKey.get(key);
        if (m == null) {
            m = new ChatMessageDTO(UUID.randomUUID().toString(), room, senderId.toLowerCase(), senderId.split("#")[0], content,
                    Instant.now(), id, local + 1, MessageType.CHAT, ++local);
            messages.add(m);
            byKey.put(key, m);
        }
        if (dropNextSendResponse) {
            dropNextSendResponse = false;
            throw new RemoteException("response lost (fake)");
        }
        return OperationResult.success("Stored", m);
    }

    @Override
    public synchronized OperationResult<MessagePage> getHistory(String s, String room, int limit) throws RemoteException {
        check();
        OperationResult<MessagePage> denied = guard(s, room);
        if (denied != null) return denied;
        List<ChatMessageDTO> inRoom = messages.stream().filter(m -> m.getRoomId().equals(room)).toList();
        long max = inRoom.stream().mapToLong(ChatMessageDTO::getLocalSequence).max().orElse(0);
        List<ChatMessageDTO> page = inRoom.subList(Math.max(0, inRoom.size() - limit), inRoom.size());
        return OperationResult.success(new MessagePage(page, max, inRoom.size() > limit));
    }

    @Override
    public synchronized OperationResult<MessagePage> getMessages(String s, String room, long after, int limit) throws RemoteException {
        check();
        OperationResult<MessagePage> denied = guard(s, room);
        if (denied != null) return denied;
        List<ChatMessageDTO> rows = new ArrayList<>(messages.stream()
                .filter(m -> m.getRoomId().equals(room) && m.getLocalSequence() > after).toList());
        boolean more = rows.size() > limit;
        if (more) rows = new ArrayList<>(rows.subList(0, limit));
        long next = rows.isEmpty() ? after : rows.get(rows.size() - 1).getLocalSequence();
        return OperationResult.success(new MessagePage(rows, next, more));
    }

    synchronized void evict(String s) {
        evictedSessions.add(s);
        sessions.remove(s);
    }

    private <T> OperationResult<T> guard(String s, String room) {
        if (evictedSessions.contains(s)) return OperationResult.failure(ErrorCodes.SESSION_EVICTED, "Logged in elsewhere");
        if (!sessions.containsKey(s)) return OperationResult.failure(ErrorCodes.INVALID_SESSION, "invalid");
        if (!joined.get(s).contains(room)) return OperationResult.failure(ErrorCodes.NOT_IN_ROOM, "join first");
        return null;
    }

    @Override
    public synchronized OperationResult<Void> heartbeat(String s) throws RemoteException {
        check();
        if (evictedSessions.contains(s)) return OperationResult.failure(ErrorCodes.SESSION_EVICTED, "Logged in elsewhere");
        return sessions.containsKey(s) ? OperationResult.ok() : OperationResult.failure(ErrorCodes.INVALID_SESSION, "invalid");
    }

    @Override
    public synchronized OperationResult<Void> disconnectClient(String s) throws RemoteException {
        check();
        disconnects++;
        sessions.remove(s);
        return OperationResult.ok();
    }

    @Override
    public String getServerId() throws RemoteException {
        check();
        return id;
    }

    @Override
    public synchronized OperationResult<List<RoomSummaryDTO>> getRoomSummaries(String s) throws RemoteException {
        check();
        if (!sessions.containsKey(s)) return OperationResult.failure(ErrorCodes.INVALID_SESSION, "invalid");
        return OperationResult.success(List.of(new RoomSummaryDTO("general", "General", "", 1, -1, Instant.now())));
    }

    @Override
    public synchronized OperationResult<Void> leaveRoom(String s, String room) throws RemoteException {
        check();
        if (!sessions.containsKey(s)) return OperationResult.failure(ErrorCodes.INVALID_SESSION, "invalid");
        joined.get(s).remove(room);
        return OperationResult.ok();
    }

    @Override
    public synchronized OperationResult<List<MemberDTO>> getRoomMembers(String s, String room) throws RemoteException {
        check();
        if (!sessions.containsKey(s)) return OperationResult.failure(ErrorCodes.INVALID_SESSION, "invalid");
        return OperationResult.success(List.of());
    }

    @Override
    public synchronized OperationResult<UserProfileDTO> getProfile(String s) throws RemoteException {
        check();
        if (!sessions.containsKey(s)) return OperationResult.failure(ErrorCodes.INVALID_SESSION, "invalid");
        return OperationResult.success(new UserProfileDTO("alice", "Alice", "", null, Instant.now()));
    }

    @Override
    public synchronized OperationResult<UserProfileDTO> updateProfile(String s, String name, String email, String presence) throws RemoteException {
        return updateProfile(s, name, email, presence, "");
    }

    @Override
    public synchronized OperationResult<UserProfileDTO> updateProfile(String s, String name, String email, String presence, String avatar) throws RemoteException {
        check();
        return OperationResult.success(new UserProfileDTO("alice", name, email, null, Instant.now(), avatar));
    }

    @Override
    public synchronized OperationResult<ChatMessageDTO> editMessage(String s, String messageId, String newContent) throws RemoteException {
        check();
        if (!sessions.containsKey(s)) return OperationResult.failure(ErrorCodes.INVALID_SESSION, "invalid");
        if (newContent == null || newContent.isBlank()) return OperationResult.failure(ErrorCodes.VALIDATION, "blank");
        for (int i = 0; i < messages.size(); i++) {
            ChatMessageDTO m = messages.get(i);
            if (m.getMessageId().equals(messageId)) {
                ChatMessageDTO updated = new ChatMessageDTO(m.getMessageId(), m.getRoomId(), m.getSenderId(), m.getSenderName(),
                        newContent.endsWith(" (edited)") ? newContent : newContent + " (edited)",
                        m.getCreatedAt(), m.getOriginServerId(), m.getOriginSequence(), m.getMessageType(), ++local);
                messages.set(i, updated);
                return OperationResult.success(updated);
            }
        }
        return OperationResult.failure(ErrorCodes.VALIDATION, "not found");
    }
}
