package com.nexus.banking.client.controller;

import com.nexus.banking.contract.dto.MemberDTO;
import com.nexus.banking.contract.dto.RoomSummaryDTO;
import com.nexus.banking.client.service.ChatClientService;
import com.nexus.banking.client.ui.Avatar;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.paint.Color;
import javafx.util.Duration;

/** Every room with live member counts, join/leave, and the people currently in the selected room. */
public class RoomsPaneController implements ScreenController {
    private final ChatClientService service;
    private final MainShellController shell;
    private final ObservableList<MemberDTO> members = FXCollections.observableArrayList();
    private Timeline refresh;
    private RoomSummaryDTO selected;
    private String selectedId;

    @FXML private Label roomsHint;
    @FXML private ListView<RoomSummaryDTO> roomList;
    @FXML private Button joinButton;
    @FXML private Button leaveButton;
    @FXML private Label membersTitle;
    @FXML private Label membersHint;
    @FXML private ListView<MemberDTO> memberList;
    @FXML private VBox membersEmpty;
    @FXML private Label membersEmptyTitle;
    @FXML private Label membersEmptyText;

    public RoomsPaneController(ChatClientService service, MainShellController shell) {
        this.service = service;
        this.shell = shell;
    }

    @FXML
    public void initialize() {
        roomList.setItems(service.getAllRooms());
        roomList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(RoomSummaryDTO r, boolean empty) {
                super.updateItem(r, empty);
                if (empty || r == null) {
                    setGraphic(null);
                    return;
                }
                Label name = new Label("# " + r.getRoomName());
                name.getStyleClass().add("title-md");
                Label desc = new Label(r.getDescription() == null || r.getDescription().isBlank() ? "No description" : r.getDescription());
                desc.getStyleClass().add("text-muted");
                desc.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
                desc.setMinWidth(0);
                name.setMinWidth(0);
                VBox text = new VBox(2, name, desc);
                text.setMinWidth(0);
                Region grow = new Region();
                HBox.setHgrow(text, Priority.ALWAYS);
                Label count = new Label(r.getMemberCount() + (r.getMemberCount() == 1 ? " here" : " here"));
                count.getStyleClass().addAll("chip", "chip-neutral");
                count.setMinWidth(Region.USE_PREF_SIZE);
                HBox row = new HBox(10, text, grow);
                if (service.getJoinedRooms().contains(r.getRoomId())) {
                    Label joined = new Label("Joined");
                    joined.getStyleClass().addAll("chip", "chip-ok");
                    joined.setMinWidth(Region.USE_PREF_SIZE);
                    row.getChildren().add(joined);
                }
                row.getChildren().add(count);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(10, 20, 10, 24));
                setPrefWidth(0);
                setGraphic(row);
            }
        });
        // Replacing the list items clears the ListView selection; the remembered id keeps the user's choice.
        roomList.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (now == null) return;
            selectedId = now.getRoomId();
            selected = now;
            updateSelection();
        });
        service.getAllRooms().addListener((ListChangeListener<RoomSummaryDTO>) c -> {
            roomsHint.setText(service.getAllRooms().size() + " rooms on this server");
            if (selectedId != null) {
                service.getAllRooms().stream().filter(r -> r.getRoomId().equals(selectedId)).findFirst().ifPresentOrElse(r -> {
                    selected = r;
                    roomList.getSelectionModel().select(r);
                }, () -> selected = null);
            }
            roomList.refresh();
            updateSelection();
        });
        service.getJoinedRooms().addListener((ListChangeListener<String>) c -> {
            roomList.refresh();
            updateSelection();
        });
        memberList.setItems(members);
        memberList.setSelectionModel(null);
        memberList.setFocusTraversable(false);
        memberList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(MemberDTO m, boolean empty) {
                super.updateItem(m, empty);
                if (empty || m == null) {
                    setGraphic(null);
                    return;
                }
                Label name = new Label(m.getDisplayName());
                name.getStyleClass().add("title-md");
                Label sub = new Label("@" + m.getUsername() + "  on " + m.getServerId());
                sub.getStyleClass().addAll("text-faint");
                Circle dot = new Circle(5, switch (m.getStatus()) {
                    case ONLINE -> Color.web("#22C55E");
                    case AWAY -> Color.web("#F59E0B");
                    case BUSY -> Color.web("#FA174A");
                });
                Label status = new Label(MainShellController.sentence(m.getStatus().name()));
                status.getStyleClass().add("text-muted");
                Region grow = new Region();
                HBox.setHgrow(grow, Priority.ALWAYS);
                HBox row = new HBox(12, Avatar.create(m.getDisplayName(), m.getAvatar(), 38), new VBox(2, name, sub), grow, dot, status);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(8, 24, 8, 24));
                setGraphic(row);
            }
        });
        refresh = new Timeline(new KeyFrame(Duration.seconds(4), e -> {
            service.refreshRooms(null);
            loadMembers();
        }));
        refresh.setCycleCount(Animation.INDEFINITE);
        roomsHint.setText("Loading rooms...");
        updateSelection();
    }

    @Override
    public void onShow() {
        service.refreshRooms(null);
        if (selectedId == null && !service.getAllRooms().isEmpty()) {
            String cur = service.currentRoomIdProperty().get();
            RoomSummaryDTO pick = service.getAllRooms().stream().filter(r -> r.getRoomId().equals(cur)).findFirst()
                    .orElse(service.getAllRooms().get(0));
            roomList.getSelectionModel().select(pick);
        }
        refresh.play();
        loadMembers();
    }

    @Override
    public void onHide() {
        refresh.stop();
    }

    private boolean joined() {
        return selected != null && service.getJoinedRooms().contains(selected.getRoomId());
    }

    private void updateSelection() {
        joinButton.setDisable(selected == null || joined() || !service.isConnected());
        leaveButton.setDisable(!joined());
        if (selected == null) {
            membersTitle.setText("People");
            membersHint.setText("Select a room to see who is in it");
            showEmpty("Select a room", "Choose a room on the left to see the people in it.");
            return;
        }
        membersTitle.setText("People in # " + selected.getRoomName());
        if (!joined()) {
            membersHint.setText("Members are visible to people who joined the room");
            members.clear();
            showEmpty("Join this room to see who is in it", "Only members can see each other. Press Join room.");
        } else {
            loadMembers();
        }
    }

    private void showEmpty(String title, String text) {
        membersEmptyTitle.setText(title);
        membersEmptyText.setText(text);
        membersEmpty.setVisible(true);
        membersEmpty.setManaged(true);
        memberList.setVisible(false);
    }

    private void loadMembers() {
        if (selected == null || !joined()) return;
        final String id = selected.getRoomId();
        membersHint.setText("Loading...");
        service.loadMembers(id, r -> {
            if (selected == null || !selected.getRoomId().equals(id)) return;
            if (!r.isSuccess()) {
                showEmpty("Could not load the people list", r.getMessage());
                membersHint.setText("");
                return;
            }
            members.setAll(r.getData());
            membersHint.setText(members.size() + (members.size() == 1 ? " person here now" : " people here now") + " on this server");
            boolean none = members.isEmpty();
            membersEmpty.setVisible(none);
            membersEmpty.setManaged(none);
            memberList.setVisible(!none);
            if (none) {
                membersEmptyTitle.setText("Nobody here right now");
                membersEmptyText.setText("People appear while they are signed in to this server.");
            }
        });
    }

    @FXML
    public void onJoin() {
        if (selected == null) return;
        service.joinRoom(selected.getRoomId());
        shell.show(MainShellController.Screen.CHAT);
    }

    @FXML
    public void onLeave() {
        if (selected != null) service.leaveRoom(selected.getRoomId(), r -> updateSelection());
    }
}
