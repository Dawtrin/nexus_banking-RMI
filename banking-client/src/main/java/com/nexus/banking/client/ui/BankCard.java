package com.nexus.banking.client.ui;

import com.nexus.banking.contract.dto.AccountDTO;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;

/** The customer's bank card: gradient face, chip, masked account number, holder and branch. */
final class BankCard extends StackPane {
    private final Label number = new Label("•••• •••• ••••");
    private final Label holder = new Label("");
    private final Label branch = new Label("");
    private final Label status = new Label("");

    BankCard() {
        setMinSize(380, 220);
        setPrefSize(380, 220);
        setMaxSize(420, 240);
        setStyle("-fx-background-radius: 22; -fx-background-color: linear-gradient(to bottom right, #0EA5E9 0%, #6366F1 55%, #A855F7 100%);"
                + "-fx-effect: dropshadow(gaussian, rgba(99,102,241,0.45), 30, 0.2, 0, 10);");
        Pane deco = new Pane();
        deco.setMouseTransparent(true);
        Circle c1 = new Circle(330, 40, 120, Color.rgb(255, 255, 255, 0.08));
        Circle c2 = new Circle(380, 190, 90, Color.rgb(255, 255, 255, 0.07));
        deco.getChildren().addAll(c1, c2);
        Rectangle clip = new Rectangle();
        clip.setArcWidth(44);
        clip.setArcHeight(44);
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        deco.setClip(clip);

        Label brand = new Label("NEXUS BANK");
        brand.getStyleClass().add("bank-card-brand");
        Label contactless = new Label(")))");
        contactless.getStyleClass().add("bank-card-brand");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox top = new HBox(brand, grow, status);
        top.setAlignment(Pos.CENTER_LEFT);

        Region chip = new Region();
        chip.setMinSize(46, 34);
        chip.setMaxSize(46, 34);
        chip.setStyle("-fx-background-radius: 8; -fx-background-color: linear-gradient(to bottom right, #FDE68A, #F59E0B);");
        HBox chipRow = new HBox(14, chip, contactless);
        chipRow.setAlignment(Pos.CENTER_LEFT);

        number.getStyleClass().add("bank-card-number");
        Label l1 = new Label("CHỦ THẺ");
        l1.getStyleClass().add("bank-card-label");
        holder.getStyleClass().add("bank-card-name");
        Label l2 = new Label("CHI NHÁNH MỞ THẺ");
        l2.getStyleClass().add("bank-card-label");
        branch.getStyleClass().add("bank-card-name");
        VBox h = new VBox(2, l1, holder);
        VBox b = new VBox(2, l2, branch);
        Region g2 = new Region();
        HBox.setHgrow(g2, Priority.ALWAYS);
        HBox bottom = new HBox(h, g2, b);
        bottom.setAlignment(Pos.BOTTOM_LEFT);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        VBox content = new VBox(14, top, chipRow, number, spacer, bottom);
        content.setPadding(new Insets(22, 24, 20, 24));
        getChildren().addAll(deco, content);
    }

    void show(AccountDTO a) {
        if (a == null) return;
        number.setText(spaced(a.getAccountNumber()));
        if (!a.getOwnerName().isBlank()) holder.setText(a.getOwnerName().toUpperCase());
        if (!a.getBranchId().isBlank()) branch.setText("HN".equals(a.getBranchId()) ? "HÀ NỘI" : "HCM".equals(a.getBranchId()) ? "TP.HỒ CHÍ MINH" : a.getBranchId());
        status.setText(a.isActive() ? "" : "ĐÃ KHÓA");
        status.getStyleClass().setAll("bank-card-label");
    }

    private static String spaced(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && i % 4 == 0) sb.append(' ');
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }
}
