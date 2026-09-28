package com.filebridge.client.controller;

import com.filebridge.client.service.LocalizationService;
import com.filebridge.client.service.NetworkUtils;
import com.filebridge.client.service.PeerClient;
import com.filebridge.client.service.ThemeService;
import com.filebridge.server.service.PinService;
import com.filebridge.server.service.ServerPortService;
import com.filebridge.server.service.SettingsService;
import com.filebridge.server.service.ShareService;
import com.filebridge.shared.model.NetworkInterfaceInfo;
import com.filebridge.shared.model.ShareDuration;
import com.filebridge.shared.model.SharedFolder;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.NodeOrientation;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
public class MainController {

    // Header
    @FXML private Parent root;
    @FXML private SplitPane splitPane;
    @FXML private Label appTitleLabel;
    @FXML private Button languageBtn;
    @FXML private Button themeBtn;
    @FXML private Label pinTitleLabel;
    @FXML private Label pinLabel;
    @FXML private Button generatePinBtn;
    @FXML private Label myIpLabel;
    @FXML private ComboBox<NetworkInterfaceInfo> ipCombo;
    @FXML private Label portTitleLabel;
    @FXML private TextField portField;
    @FXML private Button applyPortBtn;
    @FXML private Label portHintLabel;

    // My shares
    @FXML private Label sharesTitleLabel;
    @FXML private Label sharesLegendLabel;
    @FXML private ListView<SharedFolder> sharesList;
    @FXML private Button addShareBtn;
    @FXML private Button toggleWritableBtn;
    @FXML private Button removeShareBtn;
    @FXML private Label sharesTipLabel;

    // Remote
    @FXML private Label remoteTitleLabel;
    @FXML private TextField remoteHostField;
    @FXML private TextField remotePortField;
    @FXML private PasswordField remotePinField;
    @FXML private Button connectBtn;
    @FXML private Label remoteSharesTitleLabel;
    @FXML private ListView<PeerClient.RemoteShare> remoteSharesList;
    @FXML private TableView<PeerClient.RemoteEntry> remoteEntriesTable;
    @FXML private TableColumn<PeerClient.RemoteEntry, String> nameCol;
    @FXML private TableColumn<PeerClient.RemoteEntry, String> sizeCol;
    @FXML private Label remotePathLabel;
    @FXML private Button upBtn;
    @FXML private Button downloadBtn;
    @FXML private Button uploadBtn;
    @FXML private Button deleteRemoteBtn;

    // Status
    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;

    private final ShareService shareService;
    private final PinService pinService;
    private final SettingsService settingsService;
    private final ServerPortService serverPortService;
    private final NetworkUtils networkUtils;
    private final PeerClient peerClient;
    private final LocalizationService i18n;
    private final ThemeService themeService;
    private final ApplicationContext appContext;

    @Value("${server.port:8080}")
    private int defaultServerPort;

    private String remoteHost;
    private int remotePort;
    private String remotePin;
    private PeerClient.RemoteShare currentShare;
    private final Deque<String> remotePathStack = new ArrayDeque<>();

    public MainController(ShareService shareService, PinService pinService,
                          SettingsService settingsService, ServerPortService serverPortService,
                          NetworkUtils networkUtils, PeerClient peerClient,
                          LocalizationService i18n, ThemeService themeService,
                          ApplicationContext appContext) {
        this.shareService = shareService;
        this.pinService = pinService;
        this.settingsService = settingsService;
        this.serverPortService = serverPortService;
        this.networkUtils = networkUtils;
        this.peerClient = peerClient;
        this.i18n = i18n;
        this.themeService = themeService;
        this.appContext = appContext;
    }

