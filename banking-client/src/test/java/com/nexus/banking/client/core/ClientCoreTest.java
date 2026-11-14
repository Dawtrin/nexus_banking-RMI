package com.nexus.banking.client.core;

import com.nexus.banking.contract.dto.ChatMessageDTO;
import com.nexus.banking.contract.dto.ChatRoomDTO;
import com.nexus.banking.client.model.ConnectionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ClientCoreTest {
    private FakeServer server;
    private FakeServer secondServer;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<String> events = new ArrayList<>();
    private final List<ChatMessageDTO> shown = new ArrayList<>();
    private final List<ConnectionState> states = new ArrayList<>();
    private String shownRoom;
    private ClientCore core;
    private volatile FakeServer target;

    private final ClientCore.Listener listener = new ClientCore.Listener() {
        @Override
        public void onState(ConnectionState s, String status) {
            if (states.isEmpty() || states.get(states.size() - 1) != s) states.add(s);
        }

        @Override
        public void onSession(String serverId, String username) {
            events.add("session:" + serverId);
        }

        @Override
        public void onRooms(List<ChatRoomDTO> rooms) {
            events.add("rooms:" + rooms.size());
        }

        @Override
        public void onRoomChanged(String roomId) {
            shown.clear();
            shownRoom = roomId;
        }

        @Override
        public void onMessages(String roomId, List<ChatMessageDTO> messages) {
            assertThat(roomId).isEqualTo(shownRoom);              // never a message of another room
            for (ChatMessageDTO m : messages) assertThat(m.getRoomId()).isEqualTo(roomId);
            shown.addAll(messages);
        }

        @Override
        public void onEvicted(String reason) {
            events.add("evicted:" + reason);
        }
    };

    @BeforeEach
    void setUp() {
        server = new FakeServer("server-a");
        secondServer = new FakeServer("server-b");
        target = server;
        core = new ClientCore((h, p) -> target, listener, Runnable::run, now::get, 1000, 8000);
    }

    private List<String> texts() {
        return shown.stream().map(ChatMessageDTO::getContent).toList();
    }

    private void connect() throws Exception {
        assertThat(core.connect("host", 1099, "alice").get().isSuccess()).isTrue();
    }

    @Test
    void connectLoadsRoomsAndHistoryAndEntersGeneral() throws Exception {
        server.inject("general", "old-1");
        server.inject("general", "old-2");
        connect();
        assertThat(core.getState()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(core.getCurrentRoom()).isEqualTo("general");
        assertThat(texts()).containsExactly("old-1", "old-2");
        assertThat(events).contains("session:server-a", "rooms:2");
    }

    @Test
    void connectFailureEndsInFailedStateWithoutResidue() throws Exception {
        server.down = true;
        assertThat(core.connect("host", 1099, "alice").get().isSuccess()).isFalse();
        assertThat(core.getState()).isEqualTo(ConnectionState.FAILED);
    }

    @Test
    void pollingDeliversEachMessageOnce() throws Exception {
        connect();
        server.inject("general", "m1");
        core.tick();
        core.tick();
        server.inject("general", "m2");
        core.tick();
        assertThat(texts()).containsExactly("m1", "m2");
    }

    @Test
    void roomSwitchClearsTheTimelineAndNeverMixesRooms() throws Exception {
        server.inject("general", "g1");
        server.inject("random", "r1");
        connect();
        assertThat(texts()).containsExactly("g1");
        core.joinRoom("random").get();
        assertThat(shownRoom).isEqualTo("random");
        assertThat(texts()).containsExactly("r1");
        server.inject("general", "g2");     // arrives in the room we left
        server.inject("random", "r2");
        core.tick();
        assertThat(texts()).containsExactly("r1", "r2");
    }

    @Test
    void connectionLossEntersReconnectingAndRecoversWithoutRestart() throws Exception {
        connect();
        server.inject("general", "before");
        core.tick();
        server.down = true;
        core.tick();                                          // poll fails
        assertThat(core.getState()).isEqualTo(ConnectionState.RECONNECTING);
        core.tick();                                          // reconnect attempt fails (server still down)
        now.addAndGet(500);
        core.tick();                                          // inside backoff window: nothing happens
        assertThat(core.getState()).isEqualTo(ConnectionState.RECONNECTING);
        server.down = false;
        server.inject("general", "missed-while-down");
        now.addAndGet(10_000);
        core.tick();
        assertThat(core.getState()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(texts()).containsExactly("before", "missed-while-down");      // no duplicates, nothing lost
        server.inject("general", "after");
        core.tick();
        assertThat(texts()).containsExactly("before", "missed-while-down", "after");
        assertThat(states).containsSubsequence(ConnectionState.CONNECTING, ConnectionState.CONNECTED,
                ConnectionState.RECONNECTING, ConnectionState.CONNECTED);
    }

    @Test
    void serverRestartInvalidatesTheSessionAndTheClientReRegisters() throws Exception {
        connect();
        server.restart();
        core.tick();                                          // INVALID_SESSION
        assertThat(core.getState()).isEqualTo(ConnectionState.RECONNECTING);
        core.tick();                                          // immediate first attempt
        assertThat(core.getState()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(server.registrations).isEqualTo(2);
        server.inject("general", "post-restart");
        core.tick();
        assertThat(texts()).containsExactly("post-restart");
    }

    @Test
    void longOutageIsCaughtUpAcrossManyPages() throws Exception {
        connect();
        server.down = true;
        core.tick();
        for (int i = 1; i <= 350; i++) server.inject("general", "n" + i);
        server.down = false;
        core.tick();
        assertThat(core.getState()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(shown).hasSize(350);
    }

    @Test
    void failoverToAnotherServerReloadsHistoryAndDeduplicates() throws Exception {
        ChatMessageDTO shared = server.inject("general", "shared");
        connect();
        server.down = true;
        core.tick();
        secondServer.messages.add(shared);                    // replicated copy, different local sequence base
        secondServer.local = 1;
        secondServer.inject("general", "only-on-b");
        target = secondServer;
        core.tick();
        assertThat(core.getState()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(texts()).containsExactlyInAnyOrder("shared", "only-on-b");
        assertThat(texts().stream().filter("shared"::equals)).hasSize(1);
    }

    @Test
    void lostSendResponseIsRetriedWithTheSameIdAndDoesNotDuplicate() throws Exception {
        connect();
        server.dropNextSendResponse = true;
        ClientCore.SendOutcome first = core.sendMessage("hello").get();
        assertThat(first.ok()).isFalse();
        assertThat(first.retryable()).isTrue();
        assertThat(core.getState()).isEqualTo(ConnectionState.RECONNECTING);
        core.tick();                                          // reconnects
        assertThat(core.getState()).isEqualTo(ConnectionState.CONNECTED);
        ClientCore.SendOutcome retry = core.sendMessage("hello").get();
        assertThat(retry.ok()).isTrue();
        assertThat(server.messages.stream().filter(m -> m.getContent().equals("hello"))).hasSize(1);
    }

    @Test
    void validationFailuresAreNotRetryable() throws Exception {
        connect();
        ClientCore.SendOutcome r = core.sendMessage("x".repeat(4001)).get();
        assertThat(r.ok()).isFalse();
        assertThat(r.retryable()).isFalse();
    }

    @Test
    void ownMessagesAreRecognisedByInstanceNotJustByName() throws Exception {
        connect();
        ChatMessageDTO mine = core.sendMessage("mine").get().stored();
        ChatMessageDTO impostor = server.inject("general", "I am alice");
        assertThat(core.isOwn(mine)).isTrue();
        assertThat(core.isOwn(impostor)).isFalse();
    }

    @Test
    void disconnectNotifiesTheServerAndResetsState() throws Exception {
        connect();
        core.disconnect().get();
        assertThat(server.disconnects).isEqualTo(1);
        assertThat(core.getState()).isEqualTo(ConnectionState.DISCONNECTED);
        assertThat(core.sendMessage("x").get().ok()).isFalse();
    }

    @Test
    void closeSendsTheDisconnectBeforeStoppingTheExecutors() throws Exception {
        FakeServer s = new FakeServer("server-a");
        ClientCore real = ClientCore.start((h, p) -> s, listener, 60_000);
        real.connect("host", 1, "alice").get();
        real.close();
        assertThat(s.disconnects).isEqualTo(1);
    }

    @Test
    void autoReconnectOffEndsInFailedInsteadOfRetrying() throws Exception {
        core.setAutoReconnect(false);
        connect();
        server.down = true;
        core.tick();
        assertThat(core.getState()).isEqualTo(ConnectionState.FAILED);
        server.down = false;
        core.tick();
        assertThat(core.getState()).isEqualTo(ConnectionState.FAILED);      // stays failed: the user switched it off
    }

    @Test
    void leavingTheActiveRoomClearsTheTimelineAndStopsPolling() throws Exception {
        server.inject("general", "g1");
        connect();
        assertThat(core.leaveRoom("general").get().isSuccess()).isTrue();
        assertThat(core.getCurrentRoom()).isNull();
        assertThat(shown).isEmpty();
        assertThat(core.sendMessage("x").get().ok()).isFalse();
        core.tick();                                                          // heartbeat only, must not fail
        assertThat(core.getState()).isEqualTo(ConnectionState.CONNECTED);
    }

    @Test
    void operationsWhileDisconnectedFailCleanly() throws Exception {
        assertThat(core.profile().get().getErrorCode()).isEqualTo("NOT_CONNECTED");
        assertThat(core.roomSummaries().get().isSuccess()).isFalse();
    }

    @Test
    void editMessageUpdatesContentAndDeliversNewVersion() throws Exception {
        connect();
        ClientCore.SendOutcome outcome = core.sendMessage("Original content").get();
        assertThat(outcome.ok()).isTrue();
        ChatMessageDTO sent = outcome.stored();
        assertThat(texts()).contains("Original content");

        var editRes = core.editMessage(sent.getMessageId(), "Edited content").get();
        assertThat(editRes.ok()).isTrue();
        assertThat(editRes.stored().getContent()).contains("Edited content (edited)");

        // Tick to poll updates
        core.tick();
        // Since deliver re-delivers updated content to listener, shown receives the updated message
        assertThat(texts()).contains("Edited content (edited)");
    }

    @Test
    void sessionEvictionDisconnectsAndDoesNotAutoReconnect() throws Exception {
        connect();
        assertThat(core.getState()).isEqualTo(ConnectionState.CONNECTED);

        // Server evicts the session (e.g. user logged in on another machine)
        server.evict(server.sessions.keySet().iterator().next());

        // Polling tick discovers SESSION_EVICTED
        core.tick();

        assertThat(core.getState()).isEqualTo(ConnectionState.DISCONNECTED);
        assertThat(events).anyMatch(e -> e.startsWith("evicted:"));

        // Subsequent tick must NOT auto-reconnect
        core.tick();
        assertThat(core.getState()).isEqualTo(ConnectionState.DISCONNECTED);
    }
}
