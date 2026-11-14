package com.nexus.banking.client.service;

import com.nexus.banking.contract.dto.ChatMessageDTO;
import com.nexus.banking.contract.dto.ChatRoomDTO;
import com.nexus.banking.contract.dto.ClientSessionDTO;
import com.nexus.banking.contract.dto.MemberDTO;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.RoomSummaryDTO;
import com.nexus.banking.contract.dto.UserProfileDTO;
import com.nexus.banking.client.config.ClientConfig;
import com.nexus.banking.client.config.ClientPreferences;
import com.nexus.banking.client.core.ClientCore;
import com.nexus.banking.client.model.ChatMessageModel;
import com.nexus.banking.client.model.ConnectionState;
import com.nexus.banking.client.rmi.RmiClientManager;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.util.Duration;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * JavaFX adapter around {@link ClientCore}: observable state for the views, always updated on the FX thread.
 * All connection logic lives in the core; this class only mirrors state and forwards user actions.
 */
public class ChatClientService implements AutoCloseable {
    private final ObjectProperty<ConnectionState> connectionState = new SimpleObjectProperty<>(ConnectionState.DISCONNECTED);
    private final StringProperty currentServerId = new SimpleStringProperty("-");
    private final StringProperty currentUsername = new SimpleStringProperty("");
    private final StringProperty displayName = new SimpleStringProperty("");
    private final StringProperty presence = new SimpleStringProperty("ONLINE");
    private final StringProperty avatar = new SimpleStringProperty("");
    private final StringProperty currentRoomId = new SimpleStringProperty("");
    private final StringProperty statusMessage = new SimpleStringProperty("Ready to connect");
    private final IntegerProperty unread = new SimpleIntegerProperty(0);
    private final BooleanProperty windowFocused = new SimpleBooleanProperty(true);
    private final StringProperty evictionNotice = new SimpleStringProperty(null);
    private final ObservableList<RoomSummaryDTO> allRooms = FXCollections.observableArrayList();
    private final ObservableList<String> joinedRooms = FXCollections.observableArrayList();
    private final ObservableList<ChatMessageModel> messages = FXCollections.observableArrayList();
    private final ClientCore core;
    private final Timeline roomRefresh;

    public ChatClientService() {
        this.core = ClientCore.start(RmiClientManager::connect, new ClientCore.Listener() {
            @Override
            public void onState(ConnectionState state, String status) {
                Platform.runLater(() -> {
                    connectionState.set(state);
                    statusMessage.set(status);
                });
            }

            @Override
            public void onSession(String serverId, String username) {
                Platform.runLater(() -> {
                    currentServerId.set(serverId);
                    currentUsername.set(username);
                    if (displayName.get().isEmpty()) displayName.set(username);
                });
            }

            @Override
            public void onRooms(List<ChatRoomDTO> dtos) {
                // the richer summaries (member counts) are loaded separately
            }

            @Override
            public void onRoomChanged(String roomId) {
                Platform.runLater(() -> {
                    currentRoomId.set(roomId == null ? "" : roomId);
                    messages.clear();
                    if (roomId != null && !joinedRooms.contains(roomId)) joinedRooms.add(roomId);
                });
            }

            @Override
            public void onMessages(String roomId, List<ChatMessageDTO> dtos) {
                Platform.runLater(() -> {
                    if (!roomId.equals(currentRoomId.get())) return;      // stale delivery for a room we left
                    boolean foreign = false;
                    for (ChatMessageDTO dto : dtos) {
                        boolean own = core.isOwn(dto);
                        insertSorted(new ChatMessageModel(dto, own));
                        foreign |= !own;
                    }
                    if (foreign && !windowFocused.get()) {
                        unread.set(unread.get() + 1);
                        if (ClientPreferences.beepOnMessage()) beep();
                    }
                });
            }

            @Override
            public void onEvicted(String reason) {
                Platform.runLater(() -> {
                    evictionNotice.set(reason != null ? reason : "Tài khoản của bạn đã được đăng nhập từ một thiết bị khác.");
                });
            }
        }, ClientConfig.DEFAULT_POLL_INTERVAL_MS);
        core.setAutoReconnect(ClientPreferences.autoReconnect());
        roomRefresh = new Timeline(new KeyFrame(Duration.seconds(5), e -> refreshRooms(null)));
        roomRefresh.setCycleCount(Animation.INDEFINITE);
        windowFocused.addListener((o, was, now) -> {
            if (now) unread.set(0);
        });
    }

    private static void beep() {
        try {
            if (!java.awt.GraphicsEnvironment.isHeadless()) java.awt.Toolkit.getDefaultToolkit().beep();
        } catch (Throwable ignored) {
            // no AWT audio available: the setting simply has no effect on this machine
        }
    }

