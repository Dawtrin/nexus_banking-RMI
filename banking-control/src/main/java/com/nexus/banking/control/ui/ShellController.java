package com.nexus.banking.control.ui;

import com.nexus.banking.control.core.AdminPreferences;
import com.nexus.banking.control.core.AdminSession;
import com.nexus.banking.control.core.ClusterMonitor.NodeSnapshot;
import com.nexus.banking.control.pages.AdminPage;
import com.nexus.banking.control.pages.AuditPage;
import com.nexus.banking.control.pages.DashboardPage;
import com.nexus.banking.control.pages.LogsPage;
import com.nexus.banking.control.pages.AccountsPage;
import com.nexus.banking.control.pages.LedgerPage;
import com.nexus.banking.control.pages.ServersPage;
import com.nexus.banking.control.pages.SessionsPage;
import com.nexus.banking.control.pages.SettingsPage;
import com.nexus.banking.control.pages.TopologyPage;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/** Sidebar + pages. Refreshes both nodes on a timer and hands every page the same snapshot. */
public class ShellController {
    private final AdminSession session;
    private final Runnable onSignOut;
    private final List<AdminPage> pages = new ArrayList<>();
    private final List<ToggleButton> navButtons = new ArrayList<>();
    private AdminPage current;
    private Timeline timer;
    private final Label updated = new Label("Chưa làm mới");
    private final HBox nodeChips = new HBox(6);

    @FXML private VBox sidebar;
    @FXML private StackPane content;

    public ShellController(AdminSession session, Runnable onSignOut) {
        this.session = session;
        this.onSignOut = onSignOut;
    }

    @FXML
    public void initialize() {
        pages.add(new DashboardPage(session));
        pages.add(new TopologyPage(session));
        pages.add(new SessionsPage(session));
        pages.add(new AccountsPage(session));
        pages.add(new LedgerPage(session));
        pages.add(new AuditPage(session));
        pages.add(new LogsPage(session));
        pages.add(new ServersPage(session));
        pages.add(new SettingsPage(session, this::setInterval, onSignOut, this::refresh));
        buildSidebar();
        for (AdminPage p : pages) {
            Parent v = p.view();
            v.setVisible(false);
            v.setManaged(false);
            content.getChildren().add(v);
        }
        setInterval(AdminPreferences.refreshSeconds());
        content.sceneProperty().addListener((o, was, scene) -> {
            if (scene == null) return;
            KeyCode[] keys = {KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3, KeyCode.DIGIT4, KeyCode.DIGIT5, KeyCode.DIGIT6, KeyCode.DIGIT7, KeyCode.DIGIT8, KeyCode.DIGIT9};
            for (int i = 0; i < pages.size() && i < keys.length; i++) {
                final int idx = i;
                scene.getAccelerators().put(new KeyCodeCombination(keys[i], KeyCombination.CONTROL_DOWN), () -> show(idx));
            }
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.R, KeyCombination.CONTROL_DOWN), this::refresh);
        });
        List<NodeSnapshot> first = session.latest();
        pages.forEach(p -> p.onSnapshot(first));
        renderStatus(first);
        show(0);
    }

    private void buildSidebar() {
        Label mark = new Label("N");
        mark.getStyleClass().add("brand-mark-text");
        StackPane badge = new StackPane(mark);
        badge.getStyleClass().add("brand-mark");
        badge.setMinSize(34, 34);
        badge.setMaxSize(34, 34);
        Label brand = new Label("NEXUS BANKING // CONTROL");
        brand.getStyleClass().add("title-md");
        HBox brandRow = new HBox(10, badge, brand);
        brandRow.setAlignment(Pos.CENTER_LEFT);
        brandRow.setPadding(new Insets(18, 18, 6, 18));
        Label op = new Label("Đăng nhập: " + session.operator());
        op.getStyleClass().add("text-muted");
        op.setPadding(new Insets(0, 18, 14, 18));
        ToggleGroup group = new ToggleGroup();
        VBox nav = new VBox(4);
        nav.setPadding(new Insets(0, 12, 0, 12));
        for (int i = 0; i < pages.size(); i++) {
            final int idx = i;
            ToggleButton b = new ToggleButton(pages.get(i).title());
            b.getStyleClass().add("nav-item");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setToggleGroup(group);
            b.setOnAction(e -> show(idx));
            b.setTooltip(new javafx.scene.control.Tooltip("Ctrl+" + (i + 1)));
            navButtons.add(b);
            nav.getChildren().add(b);
        }
        group.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null && was != null) was.setSelected(true);
        });
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        Button refresh = new Button("Làm mới ngay");
        refresh.getStyleClass().add("btn-secondary");
        refresh.setMaxWidth(Double.MAX_VALUE);
        refresh.setOnAction(e -> refresh());
        updated.getStyleClass().add("text-faint");
        updated.setWrapText(true);
        VBox foot = new VBox(8, nodeChips, updated, refresh);
        foot.setPadding(new Insets(12, 14, 14, 14));
        foot.getStyleClass().add("band-top");
        sidebar.getChildren().addAll(brandRow, op, nav, grow, foot);
    }

    private void show(int idx) {
        if (current != null) {
            current.view().setVisible(false);
            current.view().setManaged(false);
        }
        current = pages.get(idx);
        current.view().setVisible(true);
        current.view().setManaged(true);
        navButtons.get(idx).setSelected(true);
        current.onShow();
        current.onSnapshot(session.latest());
    }

    private void setInterval(int seconds) {
        if (timer != null) timer.stop();
        timer = new Timeline(new KeyFrame(Duration.seconds(seconds), e -> refresh()));
        timer.setCycleCount(Animation.INDEFINITE);
        timer.play();
    }

    private void refresh() {
        var started = session.monitor().refresh(session.nodes(), session.token());
        if (started.isEmpty()) return;               // a refresh is already running: never overlap
        started.get().whenComplete((snaps, err) -> Platform.runLater(() -> {
            if (err != null || snaps == null) {
                updated.setText("Làm mới lỗi: " + (err == null ? "không có dữ liệu" : err.getMessage()));
                return;
            }
            session.setLatest(snaps);
            renderStatus(snaps);
            pages.forEach(p -> p.onSnapshot(snaps));
        }));
    }

    private void renderStatus(List<NodeSnapshot> snaps) {
        nodeChips.getChildren().clear();
        for (NodeSnapshot s : snaps) {
            nodeChips.getChildren().add(Ui.chip(s.endpoint().label().replace("Node ", "") + (s.ok() ? " ok" : s.stale() ? " cũ" : " mất"),
                    s.ok() ? "ok" : "danger"));
        }
        updated.setText(snaps.isEmpty() ? "Chưa làm mới" : "Cập nhật " + Ui.time(java.time.Instant.now()));
    }

    public void stop() {
        if (timer != null) timer.stop();
    }
}
