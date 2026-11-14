package com.nexus.banking.client.controller;

import com.nexus.banking.contract.dto.RoomSummaryDTO;
import com.nexus.banking.client.model.ChatMessageModel;
import com.nexus.banking.client.model.ConnectionState;
import com.nexus.banking.client.service.ChatClientService;
import com.nexus.banking.client.ui.Avatar;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/** The conversation: history, composer, file attachments, find-in-room, leave room. */
public class ChatPaneController implements ScreenController {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final String[] EMOJI = {"😀", "😂", "😊", "😍", "👍", "🙏", "🎉", "🔥", "🤔", "😅", "👋", "✅", "❤️", "💡", "🚀", "😢"};
    private static final long MAX_FILE_BYTES = 10 * 1024 * 1024; // 10 MB limit

    private record PendingAttachment(String fileName, long fileSize, String base64Data) {}

    private final ChatClientService service;
    private final MainShellController shell;
    private FilteredList<ChatMessageModel> filtered;
    private PendingAttachment pendingAttachment = null;

    @FXML private Label roomTitle;
    @FXML private Label roomSubtitle;
    @FXML private ToggleButton findButton;
    @FXML private Button membersButton;
    @FXML private MenuButton moreButton;
    @FXML private HBox searchBar;
    @FXML private TextField searchField;
    @FXML private Label searchResult;
    @FXML private HBox notice;
    @FXML private Label noticeLabel;
    @FXML private ListView<ChatMessageModel> messageList;
    @FXML private VBox emptyState;
    @FXML private Label noMessages;
    @FXML private HBox attachmentBar;
    @FXML private Label attachmentName;
    @FXML private Label attachmentSize;
    @FXML private Button removeAttachmentBtn;
    @FXML private Button attachButton;
    @FXML private TextArea input;
    @FXML private MenuButton emojiButton;
    @FXML private Button sendButton;
    @FXML private Label counter;

    public ChatPaneController(ChatClientService service, MainShellController shell) {
        this.service = service;
        this.shell = shell;
    }

    @FXML
    public void initialize() {
        filtered = new FilteredList<>(service.getMessages(), m -> true);
        messageList.setItems(filtered);
        messageList.setSelectionModel(null);
        messageList.setFocusTraversable(false);
        messageList.setCellFactory(lv -> new MessageCell());
        messageList.setPlaceholder(new Label(""));
        filtered.addListener((ListChangeListener<ChatMessageModel>) c -> {
            Platform.runLater(() -> {
                if (!filtered.isEmpty()) messageList.scrollTo(filtered.size() - 1);
                refreshEmpty();
            });
        });
        searchField.textProperty().addListener((o, was, now) -> applyFilter());

        service.currentRoomIdProperty().addListener((o, was, now) -> refreshHeader());
        service.getAllRooms().addListener((ListChangeListener<RoomSummaryDTO>) c -> refreshHeader());
        service.connectionStateProperty().addListener((o, was, now) -> refreshNotice());
        service.statusMessageProperty().addListener((o, was, now) -> refreshNotice());

        for (String e : EMOJI) {
            MenuItem item = new MenuItem(e);
            item.setOnAction(a -> {
                input.insertText(input.getCaretPosition(), e);
                input.requestFocus();
            });
            emojiButton.getItems().add(item);
        }
        emojiButton.setText("Emoji");
        input.textProperty().addListener((o, was, now) -> updateComposer());
        refreshHeader();
        refreshNotice();
        updateComposer();
    }

    @Override
    public void onShow() {
        Platform.runLater(() -> input.requestFocus());
    }

    // ------------------------------------------------------------------ header

    private void refreshHeader() {
        String id = service.currentRoomIdProperty().get();
        boolean inRoom = !id.isEmpty();
        emptyState.setVisible(!inRoom);
        emptyState.setManaged(!inRoom);
        messageList.setVisible(inRoom);
        input.setDisable(!inRoom);
        findButton.setDisable(!inRoom);
        moreButton.setDisable(!inRoom);
        if (attachButton != null) attachButton.setDisable(!inRoom);
        if (!inRoom) {
            roomTitle.setText("No room open");
            roomSubtitle.setText("Join a room to see its messages");
            updateComposer();
            return;
        }
        RoomSummaryDTO r = service.getAllRooms().stream().filter(x -> x.getRoomId().equals(id)).findFirst().orElse(null);
        roomTitle.setText("# " + (r == null ? id : r.getRoomName()));
        roomSubtitle.setText(r == null ? "" : r.getMemberCount() + (r.getMemberCount() == 1 ? " person here now" : " people here now")
                + (r.getDescription() == null || r.getDescription().isBlank() ? "" : "  -  " + r.getDescription()));
        refreshEmpty();
        updateComposer();
    }

