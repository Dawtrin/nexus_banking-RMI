package com.nexus.banking.client.controller;

import com.nexus.banking.client.service.ChatClientService;
import com.nexus.banking.client.ui.Avatar;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;

/** Profile: display name, optional email, status, and custom/preset avatar, saved on the server. */
public class ProfilePaneController implements ScreenController {
    private static final List<String> PRESENCE = List.of("Online", "Away", "Busy");

    private final ChatClientService service;

    @FXML private StackPane avatarHolder;
    @FXML private Button uploadAvatarBtn;
    @FXML private Button resetAvatarBtn;
    @FXML private FlowPane presetContainer;
    @FXML private Label usernameLabel;
    @FXML private TextField nameField;
    @FXML private TextField emailField;
    @FXML private ComboBox<String> presenceCombo;
    @FXML private HBox errorBanner;
    @FXML private Label errorLabel;
    @FXML private Button saveButton;
    @FXML private Label resultLabel;

    private String currentAvatarData = "";

    public ProfilePaneController(ChatClientService service) {
        this.service = service;
    }

    @FXML
    public void initialize() {
        presenceCombo.getItems().addAll(PRESENCE);
        presenceCombo.getSelectionModel().select(0);
        usernameLabel.textProperty().bind(service.currentUsernameProperty());
        nameField.textProperty().addListener((o, was, now) -> updateAvatarPreview());

        // Setup preset chips
        presetContainer.getChildren().clear();
        for (Avatar.Preset p : Avatar.PRESETS) {
            Button btn = new Button();
            btn.getStyleClass().add("btn-preset-dot");
            Circle dot = new Circle(9, Color.web(p.colorHex()));
            dot.setStroke(Color.web("#2D2D38"));
            dot.setStrokeWidth(1.5);
            btn.setGraphic(dot);
            btn.setTooltip(new Tooltip(p.name()));
            btn.setOnAction(e -> {
                currentAvatarData = p.id();
                updateAvatarPreview();
            });
            presetContainer.getChildren().add(btn);
        }

        updateAvatarPreview();
    }

    private void updateAvatarPreview() {
        String name = nameField.getText() == null ? "?" : nameField.getText().trim();
        avatarHolder.getChildren().setAll(Avatar.create(name.isEmpty() ? "?" : name, currentAvatarData, 88));
    }

    @Override
    public void onShow() {
        clearError();
        resultLabel.setText("Loading your profile...");
        service.loadProfile(r -> {
            if (r.isSuccess()) {
                nameField.setText(r.getData().getDisplayName());
                emailField.setText(r.getData().getEmail());
                presenceCombo.getSelectionModel().select(MainShellController.sentence(r.getData().getStatus().name()));
                currentAvatarData = r.getData().getAvatar() == null ? "" : r.getData().getAvatar();
                updateAvatarPreview();
                resultLabel.setText("");
            } else {
                resultLabel.setText("");
                showError("Could not load the profile: " + r.getMessage());
            }
        });
    }

    @FXML
    public void onUploadAvatar() {
        clearError();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose Avatar Image (PNG, JPG, GIF)");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Image Files (*.png, *.jpg, *.jpeg, *.gif)", "*.png", "*.jpg", "*.jpeg", "*.gif"));
        File file = chooser.showOpenDialog(avatarHolder.getScene().getWindow());
        if (file == null) return;

        try {
            BufferedImage src = ImageIO.read(file);
            if (src != null) {
                int maxDim = 160;
                int sw = src.getWidth();
                int sh = src.getHeight();
                double scale = Math.min(1.0, (double) maxDim / Math.max(sw, sh));
                int tw = (int) Math.max(1, sw * scale);
                int th = (int) Math.max(1, sh * scale);
                BufferedImage scaled = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g2 = scaled.createGraphics();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.drawImage(src, 0, 0, tw, th, null);
                g2.dispose();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(scaled, "png", baos);
                currentAvatarData = "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
            } else {
                byte[] raw = Files.readAllBytes(file.toPath());
                currentAvatarData = "data:image/png;base64," + Base64.getEncoder().encodeToString(raw);
            }
            updateAvatarPreview();
            resultLabel.setText("Image loaded. Click 'Save changes' to apply.");
        } catch (Exception ex) {
            showError("Could not load image: " + ex.getMessage());
        }
    }

    @FXML
    public void onResetAvatar() {
        currentAvatarData = "";
        updateAvatarPreview();
        resultLabel.setText("Avatar reset to default initials. Click 'Save changes' to apply.");
    }

    private void showError(String m) {
        errorLabel.setText(m);
        errorBanner.setVisible(true);
        errorBanner.setManaged(true);
    }

    private void clearError() {
        errorBanner.setVisible(false);
        errorBanner.setManaged(false);
    }

    @FXML
    public void onSave() {
        clearError();
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        String email = emailField.getText() == null ? "" : emailField.getText().trim();
        if (name.length() < 2 || name.length() > 40) {
            showError("Display name must be 2 to 40 characters.");
            return;
        }
        if (!email.isEmpty() && !email.matches("^[^\\s@]{1,64}@[^\\s@]{1,120}\\.[^\\s@]{2,}$")) {
            showError("That email address does not look right. Leave it empty if you prefer not to add one.");
            return;
        }
        saveButton.setDisable(true);
        resultLabel.setText("Saving...");
        service.saveProfile(name, email, presenceCombo.getValue().toUpperCase(), currentAvatarData, r -> {
            saveButton.setDisable(false);
            if (r.isSuccess()) {
                resultLabel.setText("Saved. Avatar and name updated across rooms.");
            } else {
                resultLabel.setText("");
                showError(r.getMessage());
            }
        });
    }
}