    @FXML
    public void initialize() {
        if ("ar".equals(settingsService.loadLanguage())) i18n.setLocale(new Locale("ar"));

        // IP dropdown
        List<NetworkInterfaceInfo> ifs = networkUtils.listInterfaces();
        ipCombo.setItems(FXCollections.observableArrayList(ifs));
        NetworkInterfaceInfo defaultIp = networkUtils.pickDefault(ifs, settingsService.loadPreferredIp());
        ipCombo.getSelectionModel().select(defaultIp);
        ipCombo.valueProperty().addListener((obs, old, sel) -> {
            if (sel != null) settingsService.savePreferredIp(sel.ipAddress());
        });

        // Port editor - shows the LIVE bound port; Apply rebinds Tomcat immediately.
        portField.setText(String.valueOf(getLivePort()));
        applyPortBtn.setOnAction(e -> applyPort());

        refreshPinLabel();
        generatePinBtn.setOnAction(e -> {
            pinService.issuePin();
            refreshPinLabel();
            setStatus(i18n.t("status.newPin"));
        });

        languageBtn.setOnAction(e -> toggleLanguage());
        themeBtn.setOnAction(e -> toggleTheme());

        // My shares
        sharesList.setItems(shareService.observableShares());
        addShareBtn.setOnAction(e -> openAddShareDialog());
        toggleWritableBtn.setOnAction(e -> {
            SharedFolder sel = sharesList.getSelectionModel().getSelectedItem();
            if (sel != null) shareService.setWritable(sel.getId(), !sel.isWritable());
        });
        removeShareBtn.setOnAction(e -> {
            SharedFolder sel = sharesList.getSelectionModel().getSelectedItem();
            if (sel != null) shareService.removeShare(sel.getId());
        });

        // Remote
        remotePortField.setText("8080");
        connectBtn.setOnAction(e -> connectToPeer());

        remoteSharesList.getSelectionModel().selectedItemProperty().addListener((obs, old, share) -> {
            if (share != null) openRemoteShare(share);
            updateRemoteButtonsEnabled();
        });

        nameCol.setCellValueFactory(c -> {
            PeerClient.RemoteEntry e = c.getValue();
            return new SimpleStringProperty((e.directory() ? "📁  " : "📄  ") + e.name());
        });
        sizeCol.setCellValueFactory(c -> {
            PeerClient.RemoteEntry e = c.getValue();
            return new SimpleStringProperty(e.directory() ? "" : humanSize(e.size()));
        });

        remoteEntriesTable.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                PeerClient.RemoteEntry sel = remoteEntriesTable.getSelectionModel().getSelectedItem();
                if (sel != null && sel.directory()) descendInto(sel.name());
            }
        });
        remoteEntriesTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updateRemoteButtonsEnabled());

        upBtn.setOnAction(e -> ascend());
        downloadBtn.setOnAction(e -> downloadSelected());
        uploadBtn.setOnAction(e -> upload());
        deleteRemoteBtn.setOnAction(e -> deleteRemoteSelected());
        updateRemoteButtonsEnabled();

        progressBar.setProgress(0);

        Platform.runLater(() -> {
            applyLanguage();
            themeService.apply(root.getScene());
            updateThemeButtonLabel();
        });
    }

    // ---------- Add share dialog with duration + writable options ----------

    private void openAddShareDialog() {
        // 1) Pick folder
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(i18n.t("dialog.addShare.chooseFolder"));
        File selected = chooser.showDialog(addShareBtn.getScene().getWindow());
        if (selected == null) return;

        // 2) Custom dialog for duration + writable
        Dialog<AddShareResult> dialog = new Dialog<>();
        dialog.setTitle(i18n.t("dialog.addShare.title"));
        dialog.setHeaderText(i18n.t("dialog.addShare.header", selected.getName()));

        ButtonType okBtn = new ButtonType(i18n.t("common.ok"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okBtn, ButtonType.CANCEL);

        Label durLabel = new Label(i18n.t("dialog.addShare.duration"));
        ComboBox<ShareDuration> durationCombo = new ComboBox<>();
        durationCombo.getItems().addAll(ShareDuration.values());
        durationCombo.getSelectionModel().select(ShareDuration.HOUR_1);

        CheckBox writableCheck = new CheckBox(i18n.t("dialog.addShare.writable"));
        writableCheck.setSelected(false);

        javafx.scene.layout.VBox vbox = new javafx.scene.layout.VBox(10);
        vbox.setPadding(new javafx.geometry.Insets(10));
        vbox.getChildren().addAll(durLabel, durationCombo, writableCheck);
        dialog.getDialogPane().setContent(vbox);

        dialog.setResultConverter(bt ->
                bt == okBtn ? new AddShareResult(durationCombo.getValue(), writableCheck.isSelected()) : null);

        Optional<AddShareResult> result = dialog.showAndWait();
        if (result.isEmpty()) return;

        ShareDuration dur = result.get().duration;
        boolean writable = result.get().writable;

        Instant expiresAt = dur.isForever() ? null : Instant.now().plus(dur.getDuration());
        shareService.addShare(selected.getName(), selected.toPath(), writable, expiresAt);
        setStatus(i18n.t(writable ? "status.shareWritable" : "status.shareReadOnly", selected.getAbsolutePath()));
    }

    private record AddShareResult(ShareDuration duration, boolean writable) {}

    // ---------- Language & Theme ----------

    private void toggleLanguage() {
        Locale next = "ar".equals(i18n.getLocale().getLanguage()) ? Locale.ENGLISH : new Locale("ar");
        i18n.setLocale(next);
        settingsService.saveLanguage(next.getLanguage());
        applyLanguage();
    }

    private void toggleTheme() {
        themeService.toggle(root.getScene());
        updateThemeButtonLabel();
    }

    private void updateThemeButtonLabel() {
        themeBtn.setText(themeService.getCurrent() == ThemeService.Theme.DARK
                ? i18n.t("header.theme.light") : i18n.t("header.theme.dark"));
    }

    private void applyLanguage() {
        appTitleLabel.setText(i18n.t("app.title"));
        if (root.getScene() != null && root.getScene().getWindow() instanceof javafx.stage.Stage s) {
            s.setTitle(i18n.t("app.title"));
        }
        languageBtn.setText("ar".equals(i18n.getLocale().getLanguage()) ? "English" : "العربية");
        pinTitleLabel.setText(i18n.t("header.accessPin"));
        generatePinBtn.setText(i18n.t("header.generatePin"));
        myIpLabel.setText(i18n.t("header.myIp"));
        portTitleLabel.setText(i18n.t("header.port"));
        updateThemeButtonLabel();

        sharesTitleLabel.setText(i18n.t("shares.title"));
        sharesLegendLabel.setText(i18n.t("shares.legend"));
        addShareBtn.setText(i18n.t("shares.addShare"));
        toggleWritableBtn.setText(i18n.t("shares.toggleWritable"));
        removeShareBtn.setText(i18n.t("shares.remove"));
        sharesTipLabel.setText(i18n.t("shares.tip"));

        remoteTitleLabel.setText(i18n.t("remote.title"));
        remoteHostField.setPromptText(i18n.t("remote.host"));
        remotePortField.setPromptText(i18n.t("remote.port"));
        remotePinField.setPromptText(i18n.t("remote.pin"));
        connectBtn.setText(i18n.t("remote.connect"));
        remoteSharesTitleLabel.setText(i18n.t("remote.shares"));
        upBtn.setText(i18n.t("remote.up"));
        downloadBtn.setText(i18n.t("remote.download"));
        uploadBtn.setText(i18n.t("remote.upload"));
        deleteRemoteBtn.setText(i18n.t("remote.removeRemote"));

        statusLabel.setText(i18n.t("status.ready"));

        if (root.getScene() != null) {
            root.getScene().setNodeOrientation(
                    i18n.isRtl() ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
        }
    }

    // ---------- Port change ----------

    private void applyPort() {
        String text = portField.getText().trim();
        int newPort;
        try {
            newPort = Integer.parseInt(text);
            if (newPort < 1 || newPort > 65535) throw new NumberFormatException();
        } catch (NumberFormatException nfe) {
            portHintLabel.setText(i18n.t("status.invalidPort"));
            new Alert(Alert.AlertType.ERROR, i18n.t("status.invalidPort")).showAndWait();
            return;
        }

        int current = getLivePort();
        if (newPort == current) {
            portHintLabel.setText(i18n.t("port.alreadyOn", newPort));
            return;
        }

        if (!serverPortService.isPortAvailable(newPort)) {
            portHintLabel.setText("");
            new Alert(Alert.AlertType.ERROR, i18n.t("port.inUse", newPort)).showAndWait();
            return;
        }

        // Run rebind on a background thread so the UI stays responsive
        portHintLabel.setText(i18n.t("port.applying", newPort));
        Task<Integer> task = new Task<>() {
            @Override protected Integer call() {
                return serverPortService.changePort(newPort);
            }
        };
        task.setOnSucceeded(e -> {
            int result = task.getValue();
            if (result == newPort) {
                portField.setText(String.valueOf(result));
                portHintLabel.setText(i18n.t("port.applied", result));
                setStatus(i18n.t("port.applied", result));
            } else {
                portField.setText(String.valueOf(current));
                portHintLabel.setText("");
                new Alert(Alert.AlertType.ERROR, i18n.t("port.failed", newPort)).showAndWait();
            }
        });
        task.setOnFailed(e -> {
            portField.setText(String.valueOf(current));
            portHintLabel.setText("");
            new Alert(Alert.AlertType.ERROR,
                    i18n.t("port.failed", newPort) + "\n" + task.getException().getMessage()).showAndWait();
        });
        runInBackground(task);
    }

    // ---------- Server info ----------

    private int getLivePort() {
        if (appContext instanceof WebServerApplicationContext wsac && wsac.getWebServer() != null) {
            return wsac.getWebServer().getPort();
        }
        return defaultServerPort;
    }

    private void refreshPinLabel() {
        pinLabel.setText(pinService.currentPin().orElse("—"));
    }

    // ---------- Remote browsing ----------

    private void connectToPeer() {
        String host = remoteHostField.getText().trim();
        String portText = remotePortField.getText().trim();
        String pin = remotePinField.getText().trim();
        if (host.isEmpty() || portText.isEmpty() || pin.isEmpty()) {
            setStatus(i18n.t("status.enterAll"));
            return;
        }
        int port;
        try { port = Integer.parseInt(portText); }
        catch (NumberFormatException nfe) { setStatus(i18n.t("status.invalidPort")); return; }

        this.remoteHost = host;
        this.remotePort = port;
        this.remotePin = pin;

        Task<List<PeerClient.RemoteShare>> task = new Task<>() {
            @Override protected List<PeerClient.RemoteShare> call() throws Exception {
                return peerClient.listShares(host, port, pin);
            }
        };
        task.setOnSucceeded(e -> {
            remoteSharesList.getItems().setAll(task.getValue());
            setStatus(i18n.t("status.connected", host, port, task.getValue().size()));
        });
        task.setOnFailed(e -> setStatus(i18n.t("status.connectFailed", task.getException().getMessage())));
        runInBackground(task);
    }

    private void openRemoteShare(PeerClient.RemoteShare share) {
        currentShare = share;
        remotePathStack.clear();
        loadCurrentRemoteFolder();
    }

    private void descendInto(String folderName) {
        remotePathStack.push(folderName);
        loadCurrentRemoteFolder();
    }

    private void ascend() {
        if (!remotePathStack.isEmpty()) { remotePathStack.pop(); loadCurrentRemoteFolder(); }
    }

    private String currentRemotePath() {
        if (remotePathStack.isEmpty()) return "";
        List<String> list = new ArrayList<>(remotePathStack);
        Collections.reverse(list);
        return String.join("/", list);
    }

    private void loadCurrentRemoteFolder() {
        if (currentShare == null) return;
        String path = currentRemotePath();
        remotePathLabel.setText("/" + path);

        Task<List<PeerClient.RemoteEntry>> task = new Task<>() {
            @Override protected List<PeerClient.RemoteEntry> call() throws Exception {
                return peerClient.listFolder(remoteHost, remotePort, remotePin, currentShare.id(), path);
            }
        };
        task.setOnSucceeded(e -> remoteEntriesTable.getItems().setAll(task.getValue()));
        task.setOnFailed(e -> setStatus(i18n.t("status.connectFailed", task.getException().getMessage())));
        runInBackground(task);
    }

    // ---------- Download / Upload / Delete ----------

    private void downloadSelected() {
        PeerClient.RemoteEntry sel = remoteEntriesTable.getSelectionModel().getSelectedItem();
        if (sel == null || sel.directory()) { setStatus(i18n.t("status.selectFile")); return; }

        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName(sel.name());
        File target = chooser.showSaveDialog(downloadBtn.getScene().getWindow());
        if (target == null) return;

        String remoteFilePath = currentRemotePath().isEmpty() ? sel.name() : currentRemotePath() + "/" + sel.name();
        Path local = target.toPath();
        long total = sel.size();

        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                peerClient.downloadFile(remoteHost, remotePort, remotePin,
                        currentShare.id(), remoteFilePath, local,
                        bytes -> Platform.runLater(() -> {
                            if (total > 0) progressBar.setProgress((double) bytes / total);
                            setStatus(i18n.t("status.downloading", sel.name(), humanSize(bytes), humanSize(total)));
                        }));
                return null;
            }
        };
        task.setOnSucceeded(e -> { progressBar.setProgress(1); setStatus(i18n.t("status.downloadDone", sel.name(), local)); });
        task.setOnFailed(e -> setStatus(i18n.t("status.downloadFailed", task.getException().getMessage())));
        runInBackground(task);
    }

    private void upload() {
        if (currentShare == null) { setStatus(i18n.t("status.enterAll")); return; }
        if (!currentShare.writable()) {
            Alert a = new Alert(Alert.AlertType.WARNING, i18n.t("dialog.readonly.body"));
            a.setTitle(i18n.t("dialog.readonly.title"));
            a.showAndWait();
            return;
        }

        ButtonType fileBtn = new ButtonType(i18n.t("dialog.upload.file"), ButtonBar.ButtonData.OK_DONE);
        ButtonType folderBtn = new ButtonType(i18n.t("dialog.upload.folder"), ButtonBar.ButtonData.OTHER);
        ButtonType cancelBtn = new ButtonType(i18n.t("dialog.upload.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert ask = new Alert(Alert.AlertType.CONFIRMATION);
        ask.setTitle(i18n.t("dialog.upload.title"));
        ask.setHeaderText(i18n.t("dialog.upload.header"));
        ask.setContentText(i18n.t("dialog.upload.content"));
        ask.getButtonTypes().setAll(fileBtn, folderBtn, cancelBtn);
        var choice = ask.showAndWait();
        if (choice.isEmpty() || choice.get() == cancelBtn) return;
        if (choice.get() == fileBtn) uploadFile();
        else if (choice.get() == folderBtn) uploadFolder();
    }

    private void uploadFile() {
        FileChooser chooser = new FileChooser();
        File picked = chooser.showOpenDialog(uploadBtn.getScene().getWindow());
        if (picked == null) return;

        Path local = picked.toPath();
        long total;
        try { total = Files.size(local); } catch (Exception ex) { total = 0; }
        long fixedTotal = total;
        String subFolder = currentRemotePath();

        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                peerClient.uploadFile(remoteHost, remotePort, remotePin,
                        currentShare.id(), subFolder, local,
                        bytes -> Platform.runLater(() -> {
                            if (fixedTotal > 0) progressBar.setProgress((double) bytes / fixedTotal);
                            setStatus(i18n.t("status.uploading", picked.getName(), humanSize(bytes), humanSize(fixedTotal)));
                        }));
                return null;
            }
        };
        task.setOnSucceeded(e -> { progressBar.setProgress(1); setStatus(i18n.t("status.uploadDone", picked.getName())); loadCurrentRemoteFolder(); });
        task.setOnFailed(e -> setStatus(i18n.t("status.uploadFailed", task.getException().getMessage())));
        runInBackground(task);
    }

    private void uploadFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        File picked = chooser.showDialog(uploadBtn.getScene().getWindow());
        if (picked == null) return;

        Path localRoot = picked.toPath();
        String subFolder = currentRemotePath();

        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                peerClient.uploadFolder(remoteHost, remotePort, remotePin,
                        currentShare.id(), subFolder, localRoot,
                        new PeerClient.FolderUploadProgress() {
                            @Override public void onStart(long totalFiles, long totalBytes) {
                                Platform.runLater(() ->
                                        setStatus(i18n.t("status.uploadingFolder", totalFiles, humanSize(totalBytes))));
                            }
                            @Override public void onProgress(long doneFiles, long totalFiles,
                                                              long doneBytes, long totalBytes, String currentFile) {
                                Platform.runLater(() -> {
                                    if (totalBytes > 0) progressBar.setProgress((double) doneBytes / totalBytes);
                                    setStatus(i18n.t("status.uploadingProgress",
                                            doneFiles, totalFiles, currentFile,
                                            humanSize(doneBytes), humanSize(totalBytes)));
                                });
                            }
                        });
                return null;
            }
        };
        task.setOnSucceeded(e -> { progressBar.setProgress(1); setStatus(i18n.t("status.uploadDone", picked.getName())); loadCurrentRemoteFolder(); });
        task.setOnFailed(e -> setStatus(i18n.t("status.uploadFailed", task.getException().getMessage())));
        runInBackground(task);
    }

    private void deleteRemoteSelected() {
        PeerClient.RemoteEntry sel = remoteEntriesTable.getSelectionModel().getSelectedItem();
        if (sel == null) { setStatus(i18n.t("status.selectAny")); return; }
        if (currentShare == null || !currentShare.writable()) {
            Alert a = new Alert(Alert.AlertType.WARNING, i18n.t("dialog.readonly.body"));
            a.setTitle(i18n.t("dialog.readonly.title"));
            a.showAndWait();
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(i18n.t("dialog.confirmRemove.title"));
        confirm.setHeaderText(i18n.t("dialog.confirmRemove.header"));
        String label = sel.directory() ? i18n.t("dialog.confirmRemove.folderLabel") : i18n.t("dialog.confirmRemove.fileLabel");
        confirm.setContentText(label + sel.name() + (sel.directory() ? i18n.t("dialog.confirmRemove.folderNote") : ""));
        var choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) return;

        String remotePath = currentRemotePath().isEmpty() ? sel.name() : currentRemotePath() + "/" + sel.name();
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                peerClient.deleteEntry(remoteHost, remotePort, remotePin, currentShare.id(), remotePath);
                return null;
            }
        };
        task.setOnSucceeded(e -> { setStatus(i18n.t("status.removed", sel.name())); loadCurrentRemoteFolder(); });
        task.setOnFailed(e -> setStatus(i18n.t("status.removeFailed", task.getException().getMessage())));
        runInBackground(task);
    }

    private void updateRemoteButtonsEnabled() {
        boolean shareSelected = currentShare != null;
        boolean writable = shareSelected && currentShare.writable();
        boolean entrySelected = remoteEntriesTable.getSelectionModel().getSelectedItem() != null;
        boolean fileSelected = entrySelected && !remoteEntriesTable.getSelectionModel().getSelectedItem().directory();
        uploadBtn.setDisable(!writable);
        deleteRemoteBtn.setDisable(!(writable && entrySelected));
        downloadBtn.setDisable(!fileSelected);
    }

    // ---------- Helpers ----------

    private void runInBackground(Task<?> task) {
        Thread t = new Thread(task, "fb-bg");
        t.setDaemon(true);
        t.start();
    }

    private void setStatus(String text) { Platform.runLater(() -> statusLabel.setText(text)); }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        do { v /= 1024.0; i++; } while (v >= 1024 && i < units.length - 1);
        return String.format("%.1f %s", v, units[i]);
    }
}
