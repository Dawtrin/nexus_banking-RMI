package com.nexus.banking.client.controller;

import com.nexus.banking.contract.dto.RoomSummaryDTO;
import com.nexus.banking.client.model.ConnectionState;
import com.nexus.banking.client.navigation.ViewId;
import com.nexus.banking.client.navigation.ViewRouter;
import com.nexus.banking.client.service.ChatClientService;
import com.nexus.banking.client.ui.Avatar;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.net.URL;
import java.util.EnumMap;
import java.util.Map;

/** The signed-in window: sidebar (identity, five screens, joined rooms) and the active screen. */
public class MainShellController {
    public enum Screen { CHAT, ROOMS, PROFILE, SETTINGS, ABOUT }

    private final ChatClientService service;
    private final ViewRouter router;
    private final Stage stage;
    private final Map<Screen, Parent> panes = new EnumMap<>(Screen.class);
    private final Map<Screen, ScreenController> controllers = new EnumMap<>(Screen.class);
    private final Map<Screen, ToggleButton> navButtons = new EnumMap<>(Screen.class);
    private Screen current;
    private ListView<String> roomList;

    @FXML private VBox sidebar;
    @FXML private StackPane content;

    public MainShellController(ChatClientService service, ViewRouter router, Stage stage) {
        this.service = service;
        this.router = router;
        this.stage = stage;
    }

    @FXML
    public void initialize() {
        buildSidebar();
        load(Screen.CHAT, "chat-pane.fxml", t -> new ChatPaneController(service, this));
        load(Screen.ROOMS, "rooms-pane.fxml", t -> new RoomsPaneController(service, this));
        load(Screen.PROFILE, "profile-pane.fxml", t -> new ProfilePaneController(service));
        load(Screen.SETTINGS, "settings-pane.fxml", t -> new SettingsPaneController(service));
        load(Screen.ABOUT, "about-pane.fxml", t -> new AboutPaneController(service));
        show(Screen.CHAT);

        stage.focusedProperty().addListener((o, was, now) -> service.windowFocusedProperty().set(now));
        service.unreadProperty().addListener((o, was, now) -> updateTitle());
        service.currentRoomIdProperty().addListener((o, was, now) -> updateTitle());
        service.evictionNoticeProperty().addListener((o, was, notice) -> {
            if (notice != null && !notice.isBlank()) {
                javafx.application.Platform.runLater(() -> {
                    service.disconnect();
                    com.nexus.banking.client.ui.ErrorDialog.show(stage, "Phiên làm việc đã bị ngắt", notice);
                    service.evictionNoticeProperty().set(null);
                    router.navigateTo(ViewId.LOGIN);
                });
            }
        });
        updateTitle();
        content.sceneProperty().addListener((o, was, scene) -> {
            if (scene == null) return;
            Screen[] all = Screen.values();
            KeyCode[] keys = {KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3, KeyCode.DIGIT4, KeyCode.DIGIT5};
            for (int i = 0; i < all.length; i++) {
                Screen s = all[i];
                scene.getAccelerators().put(new KeyCodeCombination(keys[i], KeyCombination.CONTROL_DOWN), () -> show(s));
            }
        });
    }

    private void updateTitle() {
        int n = service.unreadProperty().get();
        String room = service.currentRoomIdProperty().get();
        String prefix = n > 0 && com.nexus.banking.client.config.ClientPreferences.unreadInTitle() ? "(" + n + ") " : "";
        stage.setTitle(prefix + "NEXUS CHAT // GX EDITION" + (room.isEmpty() ? "" : " - " + room));
    }

    private void load(Screen s, String fxml, javafx.util.Callback<Class<?>, Object> factory) {
        try {
            URL url = getClass().getResource("/com/nexus/banking/client/fxml/" + fxml);
            FXMLLoader loader = new FXMLLoader(url);
            loader.setControllerFactory(factory);
            Parent root = loader.load();
            Object c = loader.getController();
            if (c instanceof ScreenController sc) controllers.put(s, sc);
            root.setVisible(false);
            root.setManaged(false);
            panes.put(s, root);
            content.getChildren().add(root);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load screen " + fxml + ": " + e.getMessage(), e);
        }
    }

    public void show(Screen target) {
        if (current != null) {
            Parent old = panes.get(current);
            old.setVisible(false);
            old.setManaged(false);
            ScreenController oc = controllers.get(current);
            if (oc != null) oc.onHide();
        }
        current = target;
        Parent p = panes.get(target);
        p.setVisible(true);
        p.setManaged(true);
        ToggleButton b = navButtons.get(target);
        if (b != null) b.setSelected(true);
        ScreenController c = controllers.get(target);
        if (c != null) c.onShow();
    }

    // ------------------------------------------------------------------ sidebar

