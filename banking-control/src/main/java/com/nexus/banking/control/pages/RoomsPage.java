package com.nexus.banking.control.pages;

import com.nexus.banking.control.core.AdminSession;
import com.nexus.banking.control.ui.Ui;
import com.nexus.banking.contract.dto.MemberDTO;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.RoomSummaryDTO;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Create, rename, delete and inspect rooms. Every change is applied to both nodes and reported per node. */
public class RoomsPage implements AdminPage {
    record Row(String id, String name, String description, String members, String messages, String presence) {
    }

    private final AdminSession session;
    private final VBox root = new VBox();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = Ui.table(rows, "No rooms loaded yet.");
    private final Label status = new Label();

    public RoomsPage(AdminSession session) {
        this.session = session;
        Ui.column(table, "Room", 170, Row::name);
        Ui.column(table, "Id", 120, Row::id);
        Ui.column(table, "People here (A / B)", 140, Row::members);
        Ui.column(table, "Messages stored (A / B)", 160, Row::messages);
        Ui.column(table, "Exists on", 120, Row::presence);
        Ui.column(table, "Description", 300, Row::description);
        Button create = new Button("Create room");
        create.getStyleClass().add("btn-primary");
        create.setOnAction(e -> edit(null));
        Button edit = new Button("Edit");
        edit.getStyleClass().add("btn-secondary");
        edit.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        edit.setOnAction(e -> edit(table.getSelectionModel().getSelectedItem()));
        Button people = new Button("Who is in the room");
        people.getStyleClass().add("btn-secondary");
        people.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        people.setOnAction(e -> showPeople(table.getSelectionModel().getSelectedItem()));
        Button delete = new Button("Delete");
        delete.getStyleClass().add("btn-danger");
        delete.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        delete.setOnAction(e -> delete(table.getSelectionModel().getSelectedItem()));
        Button reload = new Button("Reload");
        reload.getStyleClass().add("btn-ghost");
        reload.setOnAction(e -> onShow());
        status.getStyleClass().add("text-muted");
        status.setWrapText(true);
        VBox body = new VBox(12, new HBox(10, create, edit, people, delete, reload), status, table);
        body.setPadding(new Insets(18, 28, 22, 28));
        VBox.setVgrow(body, javafx.scene.layout.Priority.ALWAYS);
        root.getChildren().addAll(Ui.header("Rooms", "Rooms exist on each server separately; changes here go to both"), body);
    }

    @Override
    public String title() { return "Rooms"; }

    @Override
    public Parent view() { return root; }

    @Override
    public void onShow() {
        status.setText("Loading...");
        session.background().execute(() -> {
            Map<String, OperationResult<List<RoomSummaryDTO>>> res = session.monitor().onAllNodes(session.nodes(), r -> r.admin().listRooms(session.token()));
            Map<String, String[]> merged = new TreeMap<>();
            StringBuilder problems = new StringBuilder();
            int idx = 0;
            for (var e : res.entrySet()) {
                if (!e.getValue().isSuccess()) {
                    problems.append(e.getKey()).append(": ").append(e.getValue().getMessage()).append("   ");
                } else {
                    for (RoomSummaryDTO r : e.getValue().getData()) {
                        String[] m = merged.computeIfAbsent(r.getRoomId(), k -> new String[]{r.getRoomName(), r.getDescription(), "-", "-", "-", "-", ""});
                        m[idx == 0 ? 2 : 3] = String.valueOf(r.getMemberCount());
                        m[idx == 0 ? 4 : 5] = String.valueOf(r.getMessageCount());
                        m[6] += (m[6].isEmpty() ? "" : " and ") + (idx == 0 ? "A" : "B");
                    }
                }
                idx++;
            }
            List<Row> list = merged.entrySet().stream().map(en -> {
                String[] m = en.getValue();
                return new Row(en.getKey(), m[0], m[1] == null ? "" : m[1], m[2] + " / " + m[3], m[4] + " / " + m[5],
                        m[6].equals("A and B") ? "Both servers" : "Only " + m[6]);
            }).toList();
            Platform.runLater(() -> {
                rows.setAll(list);
                status.setText(problems.length() == 0 ? list.size() + " rooms" : "Some servers did not answer - " + problems);
            });
        });
    }

    private void edit(Row existing) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(existing == null ? "Create room" : "Edit room");
        d.initOwner(root.getScene().getWindow());
        d.getDialogPane().getStylesheets().addAll(root.getScene().getStylesheets());
        TextField id = new TextField(existing == null ? "" : existing.id());
        id.setPromptText("letters, digits, - and _");
        id.setDisable(existing != null);
        TextField name = new TextField(existing == null ? "" : existing.name());
        TextField desc = new TextField(existing == null ? "" : existing.description());
        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(10);
        g.setPadding(new Insets(16));
        g.addRow(0, new Label("Room id"), id);
        g.addRow(1, new Label("Name"), name);
        g.addRow(2, new Label("Description"), desc);
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            String rid = id.getText().trim();
            boolean create = existing == null;
            session.background().execute(() -> {
                var res = session.monitor().onAllNodes(session.nodes(), r -> create
                        ? r.admin().createRoom(session.token(), session.operator(), rid, name.getText(), desc.getText())
                        : r.admin().updateRoom(session.token(), session.operator(), rid, name.getText(), desc.getText()));
                report(create ? "Create " + rid : "Update " + rid, res);
            });
        });
    }

    private void delete(Row r) {
        if (r == null) return;
        if (!Ui.confirm(root.getScene().getWindow(), "Delete room " + r.name() + "?",
                "Only rooms without any stored message can be deleted. People in the room are removed from it."
                        + "\nThis is recorded in the audit log.")) return;
        session.background().execute(() -> report("Delete " + r.id(),
                session.monitor().onAllNodes(session.nodes(), x -> x.admin().deleteRoom(session.token(), session.operator(), r.id()))));
    }

    private void report(String what, Map<String, OperationResult<Void>> res) {
        StringBuilder sb = new StringBuilder(what + " - ");
        res.forEach((node, r) -> sb.append(node).append(": ").append(r.isSuccess() ? "done" : r.getMessage()).append("   "));
        Platform.runLater(() -> {
            status.setText(sb.toString().trim());
            onShow();
        });
    }

    private void showPeople(Row r) {
        if (r == null) return;
        session.background().execute(() -> {
            var res = session.monitor().onAllNodes(session.nodes(), x -> x.admin().getRoomMembers(session.token(), r.id()));
            StringBuilder sb = new StringBuilder();
            res.forEach((node, m) -> {
                sb.append(node).append(":\n");
                if (!m.isSuccess()) sb.append("   ").append(m.getMessage()).append("\n");
                else if (m.getData().isEmpty()) sb.append("   nobody right now\n");
                else for (MemberDTO x : m.getData()) sb.append("   ").append(x.getDisplayName()).append(" (@").append(x.getUsername()).append(", ")
                        .append(x.getStatus().name().toLowerCase()).append(")\n");
            });
            Platform.runLater(() -> Ui.info(root.getScene().getWindow(), "People in " + r.name(), sb.toString()));
        });
    }
}
