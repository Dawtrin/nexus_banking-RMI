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
import com.nexus.banking.contract.remote.ChatRemote;
import com.nexus.banking.client.model.ConnectionState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * UI-independent client logic: connection state machine, reconnection with backoff, room isolation,
 * duplicate suppression and idempotent sends. Every remote operation runs on ONE serialized executor, so
 * connect / join / poll / send can never interleave (no cross-room appends, no stale cursor writes).
 * The listener is called on that executor; UI adapters must hop to their own thread.
 */
public final class ClientCore implements AutoCloseable {

    @FunctionalInterface
    public interface Connector {
        ChatRemote connect(String host, int port) throws Exception;
    }

    public interface Listener {
        void onState(ConnectionState state, String status);

        void onSession(String serverId, String username);

        void onRooms(List<ChatRoomDTO> rooms);

        /** The visible timeline must be cleared: the active room changed. */
        void onRoomChanged(String roomId);

        /** New (never delivered before) messages of {@code roomId}, in delivery order. */
        void onMessages(String roomId, List<ChatMessageDTO> messages);

        /** Called when this session is evicted by a newer login on another node/client. */
        default void onEvicted(String reason) {}
    }

    public record SendOutcome(boolean ok, boolean retryable, String message, ChatMessageDTO stored) {
    }

    private static final int HISTORY_LIMIT = 200;
    private static final int POLL_PAGE = 100;
    private static final int MAX_PAGES_PER_TICK = 10;
    private static final int MAX_REMEMBERED_IDS = 5000;

