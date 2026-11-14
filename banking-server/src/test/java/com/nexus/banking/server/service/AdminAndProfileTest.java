package com.nexus.banking.server.service;

import com.nexus.banking.contract.dto.AuditEntryDTO;
import com.nexus.banking.contract.dto.ChatMessageDTO;
import com.nexus.banking.contract.dto.ErrorCodes;
import com.nexus.banking.contract.dto.MemberDTO;
import com.nexus.banking.contract.dto.RoomSummaryDTO;
import com.nexus.banking.contract.dto.UserAdminDTO;
import com.nexus.banking.contract.dto.UserProfileDTO;
import com.nexus.banking.contract.enums.MessageType;
import com.nexus.banking.server.support.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAndProfileTest {
    private static final String T = Node.ADMIN_TOKEN;
    private Node a;

    @BeforeEach
    void setUp() {
        a = Node.ram("server-a", "server-b");
    }

    @AfterEach
    void tearDown() {
        a.close();
    }

    // ------------------------------------------------------------- chat side

    @Test
    void roomSummariesCountDistinctUsersAndMembersAreListed() {
        String alice = a.login("alice", "instance-aaaa-0001", "general");
        a.login("alice", "instance-bbbb-0002", "general");     // same user, second device: still one member
        String bob = a.login("bobby", "instance-cccc-0003", "general");
        a.chat.joinRoom(bob, "random");
        List<RoomSummaryDTO> rooms = a.chat.getRoomSummaries(alice).getData();
        assertThat(rooms).filteredOn(r -> r.getRoomId().equals("general")).extracting(RoomSummaryDTO::getMemberCount).containsExactly(2);
        assertThat(rooms).filteredOn(r -> r.getRoomId().equals("random")).extracting(RoomSummaryDTO::getMemberCount).containsExactly(1);
        List<MemberDTO> members = a.chat.getRoomMembers(alice, "general").getData();
        assertThat(members).extracting(MemberDTO::getUsername).containsExactly("alice", "bobby");
        assertThat(members.get(0).getServerId()).isEqualTo("server-a");
    }

    @Test
    void membersOfARoomAreOnlyVisibleToMembers() {
        String s = a.chat.registerClient("carol", "instance-dddd-0004").getData().getSessionId();
        assertThat(a.chat.getRoomMembers(s, "general").getErrorCode()).isEqualTo(ErrorCodes.NOT_IN_ROOM);
        assertThat(a.chat.getRoomMembers("nope", "general").getErrorCode()).isEqualTo(ErrorCodes.INVALID_SESSION);
    }

    @Test
    void leavingARoomRemovesMembershipAndBlocksFurtherAccess() {
        String s = a.login("alice");
        assertThat(a.chat.leaveRoom(s, "general").isSuccess()).isTrue();
        assertThat(a.chat.sendMessage(s, "general", UUID.randomUUID().toString(), "x").getErrorCode()).isEqualTo(ErrorCodes.NOT_IN_ROOM);
        assertThat(a.sessions.memberCount("general")).isZero();
    }

    @Test
    void profileUpdateIsValidatedAndShowsUpInMessagesAndMembers() throws Exception {
        String s = a.login("alice");
        assertThat(a.chat.updateProfile(s, "A", "", "ONLINE").getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);
        assertThat(a.chat.updateProfile(s, "Alice A", "not-an-email", "ONLINE").getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);
        assertThat(a.chat.updateProfile(s, "Alice A", "", "SLEEPING").getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);
        UserProfileDTO p = a.chat.updateProfile(s, "Alice Anderson", "alice@example.com", "away").getData();
        assertThat(p.getDisplayName()).isEqualTo("Alice Anderson");
        assertThat(a.chat.getProfile(s).getData().getEmail()).isEqualTo("alice@example.com");
        a.send(s, "hi");
        ChatMessageDTO m = a.chat.getHistory(s, "general", 10).getData().getMessages().get(0);
        assertThat(m.getSenderName()).isEqualTo("Alice Anderson");
        assertThat(a.chat.getRoomMembers(s, "general").getData().get(0).getStatus().name()).isEqualTo("AWAY");
        // the profile survives a new login of the same username
        String again = a.login("alice", "instance-zzzz-0009", "general");
        assertThat(a.chat.getProfile(again).getData().getDisplayName()).isEqualTo("Alice Anderson");
    }

    @Test
    void replicatedMessageForAnUnknownRoomCreatesTheRoomOnTheReceiver() {
        ChatMessageDTO m = new ChatMessageDTO("id-room", "new-room", "s", "n", "hello", Instant.now(), "server-b", 1, MessageType.CHAT, 0);
        var ack = a.repl.replicateBatch("server-b", Node.SECRET, List.of(m)).getData();
        assertThat(ack.getAcceptedMessageIds()).containsExactly("id-room");
        String s = a.login("alice");
        assertThat(a.chat.getRoomSummaries(s).getData()).extracting(RoomSummaryDTO::getRoomId).contains("new-room");
    }

    // ------------------------------------------------------------ admin side

    @Test
    void everyAdminCallRequiresTheToken() {
        assertThat(a.admin.listRooms("bad").getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(a.admin.createRoom("bad", "op", "r1", "R1", "").getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(a.admin.updateRoom("bad", "op", "general", "G", "").getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(a.admin.deleteRoom("bad", "op", "random").getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(a.admin.getRoomMembers("bad", "general").getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(a.admin.listUsers("bad").getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(a.admin.disconnectSession("bad", "op", "12345678").getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(a.admin.getAuditLog("bad", 10).getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(a.admin.requeueDeadLetters("bad", "op").getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(a.admin.syncNow("bad", "op").getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(a.admin.getServerConfig("bad").getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(a.monitoring.getThroughput("bad", 5).getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
    }

    @Test
    void roomLifecycleIsValidatedAuditedAndNonEmptyRoomsCannotBeDeleted() throws Exception {
        assertThat(a.admin.createRoom(T, "", "dev", "Development", "").getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);  // operator required
        assertThat(a.admin.createRoom(T, "op", "bad id", "Dev", "").getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);
        assertThat(a.admin.createRoom(T, "op", "dev", "", "").getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);
        assertThat(a.admin.createRoom(T, "op", "dev", "Development", "Dev talk").isSuccess()).isTrue();
        assertThat(a.admin.createRoom(T, "op", "dev", "Again", "").isSuccess()).isFalse();           // duplicate
        assertThat(a.admin.updateRoom(T, "op", "dev", "Dev Team", "Renamed").isSuccess()).isTrue();
        assertThat(a.admin.updateRoom(T, "op", "ghost", "x", "").getErrorCode()).isEqualTo(ErrorCodes.ROOM_NOT_FOUND);
        String s = a.login("alice", "instance-aaaa-0001", "dev");
        a.chat.sendMessage(s, "dev", UUID.randomUUID().toString(), "first");
        assertThat(a.admin.deleteRoom(T, "op", "dev").isSuccess()).isFalse();                         // has messages
        assertThat(a.admin.createRoom(T, "op", "empty", "Empty", "").isSuccess()).isTrue();
        a.chat.joinRoom(s, "empty");
        assertThat(a.admin.deleteRoom(T, "op", "empty").isSuccess()).isTrue();
        assertThat(a.chat.sendMessage(s, "empty", UUID.randomUUID().toString(), "x").getErrorCode()).isEqualTo(ErrorCodes.ROOM_NOT_FOUND);
        List<RoomSummaryDTO> rooms = a.admin.listRooms(T).getData();
        assertThat(rooms).filteredOn(r -> r.getRoomId().equals("dev")).extracting(RoomSummaryDTO::getRoomName).containsExactly("Dev Team");
        assertThat(rooms).filteredOn(r -> r.getRoomId().equals("dev")).extracting(RoomSummaryDTO::getMessageCount).containsExactly(1L);
        List<AuditEntryDTO> log = a.admin.getAuditLog(T, 50).getData();
        assertThat(log).extracting(AuditEntryDTO::getAction).contains("CREATE_ROOM", "UPDATE_ROOM", "DELETE_ROOM");
        assertThat(log).filteredOn(e -> e.getAction().equals("DELETE_ROOM") && e.getTarget().equals("dev"))
                .extracting(AuditEntryDTO::getResult).containsExactly("REJECTED");
        assertThat(log).allMatch(e -> e.getActor().equals("op") && e.getServerId().equals("server-a"));
    }

    @Test
    void usersSessionsAndAuthFailuresAreVisibleToAdmins() {
        String s = a.login("alice");
        a.login("bobby");
        List<UserAdminDTO> users = a.admin.listUsers(T).getData();
        assertThat(users).extracting(UserAdminDTO::getUsername).contains("alice", "bobby");
        assertThat(users).filteredOn(u -> u.getUsername().equals("alice")).allMatch(UserAdminDTO::isOnline);
        assertThat(a.admin.disconnectSession(T, "op", s).isSuccess()).isTrue();
        assertThat(a.chat.heartbeat(s).getErrorCode()).isEqualTo(ErrorCodes.INVALID_SESSION);
        assertThat(a.admin.listUsers(T).getData()).filteredOn(u -> u.getUsername().equals("alice")).noneMatch(UserAdminDTO::isOnline);
        assertThat(a.admin.disconnectSession(T, "op", "00000000-0000-0000-0000-000000000000").isSuccess()).isFalse();
        a.monitoring.getServerStatus("wrong-token-000000");
        assertThat(a.admin.getAuditLog(T, 50).getData()).extracting(AuditEntryDTO::getAction).contains("DISCONNECT_SESSION", "ADMIN_AUTH");
        assertThat(a.admin.getAuditLog(T, 0).getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);
    }

    @Test
    void serverConfigNeverContainsSecrets() {
        Map<String, String> cfg = a.admin.getServerConfig(T).getData();
        assertThat(cfg).containsEntry("server.id", "server-a").containsEntry("admin.api", "enabled");
        String all = cfg.toString();
        assertThat(all).doesNotContain(Node.SECRET).doesNotContain(Node.ADMIN_TOKEN);
        assertThat(cfg.keySet()).noneMatch(k -> k.contains("password") || k.contains("secret") || k.contains("token"));
    }

    @Test
    void throughputCountsIndividualMessagesPerBucket() {
        String s = a.login("alice");
        for (int i = 0; i < 5; i++) a.send(s, "m" + i);
        var points = a.monitoring.getThroughput(T, 5).getData();
        assertThat(points).hasSize(30);                                    // 5 min / 10 s
        assertThat(points.stream().mapToInt(p -> p.getStored()).sum()).isEqualTo(5);
        assertThat(a.monitoring.getThroughput(T, 0).getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);
    }

    @Test
    void syncNowIsAuditedWithTheOperatorName() {
        assertThat(a.admin.syncNow(T, "operator-1").isSuccess()).isTrue();
        assertThat(a.admin.getAuditLog(T, 10).getData().get(0).getActor()).isEqualTo("operator-1");
    }
}