    private void refreshEmpty() {
        boolean inRoom = !service.currentRoomIdProperty().get().isEmpty();
        boolean none = inRoom && service.getMessages().isEmpty();
        noMessages.setVisible(none);
        noMessages.setManaged(none);
    }

    private void refreshNotice() {
        ConnectionState s = service.connectionStateProperty().get();
        boolean show = s != ConnectionState.CONNECTED;
        notice.setVisible(show);
        notice.setManaged(show);
        noticeLabel.setText(service.statusMessageProperty().get());
        notice.getStyleClass().removeAll("banner-warn", "banner-error");
        notice.getStyleClass().add(s == ConnectionState.FAILED ? "banner-error" : "banner-warn");
        updateComposer();
    }

    // ------------------------------------------------------------------ find

    @FXML
    public void onToggleFind() {
        boolean on = findButton.isSelected();
        searchBar.setVisible(on);
        searchBar.setManaged(on);
        if (on) searchField.requestFocus();
        else searchField.clear();
    }

    private void applyFilter() {
        String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        filtered.setPredicate(m -> q.isEmpty() || m.getContent().toLowerCase().contains(q) || m.getSenderName().toLowerCase().contains(q));
        searchResult.setText(q.isEmpty() ? "" : filtered.size() + " of " + service.getMessages().size() + " match");
    }

    // ------------------------------------------------------------------ attachments

    @FXML
    public void onAttachFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select File to Attach (Max 10 MB)");
        File file = chooser.showOpenDialog(input.getScene().getWindow());
        if (file == null) return;
        if (file.length() > MAX_FILE_BYTES) {
            notice.setVisible(true);
            notice.setManaged(true);
            noticeLabel.setText("File is too large: maximum allowed size is 10 MB.");
            return;
        }
        attachmentBar.setVisible(true);
        attachmentBar.setManaged(true);
        attachmentName.setText(file.getName() + " (Loading...)");
        attachmentSize.setText(formatSize(file.length()));
        sendButton.setDisable(true);