    private final Connector connector;
    private final Listener listener;
    private final Executor io;
    private final LongSupplier clock;
    private final long backoffBaseMillis;
    private final long backoffMaxMillis;
    private final String instanceId = UUID.randomUUID().toString();
    private ScheduledExecutorService scheduler;
    private ExecutorService ownedIo;

    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private volatile String currentRoom;
    private volatile boolean autoReconnect = true;
    private volatile String username = "";
    private String host;
    private int port;
    private ChatRemote remote;
    private String session;
    private String serverId;
    private long cursor;
    /** True once the cursor was initialised from a history load on the current server. */
    private boolean cursorValid;
    private long reconnectAttempt;
    private long nextReconnectAt;
    private String lastFailedText;
    private String lastFailedId;
    private final Map<String, String> seen = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_REMEMBERED_IDS;
        }
    };

    /** Test constructor: caller supplies the executor, clock, and calls {@link #tick()} itself. */
    public ClientCore(Connector connector, Listener listener, Executor io, LongSupplier clock,
                      long backoffBaseMillis, long backoffMaxMillis) {
        this.connector = connector;
        this.listener = listener;
        this.io = io;
        this.clock = clock;
        this.backoffBaseMillis = backoffBaseMillis;
        this.backoffMaxMillis = backoffMaxMillis;
    }

    /** Production instance: serialized IO thread plus a 1 s poll tick. */
    public static ClientCore start(Connector connector, Listener listener, long pollMillis) {
        ExecutorService io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nexus-client-io");
            t.setDaemon(true);
            return t;
        });
        ClientCore core = new ClientCore(connector, listener, io, System::currentTimeMillis, 1000, 15000);
        core.ownedIo = io;
        core.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nexus-client-tick");
            t.setDaemon(true);
            return t;
        });
        core.scheduler.scheduleWithFixedDelay(() -> io.execute(core::tick), pollMillis, pollMillis, TimeUnit.MILLISECONDS);
        return core;
    }

    public ConnectionState getState() {
        return state;
    }

    public String getCurrentRoom() {
        return currentRoom;
    }

    public String getInstanceId() {
        return instanceId;
    }

    /** True when the message was sent by this client installation under the current username. */
    public boolean isOwn(ChatMessageDTO dto) {
        return dto.getSenderId().equals(username.toLowerCase() + "#" + instanceId);
    }

    private <T> CompletableFuture<T> submit(java.util.concurrent.Callable<T> task) {
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            io.execute(() -> {
                try {
                    f.complete(task.call());
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                }
            });
        } catch (RuntimeException e) {
            f.completeExceptionally(e);
        }
        return f;
    }

    private void setState(ConnectionState s, String status) {
        state = s;
        listener.onState(s, status);
    }

    // ---------------------------------------------------------------- connect

    public CompletableFuture<OperationResult<ClientSessionDTO>> connect(String host, int port, String username) {
        return submit(() -> {
            this.host = host;
            this.port = port;
            this.username = username;
            this.serverId = null;
            this.currentRoom = null;
            this.cursor = 0;
            this.cursorValid = false;
            seen.clear();
            setState(ConnectionState.CONNECTING, "Connecting to " + host + ":" + port + "...");
            try {
                ClientSessionDTO dto = establish(false);
                return OperationResult.success("Connected", dto);
            } catch (Exception e) {
                forgetConnection();
                setState(ConnectionState.FAILED, "Connection failed: " + describe(e));
                return OperationResult.failure("CONNECTION_FAILED", describe(e));
            }
        });
    }

    /** Opens a session, loads rooms and enters/resumes the active room. */
    private ClientSessionDTO establish(boolean resume) throws Exception {
        ChatRemote r = connector.connect(host, port);
        OperationResult<ClientSessionDTO> reg = r.registerClient(username, instanceId);
        if (!reg.isSuccess()) throw new ClientException(reg.getMessage());
        String sid = reg.getData().getSessionId();
        String sid2 = r.getServerId();
        OperationResult<List<ChatRoomDTO>> rooms = r.getRooms(sid);
        if (!rooms.isSuccess()) throw new ClientException(rooms.getMessage());

        boolean sameServer = resume && sid2.equals(serverId);
        remote = r;
        session = sid;
        serverId = sid2;
        listener.onSession(sid2, username);
        listener.onRooms(rooms.getData());

        String room = currentRoom;
        if (room == null) {
            room = rooms.getData().stream().anyMatch(x -> x.getRoomId().equals("general"))
                    ? "general" : rooms.getData().isEmpty() ? null : rooms.getData().get(0).getRoomId();
        }
        if (room != null) {
            if (!sameServer) {
                cursor = 0;
                cursorValid = false;
            }
            enterRoom(room, !resume || currentRoom == null);
        }
        reconnectAttempt = 0;
        setState(ConnectionState.CONNECTED, "Connected to " + sid2);
        return reg.getData();
    }

    // ------------------------------------------------------------------ rooms

    public CompletableFuture<Void> joinRoom(String roomId) {
        return submit(() -> {
            if (state != ConnectionState.CONNECTED || roomId == null || roomId.equals(currentRoom)) return null;
            try {
                enterRoom(roomId, true);
            } catch (Exception e) {
                handleFailure(e, "Could not open room");
            }
            return null;
        });
    }

    private void enterRoom(String roomId, boolean reset) throws Exception {
        OperationResult<Void> j = remote.joinRoom(session, roomId);
        if (!j.isSuccess()) throw new ClientException("Cannot join " + roomId + ": " + j.getMessage());
        if (reset) {
            currentRoom = roomId;
            cursor = 0;
            cursorValid = false;
            seen.clear();
            listener.onRoomChanged(roomId);
        } else {
            currentRoom = roomId;
        }
        if (cursorValid) {
            catchUp(roomId);
        } else {
            OperationResult<MessagePage> h = remote.getHistory(session, roomId, HISTORY_LIMIT);
            if (!h.isSuccess()) throw new ClientException("Cannot load history: " + h.getMessage());
            deliver(roomId, h.getData().getMessages());
            cursor = h.getData().getNextCursor();
            cursorValid = true;
        }
    }

    private void catchUp(String roomId) throws Exception {
        for (int i = 0; i < MAX_PAGES_PER_TICK * 10; i++) {
            OperationResult<MessagePage> r = remote.getMessages(session, roomId, cursor, POLL_PAGE);
            if (!r.isSuccess()) throw new ClientException("Cannot fetch messages: " + r.getMessage());
            deliver(roomId, r.getData().getMessages());
            cursor = r.getData().getNextCursor();
            if (!r.getData().hasMore()) return;
        }
    }

    private void deliver(String roomId, List<ChatMessageDTO> messages) {
        List<ChatMessageDTO> fresh = new ArrayList<>();
        for (ChatMessageDTO m : messages) {
            if (roomId.equals(m.getRoomId())) {
                String prev = seen.put(m.getMessageId(), m.getContent());
                if (prev == null || !prev.equals(m.getContent())) {
                    fresh.add(m);
                }
            }
        }
        if (!fresh.isEmpty()) listener.onMessages(roomId, fresh);
    }

    // ------------------------------------------------------------------- send

    public CompletableFuture<SendOutcome> editMessage(String messageId, String newText) {
        return submit(() -> {
            if (state != ConnectionState.CONNECTED || remote == null) {
                return new SendOutcome(false, true, "Not connected", null);
            }
            try {
                OperationResult<ChatMessageDTO> r = remote.editMessage(session, messageId, newText);
                if (r.isSuccess() && currentRoom != null) {
                    deliver(currentRoom, List.of(r.getData()));
                    return new SendOutcome(true, false, r.getMessage(), r.getData());
                }
                if (ErrorCodes.SESSION_EVICTED.equals(r.getErrorCode())) {
                    forgetConnection();
                    String reason = (r.getMessage() != null && !r.getMessage().isBlank()) ? r.getMessage() : "Tài khoản của bạn đã được đăng nhập từ một thiết bị khác.";
                    setState(ConnectionState.DISCONNECTED, reason);
                    listener.onEvicted(reason);
                    return new SendOutcome(false, false, reason, null);
                }
                return new SendOutcome(false, false, r.getMessage(), null);
            } catch (Exception e) {
                return new SendOutcome(false, true, "Failed to edit message: " + describe(e), null);
            }
        });
    }

    public CompletableFuture<SendOutcome> sendMessage(String text) {
        return submit(() -> {
            if (state != ConnectionState.CONNECTED || remote == null || currentRoom == null) {
                return new SendOutcome(false, true, "Not connected - message was not sent.", null);
            }
            String id = text.equals(lastFailedText) ? lastFailedId : UUID.randomUUID().toString();
            String room = currentRoom;
            try {
                OperationResult<ChatMessageDTO> r = remote.sendMessage(session, room, id, text);
                if (r.isSuccess()) {
                    lastFailedText = null;
                    lastFailedId = null;
                    deliver(room, List.of(r.getData()));
                    return new SendOutcome(true, false, r.getMessage(), r.getData());
                }
                String code = r.getErrorCode();
                if (ErrorCodes.SESSION_EVICTED.equals(code)) {
                    forgetConnection();
                    String reason = (r.getMessage() != null && !r.getMessage().isBlank()) ? r.getMessage() : "Tài khoản của bạn đã được đăng nhập từ một thiết bị khác.";
                    setState(ConnectionState.DISCONNECTED, reason);
                    listener.onEvicted(reason);
                    return new SendOutcome(false, false, reason, null);
                }
                if (ErrorCodes.VALIDATION.equals(code)) {
                    return new SendOutcome(false, false, r.getMessage(), null);
                }
                lastFailedText = text;
                lastFailedId = id;
                if (ErrorCodes.INVALID_SESSION.equals(code)) startReconnect("Session expired");
                if (ErrorCodes.NOT_IN_ROOM.equals(code)) remote.joinRoom(session, room);
                return new SendOutcome(false, true, r.getMessage(), null);
            } catch (Exception e) {
                lastFailedText = text;
                lastFailedId = id;
                startReconnect("Connection lost: " + describe(e));
                return new SendOutcome(false, true,
                        "Message not confirmed (connection lost). Send again to retry - it will not be duplicated.", null);
            }
        });
    }

    // ------------------------------------------------------- polling/recovery

    /** One poll or reconnect step. Must run on the serialized executor. */
    public void tick() {
        try {
            if (state == ConnectionState.CONNECTED) poll();
            else if (state == ConnectionState.RECONNECTING) tryReconnect();
        } catch (RuntimeException e) {
            startReconnect("Unexpected error: " + describe(e));
        }
    }

    private void poll() {
        String room = currentRoom;
        try {
            if (room == null) {
                OperationResult<Void> hb = remote.heartbeat(session);
                if (!hb.isSuccess()) handleResultError(hb.getErrorCode(), hb.getMessage(), null);
                return;
            }
            for (int pages = 0; pages < MAX_PAGES_PER_TICK; pages++) {
                OperationResult<MessagePage> r = remote.getMessages(session, room, cursor, POLL_PAGE);
                if (!r.isSuccess()) {
                    handleResultError(r.getErrorCode(), r.getMessage(), room);
                    return;
                }
                deliver(room, r.getData().getMessages());
                cursor = r.getData().getNextCursor();
                if (!r.getData().hasMore()) return;
            }
        } catch (Exception e) {
            startReconnect("Connection lost: " + describe(e));
        }
    }

    private void handleResultError(String code, String message, String room) throws Exception {
        if (ErrorCodes.SESSION_EVICTED.equals(code)) {
            forgetConnection();
            String reason = (message != null && !message.isBlank())
                    ? message : "Tài khoản của bạn đã được đăng nhập từ một thiết bị khác.";
            setState(ConnectionState.DISCONNECTED, reason);
            listener.onEvicted(reason);
            return;
        } else if (ErrorCodes.INVALID_SESSION.equals(code)) {
            startReconnect("Session expired or server restarted");
        } else if (ErrorCodes.NOT_IN_ROOM.equals(code) && room != null) {
            OperationResult<Void> j = remote.joinRoom(session, room);
            if (!j.isSuccess()) listener.onState(state, "Cannot rejoin " + room + ": " + j.getMessage());
        } else {
            listener.onState(state, "Server reported: " + message);
        }
    }

    private void handleFailure(Exception e, String what) {
        if (e instanceof ClientException) listener.onState(state, what + ": " + e.getMessage());
        else startReconnect(what + ": " + describe(e));
    }

    public void setAutoReconnect(boolean enabled) {
        this.autoReconnect = enabled;
    }

    private void startReconnect(String reason) {
        if (state == ConnectionState.RECONNECTING || state == ConnectionState.DISCONNECTED || state == ConnectionState.FAILED) return;
        remote = null;
        session = null;
        if (!autoReconnect) {
            setState(ConnectionState.FAILED, reason + ". Automatic reconnect is switched off in Settings.");
            return;
        }
        reconnectAttempt = 0;
        nextReconnectAt = clock.getAsLong();
        setState(ConnectionState.RECONNECTING, reason + ". Reconnecting...");
    }

    private void tryReconnect() {
        if (clock.getAsLong() < nextReconnectAt) return;
        reconnectAttempt++;
        try {
            establish(true);
        } catch (Exception e) {
            forgetConnection();
            long delay = Math.min(backoffMaxMillis, backoffBaseMillis * (1L << Math.min(reconnectAttempt - 1, 10)));
            nextReconnectAt = clock.getAsLong() + delay;
            setState(ConnectionState.RECONNECTING, "Reconnecting (attempt " + reconnectAttempt + "): " + describe(e));
        }
    }

    private void forgetConnection() {
        remote = null;
        session = null;
    }

    // ------------------------------------------------- rooms, members, profile

    private interface RemoteCall<T> {
        OperationResult<T> run(ChatRemote r, String session) throws Exception;
    }

    private <T> CompletableFuture<OperationResult<T>> call(String what, RemoteCall<T> c) {
        return submit(() -> {
            if (state != ConnectionState.CONNECTED || remote == null) {
                return OperationResult.<T>failure("NOT_CONNECTED", "Not connected to a server.");
            }
            try {
                OperationResult<T> r = c.run(remote, session);
                if (!r.isSuccess() && ErrorCodes.INVALID_SESSION.equals(r.getErrorCode())) startReconnect("Session expired");
                return r;
            } catch (Exception e) {
                startReconnect(what + " failed: " + describe(e));
                return OperationResult.<T>failure("CONNECTION_LOST", describe(e));
            }
        });
    }

    public CompletableFuture<OperationResult<List<RoomSummaryDTO>>> roomSummaries() {
        return call("Loading rooms", (r, sid) -> r.getRoomSummaries(sid));
    }

    public CompletableFuture<OperationResult<List<MemberDTO>>> members(String roomId) {
        return call("Loading members", (r, sid) -> r.getRoomMembers(sid, roomId));
    }

    public CompletableFuture<OperationResult<UserProfileDTO>> profile() {
        return call("Loading profile", (r, sid) -> r.getProfile(sid));
    }

    public CompletableFuture<OperationResult<UserProfileDTO>> updateProfile(String displayName, String email, String presence) {
        return updateProfile(displayName, email, presence, "");
    }

    public CompletableFuture<OperationResult<UserProfileDTO>> updateProfile(String displayName, String email, String presence, String avatar) {
        return call("Saving profile", (r, sid) -> r.updateProfile(sid, displayName, email, presence, avatar));
    }

    /** Leaves a room; leaving the active room clears the timeline until another room is joined. */
    public CompletableFuture<OperationResult<Void>> leaveRoom(String roomId) {
        return call("Leaving room", (r, sid) -> {
            OperationResult<Void> res = r.leaveRoom(sid, roomId);
            if (res.isSuccess() && roomId.equals(currentRoom)) {
                currentRoom = null;
                cursor = 0;
                cursorValid = false;
                seen.clear();
                listener.onRoomChanged(null);
            }
            return res;
        });
    }

    // ------------------------------------------------------------- disconnect

    public CompletableFuture<Void> disconnect() {
        return submit(() -> {
            ChatRemote r = remote;
            String s = session;
            if (r != null && s != null) {
                try {
                    r.disconnectClient(s);
                } catch (Exception ignored) {
                    // the server will time the session out
                }
            }
            forgetConnection();
            currentRoom = null;
            cursor = 0;
            cursorValid = false;
            seen.clear();
            lastFailedText = null;
            lastFailedId = null;
            setState(ConnectionState.DISCONNECTED, "Disconnected");
            return null;
        });
    }

    /** Orderly shutdown: send the disconnect first, then stop the executors. */
    @Override
    public void close() {
        try {
            disconnect().get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // best effort
        }
        if (scheduler != null) scheduler.shutdownNow();
        if (ownedIo != null) ownedIo.shutdown();
    }

    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String m = root.getMessage();
        return m == null || m.isBlank() ? root.getClass().getSimpleName() : m;
    }

    /** A failure reported by the server in a well-formed result. */
    private static final class ClientException extends Exception {
        ClientException(String message) {
            super(message);
        }
    }
}