    /** Inserts in display order (createdAt, origin server id, origin sequence). */
    private void insertSorted(ChatMessageModel model) {
        for (ChatMessageModel existing : messages) {
            if (existing.getMessageId().equals(model.getMessageId())) {
                existing.contentProperty().set(model.getContent());
                return;
            }
        }
        int lo = 0, hi = messages.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ChatMessageModel.DISPLAY_ORDER.compare(messages.get(mid), model) <= 0) lo = mid + 1;
            else hi = mid;
        }
        messages.add(lo, model);
    }

    public void editMessage(String messageId, String newText) {
        editMessage(messageId, newText, null);
    }

    public void editMessage(String messageId, String newText, Consumer<ClientCore.SendOutcome> onComplete) {
        core.editMessage(messageId, newText).thenAccept(outcome -> Platform.runLater(() -> {
            if (!outcome.ok()) statusMessage.set(outcome.message());
            if (onComplete != null) onComplete.accept(outcome);
        }));
    }

    public void connectAndRegister(String host, int port, String username, Consumer<OperationResult<ClientSessionDTO>> onComplete) {
        joinedRooms.clear();
        displayName.set("");
        core.connect(host, port, username).thenAccept(result -> Platform.runLater(() -> {
            if (result.isSuccess()) {
                roomRefresh.play();
                refreshRooms(null);
                loadProfile(null);
            }
            if (onComplete != null) onComplete.accept(result);
        }));
    }

    // ------------------------------------------------------------- user actions

    public void joinRoom(String roomId) {
        core.joinRoom(roomId).thenRun(() -> Platform.runLater(() -> refreshRooms(null)));
    }

    public void leaveRoom(String roomId, Consumer<OperationResult<Void>> onComplete) {
        core.leaveRoom(roomId).thenAccept(r -> Platform.runLater(() -> {
            if (r.isSuccess()) {
                joinedRooms.remove(roomId);
                refreshRooms(null);
            } else {
                statusMessage.set("Could not leave the room: " + r.getMessage());
            }
            if (onComplete != null) onComplete.accept(r);
        }));
    }

    public void refreshRooms(Consumer<OperationResult<List<RoomSummaryDTO>>> onComplete) {
        core.roomSummaries().thenAccept(r -> Platform.runLater(() -> {
            if (r.isSuccess()) allRooms.setAll(r.getData());
            if (onComplete != null) onComplete.accept(r);
        }));
    }

    public void loadMembers(String roomId, Consumer<OperationResult<List<MemberDTO>>> onComplete) {
        core.members(roomId).thenAccept(r -> Platform.runLater(() -> onComplete.accept(r)));
    }

    public void loadProfile(Consumer<OperationResult<UserProfileDTO>> onComplete) {
        core.profile().thenAccept(r -> Platform.runLater(() -> {
            if (r.isSuccess()) applyProfile(r.getData());
            if (onComplete != null) onComplete.accept(r);
        }));
    }

    public void saveProfile(String name, String email, String presenceValue, Consumer<OperationResult<UserProfileDTO>> onComplete) {
        saveProfile(name, email, presenceValue, avatar.get(), onComplete);
    }

    public void saveProfile(String name, String email, String presenceValue, String avatarData, Consumer<OperationResult<UserProfileDTO>> onComplete) {
        core.updateProfile(name, email, presenceValue, avatarData).thenAccept(r -> Platform.runLater(() -> {
            if (r.isSuccess()) {
                com.nexus.banking.client.config.ClientPreferences.setAvatar(currentUsername.get(), avatarData);
                applyProfile(r.getData());
            }
            if (onComplete != null) onComplete.accept(r);
        }));
    }

    private void applyProfile(UserProfileDTO p) {
        displayName.set(p.getDisplayName());
        presence.set(p.getStatus().name());
        String av = p.getAvatar();
        if (av == null || av.isBlank()) {
            av = com.nexus.banking.client.config.ClientPreferences.avatar(currentUsername.get());
        }
        avatar.set(av == null ? "" : av);
    }

    public void sendMessage(String content, Consumer<ClientCore.SendOutcome> onComplete) {
        core.sendMessage(content).thenAccept(outcome -> Platform.runLater(() -> {
            if (!outcome.ok()) statusMessage.set(outcome.message());
            if (onComplete != null) onComplete.accept(outcome);
        }));
    }

    public void setAutoReconnect(boolean enabled) {
        core.setAutoReconnect(enabled);
    }

    public void disconnect() {
        roomRefresh.stop();
        core.disconnect().thenRun(() -> Platform.runLater(() -> {
            allRooms.clear();
            joinedRooms.clear();
            messages.clear();
            unread.set(0);
        }));
    }

    public boolean isConnected() {
        return core.getState() == ConnectionState.CONNECTED;
    }

    @Override
    public void close() {
        roomRefresh.stop();
        core.close();
    }

    public ObjectProperty<ConnectionState> connectionStateProperty() { return connectionState; }
    public StringProperty currentServerIdProperty() { return currentServerId; }
    public StringProperty currentUsernameProperty() { return currentUsername; }
    public StringProperty displayNameProperty() { return displayName; }
    public StringProperty avatarProperty() { return avatar; }
    public StringProperty presenceProperty() { return presence; }
    public StringProperty currentRoomIdProperty() { return currentRoomId; }
    public StringProperty statusMessageProperty() { return statusMessage; }
    public IntegerProperty unreadProperty() { return unread; }
    public BooleanProperty windowFocusedProperty() { return windowFocused; }
    public StringProperty evictionNoticeProperty() { return evictionNotice; }
    public ObservableList<RoomSummaryDTO> getAllRooms() { return allRooms; }
    public ObservableList<String> getJoinedRooms() { return joinedRooms; }
    public ObservableList<ChatMessageModel> getMessages() { return messages; }
}