    private void buildSidebar() {
        Label markText = new Label("N");
        markText.getStyleClass().add("brand-mark-text");
        StackPane mark = new StackPane(markText);
        mark.getStyleClass().add("brand-mark");
        mark.setMinSize(34, 34);
        mark.setMaxSize(34, 34);
        Label brand = new Label("NEXUS // GX");
        brand.getStyleClass().add("title-md");
        HBox brandRow = new HBox(10, mark, brand);
        brandRow.setAlignment(Pos.CENTER_LEFT);
        brandRow.setPadding(new Insets(18, 18, 14, 18));

        // identity
        StackPane avatarHolder = new StackPane();
        Label name = new Label();
        name.getStyleClass().add("title-md");
        name.textProperty().bind(service.displayNameProperty());
        Label presence = new Label();
        presence.getStyleClass().add("text-muted");
        presence.textProperty().bind(Bindings.createStringBinding(() -> sentence(service.presenceProperty().get()) + " on "
                + service.currentServerIdProperty().get(), service.presenceProperty(), service.currentServerIdProperty()));
        Runnable updateAvatar = () -> avatarHolder.getChildren().setAll(
                Avatar.create(service.displayNameProperty().get(), service.avatarProperty().get(), 40));
        service.displayNameProperty().addListener((o, was, now) -> updateAvatar.run());
        service.avatarProperty().addListener((o, was, now) -> updateAvatar.run());
        updateAvatar.run();
        HBox me = new HBox(12, avatarHolder, new VBox(2, name, presence));
        me.setAlignment(Pos.CENTER_LEFT);
        me.setPadding(new Insets(6, 18, 16, 18));

        // navigation
        ToggleGroup group = new ToggleGroup();
        VBox nav = new VBox(4);
        nav.setPadding(new Insets(0, 12, 0, 12));
        String[] labels = {"Chat", "Rooms and members", "Profile", "Settings", "About"};
        for (Screen s : Screen.values()) {
            ToggleButton b = new ToggleButton(labels[s.ordinal()]);
            b.getStyleClass().add("nav-item");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setToggleGroup(group);
            b.setOnAction(e -> show(s));
            b.setTooltip(new javafx.scene.control.Tooltip("Ctrl+" + (s.ordinal() + 1)));
            navButtons.put(s, b);
            nav.getChildren().add(b);
        }
        // keep the toggle from deselecting itself when clicked twice
        group.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null && was != null) was.setSelected(true);
        });

        // joined rooms
        Label roomsTitle = new Label("Your rooms");
        roomsTitle.getStyleClass().addAll("form-label");
        Button join = new Button("Join");
        join.getStyleClass().add("btn-ghost");
        join.setOnAction(e -> showJoinMenu(join));
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox roomsHead = new HBox(roomsTitle, grow, join);
        roomsHead.setAlignment(Pos.CENTER_LEFT);
        roomsHead.setPadding(new Insets(18, 14, 4, 20));

        roomList = new ListView<>(service.getJoinedRooms());
        VBox.setVgrow(roomList, Priority.ALWAYS);
        roomList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String id, boolean empty) {
                super.updateItem(id, empty);
                if (empty || id == null) {
                    setGraphic(null);
                    return;
                }
                RoomSummaryDTO r = service.getAllRooms().stream().filter(x -> x.getRoomId().equals(id)).findFirst().orElse(null);
                Label l = new Label("# " + (r == null ? id : r.getRoomName()));
                Region sp = new Region();
                HBox.setHgrow(sp, Priority.ALWAYS);
                HBox row = new HBox(8, l, sp);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(8, 14, 8, 20));
                if (r != null) {
                    Label count = new Label(String.valueOf(r.getMemberCount()));
                    count.getStyleClass().add("room-row-count");
                    row.getChildren().add(count);
                }
                if (id.equals(service.currentRoomIdProperty().get())) l.getStyleClass().addAll("text-accent", "text-violet");
                setGraphic(row);
            }
        });
        roomList.setOnMouseClicked(e -> {
            String id = roomList.getSelectionModel().getSelectedItem();
            if (id != null) {
                service.joinRoom(id);
                show(Screen.CHAT);
            }
        });
        service.getAllRooms().addListener((ListChangeListener<RoomSummaryDTO>) c -> roomList.refresh());
        service.currentRoomIdProperty().addListener((o, was, now) -> roomList.refresh());

        // connection + sign out
        Label conn = new Label();
        conn.getStyleClass().add("chip");
        Runnable paint = () -> {
            ConnectionState s = service.connectionStateProperty().get();
            conn.setText(s.getLabel());
            conn.getStyleClass().removeAll("chip-ok", "chip-warn", "chip-danger", "chip-neutral");
            conn.getStyleClass().add(switch (s) {
                case CONNECTED -> "chip-ok";
                case CONNECTING, RECONNECTING -> "chip-warn";
                case FAILED -> "chip-danger";
                default -> "chip-neutral";
            });
        };
        service.connectionStateProperty().addListener((o, was, now) -> paint.run());
        paint.run();
        Button out = new Button("Sign out");
        out.getStyleClass().add("btn-ghost");
        out.setOnAction(e -> {
            service.disconnect();
            router.navigateTo(ViewId.LOGIN);
        });
        Region g2 = new Region();
        HBox.setHgrow(g2, Priority.ALWAYS);
        HBox foot = new HBox(10, conn, g2, out);
        foot.setAlignment(Pos.CENTER_LEFT);
        foot.setPadding(new Insets(12, 14, 12, 18));
        foot.getStyleClass().add("band-top");

        sidebar.getChildren().addAll(brandRow, me, nav, roomsHead, roomList, foot);
    }

    private void showJoinMenu(Button anchor) {
        ContextMenu menu = new ContextMenu();
        for (RoomSummaryDTO r : service.getAllRooms()) {
            if (service.getJoinedRooms().contains(r.getRoomId())) continue;
            MenuItem item = new MenuItem("# " + r.getRoomName() + "   (" + r.getMemberCount() + " here)");
            item.setOnAction(e -> {
                service.joinRoom(r.getRoomId());
                show(Screen.CHAT);
            });
            menu.getItems().add(item);
        }
        if (menu.getItems().isEmpty()) {
            MenuItem none = new MenuItem(service.isConnected() ? "You have joined every room" : "Not connected");
            none.setDisable(true);
            menu.getItems().add(none);
        }
        menu.show(anchor, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    static String sentence(String enumName) {
        if (enumName == null || enumName.isEmpty()) return "";
        return enumName.charAt(0) + enumName.substring(1).toLowerCase();
    }
}