        CompletableFuture.supplyAsync(() -> {
            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                String b64 = Base64.getEncoder().encodeToString(bytes);
                return new PendingAttachment(file.getName(), file.length(), b64);
            } catch (Exception ex) {
                return null;
            }
        }).thenAccept(att -> Platform.runLater(() -> {
            if (att != null) {
                pendingAttachment = att;
                attachmentName.setText(att.fileName());
                attachmentSize.setText(formatSize(att.fileSize()));
            } else {
                attachmentBar.setVisible(false);
                attachmentBar.setManaged(false);
                pendingAttachment = null;
                notice.setVisible(true);
                notice.setManaged(true);
                noticeLabel.setText("Failed to read the selected file.");
            }
            updateComposer();
        }));
    }

    @FXML
    public void onRemoveAttachment() {
        pendingAttachment = null;
        attachmentBar.setVisible(false);
        attachmentBar.setManaged(false);
        updateComposer();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static boolean isImageFile(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".gif") || n.endsWith(".webp") || n.endsWith(".bmp");
    }

    private static String getFileIcon(String name) {
        if (name == null) return "📁";
        String n = name.toLowerCase();
        if (n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z") || n.endsWith(".tar") || n.endsWith(".gz")) return "📦";
        if (n.endsWith(".pdf")) return "📕";
        if (n.endsWith(".doc") || n.endsWith(".docx") || n.endsWith(".txt") || n.endsWith(".md")) return "📄";
        if (n.endsWith(".exe") || n.endsWith(".msi") || n.endsWith(".jar") || n.endsWith(".bat") || n.endsWith(".sh")) return "⚙";
        if (n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".ogg")) return "🎵";
        if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi")) return "🎬";
        return "📁";
    }

    // ------------------------------------------------------------------ actions

    @FXML
    public void onMembers() {
        shell.show(MainShellController.Screen.ROOMS);
    }

    @FXML
    public void onLeave() {
        String id = service.currentRoomIdProperty().get();
        if (!id.isEmpty()) service.leaveRoom(id, null);
    }

    @FXML
    public void onRefresh() {
        service.refreshRooms(null);
    }

    @FXML
    public void onKey(KeyEvent e) {
        if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
            e.consume();
            onSend();
        }
    }

    private void updateComposer() {
        String t = input.getText() == null ? "" : input.getText();
        int len = t.trim().length();
        counter.setText(t.length() + " / 4000");
        boolean hasContent = len > 0 || pendingAttachment != null;
        sendButton.setDisable(!hasContent || t.length() > 4000 || !service.isConnected() || service.currentRoomIdProperty().get().isEmpty());
    }

    @FXML
    public void onSend() {
        String text = input.getText() == null ? "" : input.getText().trim();
        if (sendButton.isDisabled()) return;
        String fullContent;
        if (pendingAttachment != null) {
            String fileToken = "[NEXUS_FILE:name=" + pendingAttachment.fileName()
                    + ";size=" + pendingAttachment.fileSize()
                    + ";data=" + pendingAttachment.base64Data() + "]";
            fullContent = text.isEmpty() ? fileToken : text + "\n" + fileToken;
        } else {
            if (text.isEmpty()) return;
            fullContent = text;
        }
        sendButton.setDisable(true);
        service.sendMessage(fullContent, outcome -> {
            if (outcome.ok()) {
                input.clear();
                onRemoveAttachment();
            }
            updateComposer();
            input.requestFocus();
        });
    }

    // ------------------------------------------------------------------ cell

    private final class MessageCell extends ListCell<ChatMessageModel> {
        @Override
        protected void updateItem(ChatMessageModel m, boolean empty) {
            super.updateItem(m, empty);
            if (empty || m == null) {
                setGraphic(null);
                return;
            }
            boolean own = m.isOwnMessage();
            Label name = new Label(own ? "You" : m.getSenderName());
            name.getStyleClass().add("msg-name");
            name.setTextFill(Avatar.colorFor(m.getSenderName()).brighter());
            Label time = new Label(TIME.format(m.getCreatedAt()));
            time.getStyleClass().add("msg-meta");
            HBox head = new HBox(8, name, time);
            head.setAlignment(Pos.BASELINE_LEFT);
            String here = service.currentServerIdProperty().get();
            if (!m.getOriginServerId().equals(here)) {
                Label via = new Label("via " + m.getOriginServerId());
                via.getStyleClass().add("via-tag");
                head.getChildren().add(via);
            }

            Node body = buildContentNode(m);
            VBox bubble = new VBox(4, head, body);
            bubble.getStyleClass().add(own ? "msg-bubble-own" : "msg-bubble");
            if (own && !m.getContent().contains("[NEXUS_FILE:")) {
                ContextMenu menu = new ContextMenu();
                MenuItem editItem = new MenuItem("✏️ Sửa tin nhắn (Edit)");
                editItem.setOnAction(e -> promptEdit(m));
                menu.getItems().add(editItem);
                bubble.setOnContextMenuRequested(e -> menu.show(bubble, e.getScreenX(), e.getScreenY()));
                bubble.setOnMouseClicked(e -> {
                    if (e.getClickCount() == 2) promptEdit(m);
                });
            }
            HBox row = new HBox(10);
            row.setPadding(new Insets(6, 24, 6, 24));
            if (own) {
                Region grow = new Region();
                HBox.setHgrow(grow, Priority.ALWAYS);
                row.getChildren().addAll(grow, bubble,
                        Avatar.create(service.displayNameProperty().get(), service.avatarProperty().get(), 36));
                row.setAlignment(Pos.TOP_RIGHT);
            } else {
                row.getChildren().addAll(Avatar.create(m.getSenderName(), m.getSenderAvatar(), 36), bubble);
                row.setAlignment(Pos.TOP_LEFT);
            }
            setGraphic(row);
        }

        private void promptEdit(ChatMessageModel m) {
            String cleanContent = m.getContent();
            if (cleanContent.endsWith(" (edited)")) {
                cleanContent = cleanContent.substring(0, cleanContent.length() - " (edited)".length());
            }
            TextInputDialog dialog = new TextInputDialog(cleanContent);
            dialog.setTitle("Edit Message");
            dialog.setHeaderText("Sửa tin nhắn của bạn:");
            dialog.setContentText("Nội dung mới:");
            if (messageList.getScene() != null && messageList.getScene().getWindow() != null) {
                dialog.initOwner(messageList.getScene().getWindow());
                dialog.getDialogPane().getStylesheets().addAll(messageList.getScene().getStylesheets());
            }
            dialog.getDialogPane().getStyleClass().add("dialog-pane");

            java.util.Optional<String> res = dialog.showAndWait();
            final String original = cleanContent;
            res.ifPresent(newText -> {
                if (!newText.trim().isEmpty() && !newText.trim().equals(original)) {
                    service.editMessage(m.getMessageId(), newText.trim());
                }
            });
        }

        private Node buildContentNode(ChatMessageModel m) {
            String raw = m.getContent();
            int fileStart = raw.indexOf("[NEXUS_FILE:");
            if (fileStart == -1) {
                Label text = new Label();
                text.textProperty().bind(m.contentProperty());
                text.setWrapText(true);
                text.getStyleClass().add("msg-text");
                text.maxWidthProperty().bind(Bindings.max(220, messageList.widthProperty().subtract(190)));
                return text;
            }

            int fileEnd = raw.indexOf("]", fileStart);
            if (fileEnd == -1) fileEnd = raw.length();

            String token = raw.substring(fileStart + "[NEXUS_FILE:".length(), fileEnd);
            String preText = raw.substring(0, fileStart).trim();
            String postText = (fileEnd < raw.length()) ? raw.substring(fileEnd + 1).trim() : "";

            String fileName = "attachment";
            long fileSize = 0;
            String fileData = "";

            for (String part : token.split(";")) {
                int eq = part.indexOf("=");
                if (eq > 0) {
                    String k = part.substring(0, eq).trim();
                    String v = part.substring(eq + 1).trim();
                    if ("name".equalsIgnoreCase(k)) fileName = v;
                    else if ("size".equalsIgnoreCase(k)) {
                        try { fileSize = Long.parseLong(v); } catch (NumberFormatException ignored) {}
                    }
                    else if ("data".equalsIgnoreCase(k)) fileData = v;
                }
            }

            VBox container = new VBox(6);
            container.maxWidthProperty().bind(Bindings.max(260, messageList.widthProperty().subtract(190)));

            if (!preText.isEmpty()) {
                Label pre = new Label(preText);
                pre.setWrapText(true);
                pre.getStyleClass().add("msg-text");
                pre.maxWidthProperty().bind(container.maxWidthProperty());
                container.getChildren().add(pre);
            }

            Node fileCard = createFileCard(fileName, fileSize, fileData);
            container.getChildren().add(fileCard);

            if (!postText.isEmpty()) {
                Label post = new Label(postText);
                post.setWrapText(true);
                post.getStyleClass().add("msg-text");
                post.maxWidthProperty().bind(container.maxWidthProperty());
                container.getChildren().add(post);
            }

            return container;
        }

        private Node createFileCard(String fileName, long fileSize, String base64Data) {
            boolean isImg = isImageFile(fileName);
            VBox card = new VBox(8);
            card.getStyleClass().add("file-card");
            card.setPadding(new Insets(10));

            if (isImg) {
                try {
                    byte[] bytes = Base64.getMimeDecoder().decode(base64Data);
                    Image img = new Image(new ByteArrayInputStream(bytes), 280, 200, true, true);
                    if (!img.isError() && img.getWidth() > 0) {
                        ImageView iv = new ImageView(img);
                        iv.setFitWidth(240);
                        iv.setPreserveRatio(true);
                        iv.setSmooth(true);
                        StackPane imgPane = new StackPane(iv);
                        imgPane.getStyleClass().add("file-thumbnail");
                        card.getChildren().add(imgPane);
                    }
                } catch (Exception ignored) {}
            }

            HBox infoRow = new HBox(10);
            infoRow.setAlignment(Pos.CENTER_LEFT);

            Label icon = new Label(isImg ? "🖼" : getFileIcon(fileName));
            icon.setStyle("-fx-font-size: 20px;");

            VBox meta = new VBox(2);
            Label nameLabel = new Label(fileName);
            nameLabel.getStyleClass().add("file-name");
            Label sizeLabel = new Label(formatSize(fileSize));
            sizeLabel.getStyleClass().add("file-size");
            meta.getChildren().addAll(nameLabel, sizeLabel);

            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);

            Button actionBtn = new Button(isImg ? "💾 Save" : "⬇ Download");
            actionBtn.getStyleClass().add("btn-download");
            actionBtn.setOnAction(e -> downloadFile(fileName, base64Data, actionBtn));

            infoRow.getChildren().addAll(icon, meta, grow, actionBtn);
            card.getChildren().add(infoRow);
            return card;
        }

        private void downloadFile(String defaultName, String base64Data, Button btn) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save File As");
            chooser.setInitialFileName(defaultName);
            javafx.stage.Window window = getScene() != null ? getScene().getWindow() :
                    (messageList.getScene() != null ? messageList.getScene().getWindow() : null);
            File dest = chooser.showSaveDialog(window);
            if (dest == null) return;

            btn.setText("Saving...");
            btn.setDisable(true);
            CompletableFuture.runAsync(() -> {
                try {
                    byte[] bytes = Base64.getMimeDecoder().decode(base64Data);
                    Files.write(dest.toPath(), bytes);
                    Platform.runLater(() -> {
                        btn.setText("✓ Saved");
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        btn.setText("Error");
                        btn.setDisable(false);
                    });
                }
            });
        }
    }
}
