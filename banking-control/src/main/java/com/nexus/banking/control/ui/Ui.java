package com.nexus.banking.control.ui;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

/** Small shared view helpers. */
public final class Ui {
    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private Ui() {
    }

    public static String time(Instant i) {
        return i == null ? "never" : TIME.format(i);
    }

    public static Label chip(String text, String kind) {
        Label l = new Label(text);
        l.getStyleClass().addAll("chip", "chip-" + kind);
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    /** Page header band with a title, a subtitle and optional right-hand controls. */
    public static HBox header(String title, String subtitle, javafx.scene.Node... right) {
        Label t = new Label(title);
        t.getStyleClass().add("title-md");
        Label s = new Label(subtitle);
        s.getStyleClass().add("text-muted");
        VBox left = new VBox(2, t, s);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox h = new HBox(10, left, grow);
        h.getChildren().addAll(right);
        h.setAlignment(Pos.CENTER_LEFT);
        h.getStyleClass().add("band");
        h.setMinHeight(68);
        h.setPadding(new Insets(10, 24, 10, 28));
        return h;
    }

    public static <R> void column(TableView<R> table, String title, double width, Function<R, String> value) {
        TableColumn<R, String> c = new TableColumn<>(title);
        c.setCellValueFactory(x -> new ReadOnlyStringWrapper(value.apply(x.getValue())));
        c.setPrefWidth(width);
        table.getColumns().add(c);
    }

    public static <R> TableView<R> table(ObservableList<R> items, String emptyText) {
        TableView<R> t = new TableView<>(items);
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        t.setPlaceholder(new Label(emptyText));
        VBox.setVgrow(t, Priority.ALWAYS);
        return t;
    }

    public static Label muted(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("text-muted");
        l.setWrapText(true);
        return l;
    }

    public static boolean confirm(javafx.stage.Window owner, String title, String text) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(title);
        if (owner != null) a.initOwner(owner);
        a.getDialogPane().getStylesheets().addAll(owner == null || owner.getScene() == null ? java.util.List.of()
                : owner.getScene().getStylesheets());
        return a.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    public static void info(javafx.stage.Window owner, String title, String text) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK);
        a.setHeaderText(title);
        if (owner != null) a.initOwner(owner);
        a.getDialogPane().getStylesheets().addAll(owner == null || owner.getScene() == null ? java.util.List.of()
                : owner.getScene().getStylesheets());
        a.showAndWait();
    }
}
