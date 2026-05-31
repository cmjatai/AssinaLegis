package br.leg.go.jatai.assinalegis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.security.KeyStore;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.util.Duration;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class DocumentViewerController {

    private enum ViewMode {
        M1,
        M2
    }

    @FXML private ListView<SignableItem> documentListView;
    @FXML private ScrollPane scrollPane;
    @FXML private StackPane contentHolder;
    @FXML private CheckBox chkSelectAll;
    @FXML private CheckBox chkSelectAllM2;

    @FXML private Button btnModeM1;
    @FXML private Button btnModeM2;
    @FXML private HBox hBoxM1Controls;
    @FXML private HBox hBoxM2Controls;

    @FXML private TabPane tabPaneM1;
    @FXML private Tab tabMinhas;
    @FXML private Tab tabSolicitacoes;
    @FXML private ListView<SignableItem> documentListViewSolicitacoes;
    @FXML private HBox hBoxLockStatus;
    @FXML private Label lblLockItem;
    @FXML private ProgressBar pbLockCountdown;
    @FXML private Label lblLockTempo;
    @FXML private Button btnLiberarLock;
    @FXML private Button btnSubmeterM1;

    @FXML private Button btnFirstPage;
    @FXML private Button btnPrevPage;
    @FXML private Button btnZoomOut;
    @FXML private Button btnFitWidth;
    @FXML private Button btnFitHeight;
    @FXML private Button btnZoomIn;
    @FXML private Button btnNextPage;
    @FXML private Button btnLastPage;

    private Consumer<String> logAction;

    private PDDocument currentDocument;
    private boolean currentDocumentIsOwnedByItem = false;
    private PDFRenderer pdfRenderer;
    private int currentPageIndex = 0;
    private int totalPages = 0;

    private final DoubleProperty zoomProperty = new SimpleDoubleProperty(1.0);
    private final AtomicReference<Rectangle> lastRect = new AtomicReference<>();
    private Group group;
    private ImageView imageView;
    private Pane imageWrapper;

    private ConfigService configService;
    private boolean isUpdatingSelectAll = false;
    private ViewMode currentMode = ViewMode.M1;
    private ConfigService.ConfigObserver authStateObserver;
    private boolean ultimoEstadoTokenConfigurado;

    // --- Estado do lock de assinatura colaborativa ---
    /** Item que detém o lock ativo no momento. */
    private AssinaturaItem itemComLockAtivo = null;
    /** Timeline que atualiza o countdown visual. */
    private Timeline lockCountdownTimeline = null;
    /** Serviço de agendamento para o polling pós-upload. */
    private final ScheduledExecutorService pollingExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "polling-assinatura");
                t.setDaemon(true);
                return t;
            });
    private ScheduledFuture<?> pollingFuture = null;

    @FXML
    public void initialize() {
        configService = ConfigService.getInstance();
        authStateObserver = this::onConfigChanged;
        configService.addObserver(authStateObserver);
        ultimoEstadoTokenConfigurado = hasTokenConfigurado();

        if (chkSelectAll != null) {
            chkSelectAll.setAllowIndeterminate(true);
        }
        if (chkSelectAllM2 != null) {
            chkSelectAllM2.setAllowIndeterminate(true);
        }

        initializeDocumentList();
        initializeSolicitacoesList();
        setupViewer();

        if (tabPaneM1 != null) {
            tabPaneM1.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
                atualizarBotaoSubmeter();
                if (newTab == tabSolicitacoes && hasTokenConfigurado()) {
                    refreshSolicitacoesList();
                }
            });
        }

        if (hasTokenConfigurado()) {
            switchMode(ViewMode.M1);
        } else {
            switchMode(ViewMode.M2);
            log("Modo M1 indisponível: nenhum token armazenado.\n");
        }
        updateNavigationButtons();
    }

    private void onConfigChanged(String key, Object newValue) {
        if (!ConfigService.KEY_TOKEN.equals(key)) {
            return;
        }

        Platform.runLater(this::processAuthStateChange);
    }

    private void processAuthStateChange() {
        boolean tokenConfigurado = hasTokenConfigurado();
        boolean houveLogin = !ultimoEstadoTokenConfigurado && tokenConfigurado;
        boolean houveLogout = ultimoEstadoTokenConfigurado && !tokenConfigurado;
        ultimoEstadoTokenConfigurado = tokenConfigurado;

        if (houveLogout && currentMode == ViewMode.M2) {
            clearPreview();
            clearItemsAndDocuments();
            log("Logout realizado. Lista de documentos limpa.\n");
        }

        if (houveLogout && currentMode == ViewMode.M1) {
            switchMode(ViewMode.M2);
            log("Token removido. Alternando para M2.\n");
            return;
        }

        if (houveLogin) {
            switchMode(ViewMode.M1);
            log("Login realizado. Alternando para M1.\n");
            return;
        }

        applyModeVisualState();

        if (tokenConfigurado) {
            log("Token configurado. M1 disponível para ativação.\n");
        } else {
            log("M1 indisponível: nenhum token armazenado.\n");
        }
    }

    public void setLogAction(Consumer<String> logAction) {
        this.logAction = logAction;
    }

    private void log(String message) {
        if (logAction != null) {
            Platform.runLater(() -> logAction.accept(message));
        }
    }

    @FXML
    private void onSwitchToM1() {
        if (!hasTokenConfigurado()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Token não configurado");
            alert.setHeaderText(null);
            alert.setContentText("Para usar o modo M1, configure um token de autenticação.");
            alert.showAndWait();
            switchMode(ViewMode.M2);
            return;
        }
        switchMode(ViewMode.M1);
    }

    @FXML
    private void onSwitchToM2() {
        switchMode(ViewMode.M2);
    }

    private void switchMode(ViewMode mode) {
        if (mode == ViewMode.M1 && !hasTokenConfigurado()) {
            mode = ViewMode.M2;
        }

        currentMode = mode;
        applyModeVisualState();
        clearPreview();
        clearItemsAndDocuments();

        if (currentMode == ViewMode.M1) {
            refreshDocumentList();
            log("Modo M1 ativo: lista carregada da API.\n");
        } else {
            updateSelectAllState();
            log("Modo M2 ativo: carregue PDFs locais para assinar.\n");
        }
    }

    private void applyModeVisualState() {
        boolean m1 = currentMode == ViewMode.M1;
        boolean tokenConfigurado = hasTokenConfigurado();

        if (hBoxM1Controls != null) {
            hBoxM1Controls.setVisible(m1);
            hBoxM1Controls.setManaged(m1);
        }
        if (hBoxM2Controls != null) {
            hBoxM2Controls.setVisible(!m1);
            hBoxM2Controls.setManaged(!m1);
        }

        if (btnModeM1 != null) {
            btnModeM1.setVisible(tokenConfigurado);
            btnModeM1.setManaged(tokenConfigurado);
            btnModeM1.setDisable(m1 || !tokenConfigurado);
        }
        if (btnModeM2 != null) {
            btnModeM2.setDisable(!m1);
        }

        // Abas do TabPane M1
        if (tabMinhas != null) {
            tabMinhas.setText(m1 ? "Minhas Proposições" : "Arquivos Locais");
        }
        if (tabSolicitacoes != null) {
            tabSolicitacoes.setDisable(!m1);
            if (!m1 && tabPaneM1 != null) {
                tabPaneM1.getSelectionModel().select(tabMinhas);
            }
        }

        // Botão Submeter: ocultar quando a aba Solicitações estiver ativa
        atualizarBotaoSubmeter();

        updateSelectAllState();
    }

    private void atualizarBotaoSubmeter() {
        if (btnSubmeterM1 == null) return;
        boolean solicitacoesAtiva = currentMode == ViewMode.M1
                && tabPaneM1 != null
                && tabPaneM1.getSelectionModel().getSelectedItem() == tabSolicitacoes;
        btnSubmeterM1.setVisible(!solicitacoesAtiva);
        btnSubmeterM1.setManaged(!solicitacoesAtiva);
    }

    private boolean hasTokenConfigurado() {
        String token = configService != null ? configService.getToken() : null;
        return token != null && !token.isBlank();
    }

    private void setupViewer() {
        imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.setCache(true);

        group = new Group(imageView);

        Scale scaleTransform = new Scale();
        scaleTransform.xProperty().bind(zoomProperty);
        scaleTransform.yProperty().bind(zoomProperty);
        scaleTransform.setPivotX(0);
        scaleTransform.setPivotY(0);
        group.getTransforms().add(scaleTransform);

        imageWrapper = new Pane(group);

        imageWrapper.minWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
            () -> imageView.getImage() != null ? imageView.getImage().getWidth() * zoomProperty.get() : 0.0,
            zoomProperty, imageView.imageProperty()));
        imageWrapper.minHeightProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
            () -> imageView.getImage() != null ? imageView.getImage().getHeight() * zoomProperty.get() : 0.0,
            zoomProperty, imageView.imageProperty()));

        imageWrapper.maxWidthProperty().bind(imageWrapper.minWidthProperty());
        imageWrapper.maxHeightProperty().bind(imageWrapper.minHeightProperty());

        contentHolder.getChildren().add(imageWrapper);

        double rectWidth = (6.0 / 2.54) * 200;
        double rectHeight = (1.7 / 2.54) * 200;

        group.setOnMouseClicked(event -> {
            if (event.isControlDown()) {
                if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                    onZoomIn();
                } else if (event.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                    onZoomOut();
                }
                event.consume();
            } else if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                if (lastRect.get() != null) {
                    group.getChildren().remove(lastRect.get());
                }

                SignableItem item = documentListView.getSelectionModel().getSelectedItem();
                if (item == null) {
                    log("Nenhum documento selecionado para adicionar a marcação.\n");
                    return;
                }

                if (isItemDisabled(item)) {
                    log("O documento selecionado não pode ser marcado.\n");
                    return;
                }

                Rectangle rect = new Rectangle(rectWidth, rectHeight);
                rect.setFill(Color.rgb(0, 115, 183, 0.6));
                rect.setStroke(Color.rgb(0, 115, 183, 1.0));

                rect.setX(event.getX());
                rect.setY(event.getY() - rectHeight);

                group.getChildren().add(rect);
                lastRect.set(rect);
                updateCurrentItemState();
                event.consume();
            }
        });

        scrollPane.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> {
            if (event.isControlDown()) {
                double deltaY = event.getDeltaY();
                if (deltaY > 0) {
                    zoom(1.05);
                } else if (deltaY < 0) {
                    zoom(0.95);
                }
                event.consume();
            }
        });
    }

    private void zoom(double factor) {
        zoomProperty.set(zoomProperty.get() * factor);
    }

    @FXML
    private void onRefreshDocuments() {
        if (currentMode != ViewMode.M1) {
            log("Atualização da API disponível apenas no modo M1.\n");
            return;
        }

        log("Atualizando lista de documentos...\n");
        clearPreview();
        clearItemsAndDocuments();
        refreshDocumentList();
    }

    @FXML
    private void onLoadLocalFiles() {
        if (currentMode != ViewMode.M2) {
            switchMode(ViewMode.M2);
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Selecionar arquivos PDF");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Arquivos PDF", "*.pdf"));

        List<File> arquivos = fileChooser.showOpenMultipleDialog(documentListView.getScene().getWindow());
        if (arquivos == null || arquivos.isEmpty()) {
            return;
        }

        log("Carregando " + arquivos.size() + " arquivo(s) local(is) em etapa incremental...\n");

        new Thread(() -> {
            int carregados = 0;
            for (File arquivo : arquivos) {
                try {
                    byte[] bytes = Files.readAllBytes(arquivo.toPath());
                    PDDocument doc = Loader.loadPDF(bytes);

                    FileItem item = new FileItem(arquivo.getName(), "Arquivo local", arquivo);
                    item.setOriginalBytes(bytes);
                    item.setPdDocument(doc);
                    item.selectedProperty().addListener((obs, wasSelected, isSelected) -> updateSelectAllState());

                    Platform.runLater(() -> documentListView.getItems().add(item));
                    carregados++;
                } catch (Exception e) {
                    String erro = "Erro ao carregar '" + arquivo.getName() + "': " + e.getMessage() + "\n";
                    Platform.runLater(() -> log(erro));
                }
            }

            int totalCarregados = carregados;
            Platform.runLater(() -> {
                updateSelectAllState();
                log("Arquivos locais carregados: " + totalCarregados + ".\n");
            });
        }).start();
    }

    @FXML
    private void onClearLocalList() {
        if (currentMode != ViewMode.M2) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Aviso");
            alert.setHeaderText(null);
            alert.setContentText("A limpeza da lista local está disponível apenas no modo M2.");
            alert.showAndWait();
            return;
        }

        if (documentListView.getItems().isEmpty()) {
            return;
        }

        clearPreview();
        clearItemsAndDocuments();
        log("Lista local limpa.\n");
    }

    @FXML
    private void onSaveSignedFiles() {
        if (currentMode != ViewMode.M2) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Aviso");
            alert.setHeaderText(null);
            alert.setContentText("O salvamento local está disponível apenas no modo M2.");
            alert.showAndWait();
            return;
        }

        List<FileItem> itemsToSave = documentListView.getItems().stream()
            .filter(item -> item instanceof FileItem)
            .map(item -> (FileItem) item)
            .filter(item -> item.getPdDocumentSigned() != null)
            .collect(Collectors.toList());

        if (itemsToSave.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Aviso");
            alert.setHeaderText(null);
            alert.setContentText("Não há arquivos assinados para salvar.");
            alert.showAndWait();
            return;
        }

        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Selecionar pasta de destino");
        File pastaDestino = directoryChooser.showDialog(documentListView.getScene().getWindow());
        if (pastaDestino == null) {
            return;
        }

        log("Salvando " + itemsToSave.size() + " arquivo(s) assinado(s)...\n");

        new Thread(() -> {
            int successCount = 0;

            for (FileItem item : itemsToSave) {
                try {
                    byte[] pdfBytes = item.getSignedBytes();
                    if (pdfBytes == null) {
                        PDDocument signedDoc = item.getPdDocumentSigned();
                        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                            signedDoc.save(baos);
                            pdfBytes = baos.toByteArray();
                        }
                    }

                    String nomeBase = removePdfExtension(item.getHeader());
                    File arquivoDestino = new File(pastaDestino, nomeBase + "_assinado.pdf");

                    try (FileOutputStream fos = new FileOutputStream(arquivoDestino)) {
                        fos.write(pdfBytes);
                    }

                    successCount++;
                    Platform.runLater(() -> log("Arquivo salvo: " + arquivoDestino.getName() + "\n"));
                } catch (Exception e) {
                    Platform.runLater(() -> log("Erro ao salvar '" + item.getHeader() + "': " + e.getMessage() + "\n"));
                }
            }

            int total = successCount;
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Salvamento concluído");
                alert.setHeaderText(null);
                alert.setContentText(total + " arquivo(s) salvo(s) com sucesso.");
                alert.showAndWait();
            });
        }).start();
    }

    private String removePdfExtension(String fileName) {
        if (fileName == null) {
            return "arquivo";
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return fileName.substring(0, fileName.length() - 4);
        }
        return fileName;
    }

    private void clearItemsAndDocuments() {
        for (SignableItem item : documentListView.getItems()) {
            closeDocumentIfNecessary(item.getPdDocument());
            closeDocumentIfNecessary(item.getPdDocumentSigned());
            item.setPdDocument(null);
            item.setPdDocumentSigned(null);
            item.setOriginalBytes(null);
            item.setSignedBytes(null);
        }
        documentListView.getItems().clear();
        clearSolicitacoesListItems();
        updateSelectAllState();
    }

    private void clearSolicitacoesListItems() {
        cancelarLockAtivo(false);
        pararPolling();
        if (documentListViewSolicitacoes != null) {
            for (SignableItem item : documentListViewSolicitacoes.getItems()) {
                closeDocumentIfNecessary(item.getPdDocument());
                closeDocumentIfNecessary(item.getPdDocumentSigned());
                item.setPdDocument(null);
                item.setPdDocumentSigned(null);
                item.setOriginalBytes(null);
                item.setSignedBytes(null);
            }
            documentListViewSolicitacoes.getItems().clear();
        }
        atualizarBadgeSolicitacoes(0);
    }

    private void closeDocumentIfNecessary(PDDocument document) {
        if (document == null) {
            return;
        }

        try {
            document.close();
        } catch (Exception ignored) {
        }
    }

    private void clearPreview() {
        if (currentDocument != null && !currentDocumentIsOwnedByItem) {
            try {
                currentDocument.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        currentDocument = null;
        currentDocumentIsOwnedByItem = false;

        Platform.runLater(() -> {
            if (imageView != null) {
                imageView.setImage(null);
            }
            if (lastRect.get() != null && group != null) {
                group.getChildren().remove(lastRect.get());
                lastRect.set(null);
            }
            updateNavigationButtons();
        });
    }

    private void refreshDocumentList() {
        ObservableList<SignableItem> items = documentListView.getItems();

        new Thread(() -> {
            try {
                Map<String, Object> params = new HashMap<>();
                params.put("o", "-data_envio,-id");
                params.put("page_size", 100);
                params.put("expand", "autor");
                InputStream response = ApiService.getInstance().get("materia", "proposicao", null, null, params);

                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response);

                Platform.runLater(() -> {
                    if (root.has("results") && root.get("results").isArray()) {
                        for (JsonNode node : root.get("results")) {
                            String header = node.has("__str__") ? node.get("__str__").asText() : "";
                            String description = node.has("descricao") ? node.get("descricao").asText() : "";
                            DocumentItem item = new DocumentItem(header, description, node);

                            if (node.has("data_envio") && node.get("data_envio").isNull()) {
                                preloadPdf(item);
                            }

                            item.selectedProperty().addListener((obs, wasSelected, isSelected) -> updateSelectAllState());
                            items.add(item);
                        }
                    }
                    updateSelectAllState();
                    log("Lista de documentos atualizada com " + items.size() + " itens.\n");
                });

            } catch (Exception e) {
                e.printStackTrace();
                log("Erro ao atualizar documentos: " + e.getMessage() + "\n");
            }
        }).start();
    }

    private InputStream getInputStreamFromUrl(String urlString) throws IOException {
        if (urlString != null && !urlString.isEmpty() && !"null".equals(urlString)) {
            URL url = java.net.URI.create(urlString).toURL();
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
            String token = configService.getToken();
            if (token != null && !token.isEmpty()) {
                connection.setRequestProperty("Authorization", "Token " + token);
            }
            return connection.getInputStream();
        }
        throw new IOException("URL inválida para obter InputStream: " + urlString);
    }

    private void preloadPdf(DocumentItem item) {
        JsonNode jsonNode = item.getJsonData();
        if (jsonNode.has("texto_original")) {
            String urlString = jsonNode.get("texto_original").asText();
            if (urlString != null && !urlString.isEmpty() && !"null".equals(urlString)) {
                new Thread(() -> {
                    try (InputStream is = getInputStreamFromUrl(urlString)) {
                        byte[] bytes = is.readAllBytes();
                        item.setOriginalBytes(bytes);
                        PDDocument doc = Loader.loadPDF(bytes);
                        item.setPdDocument(doc);
                    } catch (Exception e) {
                        e.printStackTrace();
                        log("Erro ao pré-carregar PDF: " + e.getMessage() + "\n");
                    }
                }).start();
            }
        }
    }

    private void initializeDocumentList() {
        ObservableList<SignableItem> items = FXCollections.observableArrayList();
        documentListView.setItems(items);
        documentListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        documentListView.setCellFactory(param -> {
            ListCell<SignableItem> cell = new ListCell<>() {
                @Override
                protected void updateItem(SignableItem item, boolean empty) {
                    super.updateItem(item, empty);

                    if (empty || item == null) {
                        setText(null);
                        setGraphic(null);
                    } else {
                        VBox mainVBox = new VBox(5);

                        HBox headerHBox = new HBox(10);
                        headerHBox.setAlignment(Pos.CENTER_LEFT);

                        CheckBox checkBox = new CheckBox();
                        checkBox.selectedProperty().bindBidirectional(item.selectedProperty());

                        boolean disabled = isItemDisabled(item);
                        checkBox.setDisable(disabled);
                        if (disabled) {
                            checkBox.setTooltip(new Tooltip("Este documento já foi enviado e não pode ser selecionado."));
                        }

                        if (item instanceof DocumentItem apiItem) {
                            Hyperlink headerLink = new Hyperlink(item.getHeader());
                            headerLink.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-border-color: transparent; -fx-padding: 0;");
                            headerLink.setWrapText(true);

                            String urlTemp = ConfigService.getInstance().getUrl();
                            if (urlTemp != null && urlTemp.endsWith("/")) {
                                urlTemp = urlTemp.substring(0, urlTemp.length() - 1);
                            }

                            JsonNode jsonData = apiItem.getJsonData();
                            boolean hasDataEnvio = disabled;
                            if (hasDataEnvio) {
                                headerLink.setStyle(headerLink.getStyle() + " -fx-text-fill: #640606ff;");
                                headerLink.setTooltip(new Tooltip("Este documento já foi enviado."));
                                if (urlTemp != null) {
                                    urlTemp += "/materia/" + jsonData.get("object_id").asText();
                                }
                            } else {
                                headerLink.setStyle(headerLink.getStyle() + " -fx-text-fill: #064664ff;");
                                if (urlTemp != null) {
                                    urlTemp += "/proposicao/" + jsonData.get("id").asText();
                                }
                            }

                            final String url = urlTemp;
                            headerLink.setOnAction(e -> {
                                if (url == null || url.isBlank()) {
                                    log("URL base não configurada para abrir o item.\n");
                                    return;
                                }
                                try {
                                    App.openUrl(url);
                                } catch (Exception ex) {
                                    log("Erro ao abrir link: " + ex.getMessage() + "\n");
                                }
                            });

                            headerLink.prefWidthProperty().bind(getListView().widthProperty().subtract(65));
                            headerHBox.getChildren().addAll(checkBox, headerLink);

                            VBox detailsVBox = new VBox(2);
                            detailsVBox.setPadding(new Insets(0, 0, 0, 0));

                            if (jsonData.has("autor") && !jsonData.get("autor").isNull() && jsonData.get("autor").has("nome")) {
                                Label autorLabel = new Label("Autor: " + jsonData.get("autor").get("nome").asText());
                                autorLabel.styleProperty().bind(
                                    javafx.beans.binding.Bindings.when(selectedProperty())
                                        .then("-fx-font-size: 11px; -fx-text-fill: -fx-selection-bar-text;")
                                        .otherwise("-fx-font-size: 11px; -fx-text-fill: #555555;")
                                );
                                detailsVBox.getChildren().add(autorLabel);
                            }

                            if (jsonData.has("data_envio") && !jsonData.get("data_envio").isNull()) {
                                String dataEnvio = jsonData.get("data_envio").asText();
                                Label dataEnvioLabel = new Label("Enviado em: " + formatData(dataEnvio));
                                dataEnvioLabel.styleProperty().bind(
                                    javafx.beans.binding.Bindings.when(selectedProperty())
                                        .then("-fx-font-size: 11px; -fx-text-fill: -fx-selection-bar-text;")
                                        .otherwise("-fx-font-size: 11px; -fx-text-fill: #555555;")
                                );
                                detailsVBox.getChildren().add(dataEnvioLabel);
                            }

                            if (jsonData.has("data_recebimento") && !jsonData.get("data_recebimento").isNull()) {
                                String dataRecebimento = jsonData.get("data_recebimento").asText();
                                Label dataRecebimentoLabel = new Label("Recebido em: " + formatData(dataRecebimento));
                                dataRecebimentoLabel.styleProperty().bind(
                                    javafx.beans.binding.Bindings.when(selectedProperty())
                                        .then("-fx-font-size: 11px; -fx-text-fill: -fx-selection-bar-text;")
                                        .otherwise("-fx-font-size: 11px; -fx-text-fill: #555555;")
                                );
                                detailsVBox.getChildren().add(dataRecebimentoLabel);
                            }

                            Label descLabel = new Label(item.getDescription());
                            descLabel.setWrapText(true);
                            descLabel.prefWidthProperty().bind(getListView().widthProperty().subtract(65));
                            descLabel.styleProperty().bind(
                                javafx.beans.binding.Bindings.when(selectedProperty())
                                    .then("-fx-text-fill: -fx-selection-bar-text;")
                                    .otherwise("-fx-text-fill: #666666;")
                            );

                            detailsVBox.getChildren().add(descLabel);
                            mainVBox.getChildren().add(headerHBox);
                            mainVBox.getChildren().add(detailsVBox);
                        } else if (item instanceof FileItem fileItem) {
                            Label headerLabel = new Label(item.getHeader());
                            headerLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
                            headerLabel.setWrapText(true);
                            headerLabel.prefWidthProperty().bind(getListView().widthProperty().subtract(65));

                            headerHBox.getChildren().addAll(checkBox, headerLabel);

                            VBox detailsVBox = new VBox(2);
                            detailsVBox.setPadding(new Insets(0, 0, 0, 0));

                            Label origemLabel = new Label("Origem: " + fileItem.getSourceFile().getAbsolutePath());
                            origemLabel.setWrapText(true);
                            origemLabel.setMinWidth(0);
                            origemLabel.setMaxWidth(Double.MAX_VALUE);
                            origemLabel.prefWidthProperty().bind(getListView().widthProperty().subtract(150));
                            origemLabel.styleProperty().bind(
                                javafx.beans.binding.Bindings.when(selectedProperty())
                                    .then("-fx-font-size: 11px; -fx-text-fill: -fx-selection-bar-text;")
                                    .otherwise("-fx-font-size: 11px; -fx-text-fill: #555555;")
                            );

                            Button removeButton = new Button("Remover");
                            removeButton.setOnAction(event -> {
                                removeFileItem(fileItem);
                                event.consume();
                            });

                            HBox origemHBox = new HBox(8);
                            origemHBox.setAlignment(Pos.CENTER_LEFT);
                            HBox.setHgrow(origemLabel, Priority.ALWAYS);
                            origemHBox.getChildren().addAll(origemLabel, removeButton);

                            Label descLabel = new Label(item.getDescription());
                            descLabel.setWrapText(true);
                            descLabel.prefWidthProperty().bind(getListView().widthProperty().subtract(65));
                            descLabel.styleProperty().bind(
                                javafx.beans.binding.Bindings.when(selectedProperty())
                                    .then("-fx-text-fill: -fx-selection-bar-text;")
                                    .otherwise("-fx-text-fill: #666666;")
                            );

                            detailsVBox.getChildren().addAll(origemHBox, descLabel);
                            mainVBox.getChildren().add(headerHBox);
                            mainVBox.getChildren().add(detailsVBox);
                        }

                        setGraphic(mainVBox);
                    }
                }
            };

            cell.setOnMouseClicked(event -> {
                if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY
                        && event.getClickCount() == 2
                        && !cell.isEmpty()) {
                    SignableItem item = cell.getItem();
                    if (item != null && !isItemDisabled(item)) {
                        item.setSelected(!item.isSelected());
                    }
                }
            });

            return cell;
        });

        documentListView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (oldValue != null) {
                oldValue.setSavedPageIndex(currentPageIndex);
                oldValue.setSavedRect(lastRect.get());
            }
            if (newValue != null) {
                handleDocumentSelection(newValue);
            }
        });
    }

    private String formatData(String dateStr) {
        try {
            ZonedDateTime zdt = ZonedDateTime.parse(dateStr);
            return zdt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        } catch (Exception e) {
            return dateStr;
        }
    }

    private void handleDocumentSelection(SignableItem item) {
        log("Item selecionado: " + item.getHeader() + "\n");

        if (item.getPdDocument() != null) {
            loadPdfPreview(item.getPdDocument(), item.getSavedPageIndex(), item.getSavedRect());
            return;
        }

        if (item instanceof DocumentItem documentItem) {
            JsonNode jsonNode = documentItem.getJsonData();
            if (jsonNode != null && jsonNode.has("texto_original")) {
                String textoOriginal = jsonNode.get("texto_original").asText();
                if (textoOriginal == null || textoOriginal.isEmpty() || "null".equals(textoOriginal)) {
                    log("O documento selecionado não possui PDF disponível.\n");
                    clearPreview();
                    return;
                }
                loadPdfPreview(textoOriginal, item.getSavedPageIndex(), item.getSavedRect());
                return;
            }
        }

        if (item instanceof AssinaturaItem assinaturaItem) {
            JsonNode jsonNode = assinaturaItem.getJsonData();
            if (jsonNode != null && jsonNode.has("texto_original")) {
                String textoOriginal = jsonNode.get("texto_original").asText();
                if (textoOriginal != null && !textoOriginal.isEmpty() && !"null".equals(textoOriginal)) {
                    loadPdfPreview(textoOriginal, item.getSavedPageIndex(), item.getSavedRect());
                    return;
                }
            }
            log("A proposição selecionada não possui PDF disponível.\n");
        }

        clearPreview();
    }

    private void loadPdfPreview(PDDocument doc, int initialPage, Rectangle initialRect) {
        clearPreview();
        currentDocument = doc;
        currentDocumentIsOwnedByItem = true;
        pdfRenderer = new PDFRenderer(currentDocument);
        totalPages = currentDocument.getNumberOfPages();
        currentPageIndex = initialPage;

        new Thread(() -> {
            renderCurrentPage();
            if (initialRect != null) {
                Platform.runLater(() -> restoreRect(initialRect));
            }
        }).start();
    }

    private void loadPdfPreview(String urlString, int initialPage, Rectangle initialRect) {
        clearPreview();

        new Thread(() -> {
            try (InputStream is = getInputStreamFromUrl(urlString)) {
                byte[] bytes = is.readAllBytes();
                currentDocument = Loader.loadPDF(bytes);
                currentDocumentIsOwnedByItem = false;
                pdfRenderer = new PDFRenderer(currentDocument);
                totalPages = currentDocument.getNumberOfPages();
                currentPageIndex = initialPage;

                renderCurrentPage();

                if (initialRect != null) {
                    Platform.runLater(() -> restoreRect(initialRect));
                }
            } catch (Exception e) {
                e.printStackTrace();
                log("Erro ao carregar PDF: " + e.getMessage() + "\n");
            }
        }).start();
    }

    private void restoreRect(Rectangle rect) {
        if (lastRect.get() != null) {
            group.getChildren().remove(lastRect.get());
        }
        if (rect.getParent() != null) {
            ((Group) rect.getParent()).getChildren().remove(rect);
        }
        group.getChildren().add(rect);
        lastRect.set(rect);
    }

    private void renderCurrentPage() {
        if (currentDocument == null || pdfRenderer == null) {
            return;
        }

        try {
            BufferedImage bim = pdfRenderer.renderImageWithDPI(currentPageIndex, 200, org.apache.pdfbox.rendering.ImageType.RGB);
            WritableImage image = SwingFXUtils.toFXImage(bim, null);

            Platform.runLater(() -> {
                imageView.setImage(image);
                updateNavigationButtons();

                if (currentPageIndex == 0) {
                    onFitHeight();
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
            log("Erro ao renderizar página: " + e.getMessage() + "\n");
        }
    }

    private void updateNavigationButtons() {
        boolean hasDoc = currentDocument != null;
        btnFirstPage.setDisable(!hasDoc || currentPageIndex == 0);
        btnPrevPage.setDisable(!hasDoc || currentPageIndex == 0);
        btnNextPage.setDisable(!hasDoc || currentPageIndex >= totalPages - 1);
        btnLastPage.setDisable(!hasDoc || currentPageIndex >= totalPages - 1);

        btnZoomIn.setDisable(!hasDoc);
        btnZoomOut.setDisable(!hasDoc);
        btnFitWidth.setDisable(!hasDoc);
        btnFitHeight.setDisable(!hasDoc);
    }

    @FXML
    private void onFirstPage() {
        if (currentPageIndex > 0) {
            currentPageIndex = 0;
            updateCurrentItemState();
            new Thread(this::renderCurrentPage).start();
        }
    }

    @FXML
    private void onPrevPage() {
        if (currentPageIndex > 0) {
            currentPageIndex--;
            updateCurrentItemState();
            new Thread(this::renderCurrentPage).start();
        }
    }

    @FXML
    private void onNextPage() {
        if (currentPageIndex < totalPages - 1) {
            currentPageIndex++;
            updateCurrentItemState();
            new Thread(this::renderCurrentPage).start();
        }
    }

    @FXML
    private void onLastPage() {
        if (currentPageIndex < totalPages - 1) {
            currentPageIndex = totalPages - 1;
            updateCurrentItemState();
            new Thread(this::renderCurrentPage).start();
        }
    }

    private void updateCurrentItemState() {
        SignableItem selectedItem = documentListView.getSelectionModel().getSelectedItem();
        if (selectedItem != null) {
            selectedItem.setSavedPageIndex(currentPageIndex);
            selectedItem.setSavedRect(lastRect.get());
        }
    }

    @FXML
    private void onZoomIn() {
        zoom(1.25);
    }

    @FXML
    private void onZoomOut() {
        zoom(0.8);
    }

    @FXML
    private void onFitWidth() {
        if (imageView.getImage() == null) {
            return;
        }

        double width = scrollPane.getWidth();
        if (width <= 0) {
            width = 800;
        }

        double fitScale = (width - 40) / imageView.getImage().getWidth();
        if (fitScale > 0) {
            zoomProperty.set(fitScale);
        }
    }

    @FXML
    private void onFitHeight() {
        if (imageView.getImage() == null) {
            return;
        }

        double height = scrollPane.getHeight();
        if (height <= 0) {
            height = 600;
        }

        double fitScale = (height - 40) / imageView.getImage().getHeight();
        if (fitScale > 0) {
            zoomProperty.set(fitScale);
        }
    }

    @FXML
    private void onSend() {
        if (currentMode != ViewMode.M1) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Aviso");
            alert.setHeaderText(null);
            alert.setContentText("O envio para API está disponível apenas no modo M1.");
            alert.showAndWait();
            return;
        }

        List<DocumentItem> itemsToSend = documentListView.getItems().stream()
            .filter(item -> item instanceof DocumentItem)
            .map(item -> (DocumentItem) item)
            .filter(item -> item.getPdDocumentSigned() != null)
            .collect(Collectors.toList());

        if (itemsToSend.isEmpty()) {
            log("Nenhum documento assinado para enviar.\n");
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Aviso");
            alert.setHeaderText(null);
            alert.setContentText("Não há documentos assinados para enviar.");
            alert.showAndWait();
            return;
        }

        log("Iniciando envio de " + itemsToSend.size() + " documentos...\n");

        new Thread(() -> {
            int successCount = 0;
            for (DocumentItem item : itemsToSend) {
                try {
                    byte[] pdfBytes = item.getSignedBytes();
                    if (pdfBytes == null) {
                        PDDocument signedDoc = item.getPdDocumentSigned();
                        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                            signedDoc.save(baos);
                            pdfBytes = baos.toByteArray();
                        }
                    }

                    Map<String, Object> form = new HashMap<>();
                    form.put("texto_original", new ApiService.FileData("arq.pdf", pdfBytes, "application/pdf"));

                    Integer id = item.getJsonData().has("id") ? item.getJsonData().get("id").asInt() : null;

                    if (id != null) {
                        ApiService.getInstance().patch("materia", "proposicao", id, null, form, null);
                        successCount++;
                        Platform.runLater(() -> log("Documento '" + item.getHeader() + "' enviado com sucesso.\n"));
                    } else {
                        Platform.runLater(() -> log("Erro: ID não encontrado para o documento '" + item.getHeader() + "'.\n"));
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                    Platform.runLater(() -> log("Erro ao enviar documento '" + item.getHeader() + "': " + e.getMessage() + "\n"));
                }
            }

            final int totalSuccess = successCount;
            Platform.runLater(() -> {
                if (totalSuccess > 0) {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Envio Concluído");
                    alert.setHeaderText(null);
                    alert.setContentText(totalSuccess + " documentos enviados com sucesso.");
                    alert.showAndWait();
                    onRefreshDocuments();
                }
            });
        }).start();
    }

    // ─────────────────────────────────────────────────────────────────
    // Métodos do fluxo de Solicitações de Assinatura
    // ─────────────────────────────────────────────────────────────────

    private void initializeSolicitacoesList() {
        if (documentListViewSolicitacoes == null) return;

        ObservableList<SignableItem> items = FXCollections.observableArrayList();
        documentListViewSolicitacoes.setItems(items);
        documentListViewSolicitacoes.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        documentListViewSolicitacoes.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(SignableItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                if (!(item instanceof AssinaturaItem assinaturaItem)) return;

                VBox mainVBox = new VBox(5);

                HBox headerHBox = new HBox(8);
                headerHBox.setAlignment(Pos.CENTER_LEFT);

                CheckBox checkBox = new CheckBox();
                checkBox.selectedProperty().bindBidirectional(item.selectedProperty());
                boolean disabled = isItemDisabled(item);
                checkBox.setDisable(disabled);

                Hyperlink headerLink = new Hyperlink(item.getHeader());
                headerLink.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-border-color: transparent; -fx-padding: 0; -fx-text-fill: #4A1A6B;");
                headerLink.setWrapText(true);
                headerLink.prefWidthProperty().bind(getListView().widthProperty().subtract(120));

                String urlTemp = ConfigService.getInstance().getUrl();
                if (urlTemp != null && urlTemp.endsWith("/")) urlTemp = urlTemp.substring(0, urlTemp.length() - 1);
                JsonNode jsonData = assinaturaItem.getJsonData();
                if (urlTemp != null && jsonData.has("id")) {
                    final String url = urlTemp + "/proposicao/" + jsonData.get("id").asText();
                    headerLink.setOnAction(e -> {
                        try { App.openUrl(url); } catch (Exception ex) { log("Erro ao abrir link: " + ex.getMessage() + "\n"); }
                    });
                }

                String status = assinaturaItem.getStatusAssinatura();
                String badgeTexto = switch (status) {
                    case "A" -> "EM ASSINATURA";
                    case "S" -> "ASSINADO";
                    default  -> "PENDENTE";
                };
                String badgeCor = switch (status) {
                    case "A" -> "-fx-background-color: #e6a817; -fx-text-fill: #5a3e00;";
                    case "S" -> "-fx-background-color: #28a745; -fx-text-fill: white;";
                    default  -> "-fx-background-color: #6c757d; -fx-text-fill: white;";
                };
                Label badgeLabel = new Label(badgeTexto);
                badgeLabel.setStyle(badgeCor + " -fx-font-size: 10px; -fx-font-weight: bold; -fx-padding: 2 6 2 6; -fx-background-radius: 4;");

                headerHBox.getChildren().addAll(checkBox, headerLink, badgeLabel);

                VBox detailsVBox = new VBox(2);
                if (jsonData.has("autor") && !jsonData.get("autor").isNull()) {
                    String nomeAutor = jsonData.get("autor").isObject() && jsonData.get("autor").has("nome")
                            ? jsonData.get("autor").get("nome").asText()
                            : jsonData.get("autor").asText();
                    Label autorLabel = new Label("Autor principal: " + nomeAutor);
                    autorLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #555555;");
                    detailsVBox.getChildren().add(autorLabel);
                }
                Label descLabel = new Label(item.getDescription());
                descLabel.setWrapText(true);
                descLabel.prefWidthProperty().bind(getListView().widthProperty().subtract(65));
                descLabel.setStyle("-fx-text-fill: #666666;");
                detailsVBox.getChildren().add(descLabel);

                mainVBox.getChildren().addAll(headerHBox, detailsVBox);
                setGraphic(mainVBox);
            }
        });

        documentListViewSolicitacoes.getSelectionModel().selectedItemProperty().addListener((obs, old, newItem) -> {
            if (old != null) {
                old.setSavedPageIndex(currentPageIndex);
                old.setSavedRect(lastRect.get());
            }
            if (newItem != null) {
                handleDocumentSelection(newItem);
            }
        });
    }

    @FXML
    private void onRefreshSolicitacoes() {
        if (currentMode != ViewMode.M1) return;
        log("Atualizando solicitações...\n");
        clearPreview();
        clearSolicitacoesListItems();
        refreshSolicitacoesList();
    }

    private void refreshSolicitacoesList() {
        if (documentListViewSolicitacoes == null) return;
        ObservableList<SignableItem> items = documentListViewSolicitacoes.getItems();

        new Thread(() -> {
            try {
                InputStream response = ApiService.getInstance().getSolicitacoes();
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response);

                List<AssinaturaItem> novosItens = new ArrayList<>();
                if (root.has("results") && root.get("results").isArray()) {
                    for (JsonNode node : root.get("results")) {
                        String header = node.has("__str__") ? node.get("__str__").asText() : "";
                        String description = node.has("descricao") ? node.get("descricao").asText() : "";
                        AssinaturaItem item = new AssinaturaItem(header, description, node);
                        preloadPdfSolicitacao(item);
                        novosItens.add(item);
                    }
                }

                Platform.runLater(() -> {
                    items.clear();
                    items.addAll(novosItens);
                    atualizarBadgeSolicitacoes(novosItens.size());
                    log("Solicitações de assinatura carregadas: " + novosItens.size() + ".\n");
                });
            } catch (Exception e) {
                Platform.runLater(() -> log("Erro ao carregar solicitações: " + e.getMessage() + "\n"));
            }
        }).start();
    }

    private void preloadPdfSolicitacao(AssinaturaItem item) {
        JsonNode jsonNode = item.getJsonData();
        if (!jsonNode.has("texto_original")) return;
        String urlString = jsonNode.get("texto_original").asText();
        if (urlString == null || urlString.isBlank() || "null".equals(urlString)) return;

        new Thread(() -> {
            try (InputStream is = getInputStreamFromUrl(urlString)) {
                byte[] bytes = is.readAllBytes();
                item.setOriginalBytes(bytes);
                PDDocument doc = Loader.loadPDF(bytes);
                item.setPdDocument(doc);
            } catch (Exception e) {
                log("Erro ao pré-carregar PDF de solicitação: " + e.getMessage() + "\n");
            }
        }).start();
    }

    private void atualizarBadgeSolicitacoes(int count) {
        Platform.runLater(() -> {
            if (tabSolicitacoes != null) {
                tabSolicitacoes.setText(count > 0 ? "Solicitações (" + count + ")" : "Solicitações");
            }
        });
    }

    // ─── Lock / Countdown / Polling ────────────────────────────────

    private void iniciarCountdown(AssinaturaItem item, ZonedDateTime dataCaptura) {
        pararCountdown();
        if (hBoxLockStatus != null) {
            hBoxLockStatus.setVisible(true);
            hBoxLockStatus.setManaged(true);
            if (lblLockItem != null) lblLockItem.setText(item.getHeader());
        }

        long expiracaoEpoch = dataCaptura.toEpochSecond() + 300; // 5 min

        lockCountdownTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            long restantes = expiracaoEpoch - java.time.Instant.now().getEpochSecond();
            if (restantes <= 0) {
                pararCountdown();
                log("Lock de assinatura expirado.\n");
                return;
            }
            long min = restantes / 60;
            long seg = restantes % 60;
            double progresso = (double) restantes / 300.0;
            if (pbLockCountdown != null) pbLockCountdown.setProgress(progresso);
            if (lblLockTempo != null) lblLockTempo.setText(String.format("%d:%02d restantes", min, seg));
        }));
        lockCountdownTimeline.setCycleCount(Timeline.INDEFINITE);
        lockCountdownTimeline.play();
    }

    private void pararCountdown() {
        if (lockCountdownTimeline != null) {
            lockCountdownTimeline.stop();
            lockCountdownTimeline = null;
        }
        Platform.runLater(() -> {
            if (hBoxLockStatus != null) {
                hBoxLockStatus.setVisible(false);
                hBoxLockStatus.setManaged(false);
            }
        });
    }

    private void pararPolling() {
        if (pollingFuture != null && !pollingFuture.isDone()) {
            pollingFuture.cancel(false);
            pollingFuture = null;
        }
    }

    private void cancelarLockAtivo(boolean liberarNoServidor) {
        pararCountdown();
        pararPolling();
        if (itemComLockAtivo != null && liberarNoServidor) {
            final int id = itemComLockAtivo.getProposicaoId();
            itemComLockAtivo.setStatusAssinatura("P");
            new Thread(() -> {
                try {
                    ApiService.getInstance().liberarAssinatura(id);
                    log("Lock liberado no servidor.\n");
                } catch (Exception e) {
                    log("Aviso: não foi possível liberar o lock no servidor: " + e.getMessage() + "\n");
                }
            }).start();
        }
        if (itemComLockAtivo != null) {
            itemComLockAtivo.setStatusAssinatura("P");
            Platform.runLater(() -> {
                if (documentListViewSolicitacoes != null) documentListViewSolicitacoes.refresh();
            });
        }
        itemComLockAtivo = null;
    }

    @FXML
    private void onLiberarLock() {
        if (itemComLockAtivo == null) return;
        cancelarLockAtivo(true);
        log("Lock liberado pelo usuário.\n");
    }

    private void liberarLockComErro(AssinaturaItem item, String mensagem) {
        cancelarLockAtivo(true);
        Platform.runLater(() -> {
            log("Erro no fluxo de assinatura: " + mensagem + "\n");
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Erro na Assinatura");
            alert.setHeaderText("Operação cancelada");
            alert.setContentText(mensagem);
            alert.showAndWait();
        });
    }

    private void iniciarPolling(AssinaturaItem item, int autorIdLock) {
        final int proposicaoId = item.getProposicaoId();
        final int maxTentativas = 24; // 24 × 5s = 2 min
        final int[] tentativas = {0};
        final ObjectMapper pollingMapper = new ObjectMapper();

        pollingFuture = pollingExecutor.scheduleWithFixedDelay(() -> {
            tentativas[0]++;
            try {
                InputStream is = ApiService.getInstance().getAssinantes(proposicaoId);
                JsonNode assinantes = pollingMapper.readTree(is);

                String statusAtual = null;
                if (assinantes.isArray()) {
                    for (JsonNode a : assinantes) {
                        if (autorIdLock > 0 && a.has("autor_id") && a.get("autor_id").asInt() == autorIdLock) {
                            statusAtual = a.has("status") ? a.get("status").asText() : null;
                            break;
                        }
                    }
                    // Fallback: procura qualquer entrada que não seja P (foi processada)
                    if (statusAtual == null) {
                        for (JsonNode a : assinantes) {
                            String s = a.has("status") ? a.get("status").asText() : "P";
                            if ("S".equals(s)) { statusAtual = "S"; break; }
                        }
                    }
                }

                if ("S".equals(statusAtual)) {
                    pararPolling();
                    item.setStatusAssinatura("S");
                    Platform.runLater(() -> {
                        log("Assinatura confirmada pelo servidor!\n");
                        Alert alert = new Alert(Alert.AlertType.INFORMATION);
                        alert.setTitle("Assinatura Confirmada");
                        alert.setHeaderText(null);
                        alert.setContentText("A assinatura foi validada e confirmada pelo servidor.");
                        alert.showAndWait();
                        onRefreshSolicitacoes();
                    });
                    itemComLockAtivo = null;
                } else if ("P".equals(statusAtual) && tentativas[0] > 2) {
                    // Lock liberado pelo servidor → CN não coincidiu
                    pararPolling();
                    itemComLockAtivo = null;
                    item.setStatusAssinatura("P");
                    Platform.runLater(() -> {
                        log("O servidor rejeitou a assinatura: o CN do certificado não coincide com o cadastrado.\n");
                        Alert alert = new Alert(Alert.AlertType.WARNING);
                        alert.setTitle("Assinatura Rejeitada");
                        alert.setHeaderText("O servidor não confirmou a assinatura");
                        alert.setContentText("O CN do certificado utilizado não corresponde ao certificado cadastrado para o seu usuário.\n\nContate o administrador do sistema para verificar o campo 'Certificado CN'.");
                        alert.showAndWait();
                        if (documentListViewSolicitacoes != null) documentListViewSolicitacoes.refresh();
                    });
                } else if (tentativas[0] >= maxTentativas) {
                    pararPolling();
                    itemComLockAtivo = null;
                    Platform.runLater(() -> {
                        log("Timeout do polling. Verifique o status no portal.\n");
                        Alert alert = new Alert(Alert.AlertType.INFORMATION);
                        alert.setTitle("Verificação Pendente");
                        alert.setHeaderText(null);
                        alert.setContentText("O servidor ainda está processando a assinatura. Verifique o status da proposição no portal.");
                        alert.showAndWait();
                        onRefreshSolicitacoes();
                    });
                }
            } catch (Exception e) {
                log("Erro no polling de status: " + e.getMessage() + "\n");
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private String calcularHashSHA256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        return HexFormat.of().formatHex(hash);
    }

    // ─── Fluxo principal de assinatura de solicitação ──────────────

    private record A3Credenciais(String libPath, char[] pin) {}
    private record A1Credenciais(java.io.File certFile, char[] senha) {}

    /** Coleta credenciais A3 na thread FX. Retorna null se o usuário cancelar. */
    private A3Credenciais promptA3Credenciais() {
        TokenService tokenService = new TokenService();
        List<String> detectedLibs = tokenService.detectLibraries();
        String libPath = null;

        if (detectedLibs.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Driver não encontrado");
            alert.setHeaderText("Driver do token não detectado automaticamente.");
            alert.setContentText("Deseja selecionar o arquivo do driver (DLL/SO) manualmente?");
            Optional<ButtonType> res = alert.showAndWait();
            if (res.isPresent() && res.get() == ButtonType.OK) {
                FileChooser fc = new FileChooser();
                fc.setTitle("Selecionar Driver do Token");
                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Bibliotecas Windows", "*.dll"));
                } else {
                    fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Bibliotecas Linux", "*.so"));
                }
                java.io.File f = fc.showOpenDialog(documentListViewSolicitacoes.getScene().getWindow());
                if (f != null) libPath = f.getAbsolutePath();
            }
        } else {
            libPath = null; // usa detecção automática
        }

        if (detectedLibs.isEmpty() && libPath == null) return null;

        String pin = solicitarSenha();
        if (pin == null) return null;
        return new A3Credenciais(libPath, pin.toCharArray());
    }

    /** Coleta credenciais A1 na thread FX. Retorna null se o usuário cancelar. */
    private A1Credenciais promptA1Credenciais() {
        String certPath = configService.getCertPath();
        java.io.File certFile = null;

        if (certPath != null && !certPath.isEmpty()) {
            java.io.File f = new java.io.File(certPath);
            if (f.exists()) certFile = f;
        }
        if (certFile == null) {
            FileChooser fc = new FileChooser();
            fc.setTitle("Selecionar Certificado Digital (.pfx)");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Certificado PKCS#12", "*.pfx", "*.p12"));
            certFile = fc.showOpenDialog(documentListViewSolicitacoes.getScene().getWindow());
            if (certFile == null) return null;
        }

        String senha = configService.getCertPassword();
        if (senha == null || senha.isEmpty()) {
            senha = solicitarSenha();
        }
        if (senha == null) return null;
        return new A1Credenciais(certFile, senha.toCharArray());
    }

    private void onSignSolicitacao() {
        if (documentListViewSolicitacoes == null) return;

        List<SignableItem> selecionados = documentListViewSolicitacoes.getItems().stream()
                .filter(SignableItem::isSelected)
                .collect(Collectors.toList());

        if (selecionados.size() != 1) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Aviso");
            alert.setHeaderText(null);
            alert.setContentText(selecionados.isEmpty()
                    ? "Selecione uma proposição para assinar."
                    : "Selecione apenas uma proposição por vez para solicitações de assinatura.");
            alert.showAndWait();
            return;
        }

        if (!(selecionados.get(0) instanceof AssinaturaItem item)) return;

        // Escolha de tipo de certificado
        Alert typeAlert = new Alert(Alert.AlertType.CONFIRMATION);
        typeAlert.setTitle("Tipo de Certificado");
        typeAlert.setHeaderText("Assinatura de solicitação: " + item.getHeader());
        typeAlert.setContentText("Qual tipo de certificado deseja usar?");
        ButtonType btnA1 = new ButtonType("Arquivo (A1)");
        ButtonType btnA3 = new ButtonType("Token (A3)");
        ButtonType btnCancel = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        typeAlert.getButtonTypes().setAll(btnA1, btnA3, btnCancel);
        Optional<ButtonType> typeResult = typeAlert.showAndWait();
        if (typeResult.isEmpty() || typeResult.get() == btnCancel) return;

        boolean useA3 = typeResult.get() == btnA3;

        // Coleta credenciais na thread FX
        final KeyStore[] ksHolder = {null};
        final String[] aliasHolder = {null};
        final char[][] pinHolder = {null};
        final TokenService[] tokenServiceHolder = {null};
        final String[] libPathHolder = {null};

        if (useA3) {
            A3Credenciais creds = promptA3Credenciais();
            if (creds == null) { log("Operação cancelada.\n"); return; }
            tokenServiceHolder[0] = new TokenService();
            libPathHolder[0] = creds.libPath();
            pinHolder[0] = creds.pin();
        } else {
            A1Credenciais creds = promptA1Credenciais();
            if (creds == null) { log("Operação cancelada.\n"); return; }
            try {
                KeyStore ks = KeyStore.getInstance("PKCS12");
                try (FileInputStream fis = new FileInputStream(creds.certFile())) {
                    ks.load(fis, creds.senha());
                }
                String alias = null;
                Enumeration<String> aliases = ks.aliases();
                while (aliases.hasMoreElements()) {
                    String a = aliases.nextElement();
                    if (ks.isKeyEntry(a)) { alias = a; break; }
                }
                if (alias == null) {
                    new Alert(Alert.AlertType.ERROR, "Nenhuma chave privada encontrada no certificado.").showAndWait();
                    return;
                }
                ksHolder[0] = ks;
                aliasHolder[0] = alias;
                pinHolder[0] = creds.senha();
            } catch (Exception e) {
                new Alert(Alert.AlertType.ERROR, "Erro ao carregar certificado: " + e.getMessage()).showAndWait();
                return;
            }
        }

        log("Iniciando fluxo de assinatura colaborativa para: " + item.getHeader() + "\n");

        new Thread(() -> {
            // 1. Garantir PDF baixado
            if (item.getOriginalBytes() == null) {
                Platform.runLater(() -> log("Baixando PDF da proposição...\n"));
                try {
                    JsonNode jsonNode = item.getJsonData();
                    String urlStr = jsonNode.has("texto_original") ? jsonNode.get("texto_original").asText() : null;
                    if (urlStr == null || urlStr.isBlank() || "null".equals(urlStr)) {
                        try (InputStream is = ApiService.getInstance().get("materia", "proposicao", item.getProposicaoId(), null, null)) {
                            JsonNode detalhe = new ObjectMapper().readTree(is);
                            urlStr = detalhe.has("texto_original") ? detalhe.get("texto_original").asText() : null;
                        }
                    }
                    if (urlStr == null || urlStr.isBlank() || "null".equals(urlStr)) {
                        Platform.runLater(() -> {
                            Alert a = new Alert(Alert.AlertType.ERROR);
                            a.setTitle("Erro"); a.setHeaderText(null);
                            a.setContentText("A proposição não possui arquivo PDF disponível para assinatura.");
                            a.showAndWait();
                        });
                        return;
                    }
                    try (InputStream is = getInputStreamFromUrl(urlStr)) {
                        byte[] bytes = is.readAllBytes();
                        item.setOriginalBytes(bytes);
                        item.setPdDocument(Loader.loadPDF(bytes));
                    }
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert a = new Alert(Alert.AlertType.ERROR);
                        a.setTitle("Erro"); a.setHeaderText(null);
                        a.setContentText("Erro ao baixar o PDF: " + e.getMessage());
                        a.showAndWait();
                    });
                    return;
                }
            }

            // 2. Capturar lock
            Platform.runLater(() -> log("Capturando lock de assinatura...\n"));
            JsonNode lockResp;
            try {
                lockResp = ApiService.getInstance().capturarAssinatura(item.getProposicaoId());
            } catch (Exception e) {
                String msg = e.getMessage();
                Platform.runLater(() -> {
                    String msgUsuario = (msg != null && msg.contains("409"))
                            ? "Outro vereador está assinando esta proposição no momento. Aguarde e tente novamente."
                            : "Não foi possível iniciar a assinatura: " + msg;
                    Alert a = new Alert(Alert.AlertType.ERROR);
                    a.setTitle("Erro"); a.setHeaderText("Falha ao capturar assinatura");
                    a.setContentText(msgUsuario); a.showAndWait();
                });
                return;
            }

            String hashCode = lockResp.has("hash_code") ? lockResp.get("hash_code").asText() : null;
            ZonedDateTime dataCaptura = null;
            try {
                if (lockResp.has("data_captura")) dataCaptura = ZonedDateTime.parse(lockResp.get("data_captura").asText());
            } catch (Exception ignored) {}

            item.setStatusAssinatura("A");
            itemComLockAtivo = item;
            final ZonedDateTime finalDataCaptura = dataCaptura;

            // Buscar autorId do lock para polling
            int autorIdLock = -1;
            try {
                InputStream isAs = ApiService.getInstance().getAssinantes(item.getProposicaoId());
                JsonNode assinantes = new ObjectMapper().readTree(isAs);
                if (assinantes.isArray()) {
                    for (JsonNode a : assinantes) {
                        if ("A".equals(a.has("status") ? a.get("status").asText() : "")) {
                            autorIdLock = a.has("autor_id") ? a.get("autor_id").asInt() : -1;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
            item.setAutorIdEmAssinatura(autorIdLock);
            final int finalAutorIdLock = autorIdLock;

            Platform.runLater(() -> {
                if (documentListViewSolicitacoes != null) documentListViewSolicitacoes.refresh();
                if (finalDataCaptura != null) iniciarCountdown(item, finalDataCaptura);
                log("Lock capturado. Verificando integridade do arquivo...\n");
            });

            // 3. Verificar hash SHA-256
            if (hashCode != null && !hashCode.isBlank()) {
                try {
                    String hashLocal = calcularHashSHA256(item.getOriginalBytes());
                    if (!hashCode.equalsIgnoreCase(hashLocal)) {
                        liberarLockComErro(item, "O arquivo foi alterado no servidor desde que foi baixado. Atualize a lista e tente novamente.");
                        return;
                    }
                } catch (Exception e) {
                    liberarLockComErro(item, "Erro ao verificar integridade do arquivo: " + e.getMessage());
                    return;
                }
            }

            Platform.runLater(() -> log("Integridade verificada. Assinando...\n"));

            // 4. Abrir KeyStore A3 (se necessário) e assinar
            try {
                if (useA3) {
                    TokenService ts = tokenServiceHolder[0];
                    KeyStore ks = (libPathHolder[0] != null)
                            ? ts.getKeyStore(libPathHolder[0], pinHolder[0])
                            : ts.getKeyStore(pinHolder[0]);
                    String alias = null;
                    Enumeration<String> aliases = ks.aliases();
                    while (aliases.hasMoreElements()) { alias = aliases.nextElement(); break; }
                    if (alias == null) throw new Exception("Nenhum certificado encontrado no Token.");
                    ksHolder[0] = ks;
                    aliasHolder[0] = alias;
                }

                AssinaturaService service = new AssinaturaService();
                service.assinarDocumentos(List.of(item), ksHolder[0], aliasHolder[0], pinHolder[0]);
            } catch (Exception e) {
                liberarLockComErro(item, "Erro ao assinar o documento: " + e.getMessage());
                return;
            }

            Platform.runLater(() -> log("Documento assinado. Enviando ao servidor...\n"));

            // 5. Upload do PDF assinado
            try {
                byte[] pdfBytes = item.getSignedBytes();
                if (pdfBytes == null && item.getPdDocumentSigned() != null) {
                    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        item.getPdDocumentSigned().save(baos);
                        pdfBytes = baos.toByteArray();
                    }
                }
                if (pdfBytes == null) {
                    liberarLockComErro(item, "Nenhum PDF assinado disponível para envio.");
                    return;
                }
                Map<String, Object> form = new HashMap<>();
                form.put("texto_original", new ApiService.FileData("arq.pdf", pdfBytes, "application/pdf"));
                ApiService.getInstance().patch("materia", "proposicao", item.getProposicaoId(), null, form, null);
            } catch (Exception e) {
                liberarLockComErro(item, "Erro ao enviar o documento assinado: " + e.getMessage());
                return;
            }

            Platform.runLater(() -> {
                pararCountdown();
                log("Documento enviado. Aguardando confirmação do servidor...\n");
            });

            // 6. Iniciar polling
            iniciarPolling(item, finalAutorIdLock);

        }).start();
    }

    @FXML
    private void onSign() {
        // Delegar para o fluxo de solicitação se a aba Solicitações estiver ativa
        if (currentMode == ViewMode.M1
                && tabPaneM1 != null
                && tabPaneM1.getSelectionModel().getSelectedItem() == tabSolicitacoes) {
            onSignSolicitacao();
            return;
        }

        List<SignableItem> selectedItems = documentListView.getItems().stream()
            .filter(SignableItem::isSelected)
            .collect(Collectors.toList());

        if (selectedItems.isEmpty()) {
            log("Nenhum documento selecionado para assinatura.\n");
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Aviso");
            alert.setHeaderText(null);
            alert.setContentText("Selecione pelo menos um documento para assinar.");
            alert.showAndWait();
            return;
        }

        Alert typeAlert = new Alert(Alert.AlertType.CONFIRMATION);
        typeAlert.setTitle("Tipo de Certificado");
        typeAlert.setHeaderText("Selecione o tipo de certificado");
        typeAlert.setContentText("Qual tipo de certificado deseja usar?");

        ButtonType btnA1 = new ButtonType("Arquivo (A1)");
        ButtonType btnA3 = new ButtonType("Token (A3)");
        ButtonType btnCancel = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);

        typeAlert.getButtonTypes().setAll(btnA1, btnA3, btnCancel);

        Optional<ButtonType> typeResult = typeAlert.showAndWait();
        if (typeResult.isEmpty() || typeResult.get() == btnCancel) {
            return;
        }

        if (typeResult.get() == btnA1) {
            signA1(selectedItems);
        } else {
            signA3(selectedItems);
        }
    }

    private void signA3(List<SignableItem> selectedItems) {
        TokenService tokenService = new TokenService();
        List<String> detectedLibs = tokenService.detectLibraries();
        String manualLibPath = null;

        if (detectedLibs.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Driver não encontrado");
            alert.setHeaderText("Driver do token não detectado automaticamente.");
            alert.setContentText("Deseja selecionar o arquivo do driver (DLL/SO) manualmente?");
            Optional<ButtonType> res = alert.showAndWait();
            if (res.isPresent() && res.get() == ButtonType.OK) {
                FileChooser fileChooser = new FileChooser();
                fileChooser.setTitle("Selecionar Driver do Token");
                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Bibliotecas Windows", "*.dll"));
                } else {
                    fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Bibliotecas Linux", "*.so"));
                }
                File f = fileChooser.showOpenDialog(documentListView.getScene().getWindow());
                if (f != null) {
                    manualLibPath = f.getAbsolutePath();
                }
            }
        }

        if (detectedLibs.isEmpty() && manualLibPath == null) {
            log("Operação cancelada: Driver não selecionado.\n");
            return;
        }

        String pin = solicitarSenha();
        if (pin == null) {
            return;
        }

        final String finalManualLibPath = manualLibPath;
        final String finalPin = pin;

        log("Iniciando assinatura com Token A3...\n");

        new Thread(() -> {
            try {
                KeyStore ks;
                if (finalManualLibPath != null) {
                    ks = tokenService.getKeyStore(finalManualLibPath, finalPin.toCharArray());
                } else {
                    ks = tokenService.getKeyStore(finalPin.toCharArray());
                }

                String alias = null;
                Enumeration<String> aliases = ks.aliases();
                while (aliases.hasMoreElements()) {
                    alias = aliases.nextElement();
                    break;
                }

                if (alias == null) {
                    throw new Exception("Nenhum certificado encontrado no Token.");
                }

                AssinaturaService service = new AssinaturaService();
                service.assinarDocumentos(selectedItems, ks, alias, finalPin.toCharArray());

                Platform.runLater(() -> {
                    log("Sucesso! " + selectedItems.size() + " documentos assinados (A3).\n");
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Sucesso");
                    alert.setHeaderText(null);
                    alert.setContentText("Documentos assinados com sucesso!");
                    alert.showAndWait();
                });

            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    log("Erro ao assinar (A3): " + e.getMessage() + "\n");
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Erro");
                    alert.setHeaderText("Falha na assinatura A3");
                    alert.setContentText(e.getMessage());
                    alert.showAndWait();
                });
            }
        }).start();
    }

    private void signA1(List<SignableItem> selectedItems) {
        String certPath = configService.getCertPath();
        File certificadoFile;

        if (certPath != null && !certPath.isEmpty()) {
            certificadoFile = new File(certPath);
            if (!certificadoFile.exists()) {
                log("Certificado configurado não encontrado: " + certPath + "\n");
                certificadoFile = null;
            } else {
                log("Usando certificado configurado: " + certificadoFile.getName() + "\n");
            }
        } else {
            certificadoFile = null;
        }

        if (certificadoFile == null) {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Selecionar Certificado Digital (.pfx)");
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Certificado PKCS#12", "*.pfx", "*.p12"));
            certificadoFile = fileChooser.showOpenDialog(documentListView.getScene().getWindow());

            if (certificadoFile == null) {
                log("Seleção de certificado cancelada.\n");
                return;
            }
        }

        String senhaTemp = configService.getCertPassword();
        if (senhaTemp == null || senhaTemp.isEmpty()) {
            senhaTemp = solicitarSenha();
        }

        if (senhaTemp == null) {
            log("Operação cancelada pelo usuário.\n");
            return;
        }

        final String senha = senhaTemp;

        log("Iniciando processo de assinatura para " + selectedItems.size() + " documentos...\n");

        final File finalCertificadoFile = certificadoFile;

        new Thread(() -> {
            try {
                KeyStore ks = KeyStore.getInstance("PKCS12");
                try (FileInputStream fis = new FileInputStream(finalCertificadoFile)) {
                    ks.load(fis, senha.toCharArray());
                }

                String alias = null;
                Enumeration<String> aliases = ks.aliases();
                while (aliases.hasMoreElements()) {
                    String a = aliases.nextElement();
                    if (ks.isKeyEntry(a)) {
                        alias = a;
                        break;
                    }
                }

                if (alias == null) {
                    throw new Exception("Nenhuma chave privada encontrada no certificado.");
                }

                AssinaturaService service = new AssinaturaService();
                service.assinarDocumentos(selectedItems, ks, alias, senha.toCharArray());

                Platform.runLater(() -> {
                    log("Sucesso! " + selectedItems.size() + " documentos assinados.\n");
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Sucesso");
                    alert.setHeaderText(null);
                    alert.setContentText("Documentos assinados com sucesso!");
                    alert.showAndWait();
                });

            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    log("Erro ao assinar: " + e.getMessage() + "\n");
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Erro");
                    alert.setHeaderText("Falha na assinatura");
                    alert.setContentText(e.getMessage());
                    alert.showAndWait();
                });
            }
        }).start();
    }

    private String solicitarSenha() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Senha do Certificado");
        dialog.setHeaderText("Digite a senha do certificado digital:");

        ButtonType okButtonType = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okButtonType, ButtonType.CANCEL);

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Senha");

        VBox content = new VBox(10);
        content.getChildren().addAll(new Label("Senha:"), passwordField);

        dialog.getDialogPane().setContent(content);

        Platform.runLater(passwordField::requestFocus);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == okButtonType) {
                return passwordField.getText();
            }
            return null;
        });

        Optional<String> result = dialog.showAndWait();
        return result.orElse(null);
    }

    @FXML
    private void onSelectAll() {
        if (isUpdatingSelectAll) {
            return;
        }

        CheckBox activeSelectAll = getActiveSelectAllCheckBox();
        if (activeSelectAll == null) {
            return;
        }

        boolean select = activeSelectAll.isSelected();

        if (activeSelectAll.isIndeterminate()) {
            select = true;
            activeSelectAll.setIndeterminate(false);
            activeSelectAll.setSelected(true);
        }

        isUpdatingSelectAll = true;
        try {
            for (SignableItem item : documentListView.getItems()) {
                if (!isItemDisabled(item)) {
                    item.setSelected(select);
                }
            }
        } finally {
            isUpdatingSelectAll = false;
        }

        setSelectAllState(select, false);
    }

    private CheckBox getActiveSelectAllCheckBox() {
        return currentMode == ViewMode.M1 ? chkSelectAll : chkSelectAllM2;
    }

    private void setSelectAllState(boolean selected, boolean indeterminate) {
        if (chkSelectAll != null) {
            chkSelectAll.setSelected(selected);
            chkSelectAll.setIndeterminate(indeterminate);
        }
        if (chkSelectAllM2 != null) {
            chkSelectAllM2.setSelected(selected);
            chkSelectAllM2.setIndeterminate(indeterminate);
        }
    }

    private void updateSelectAllState() {
        if (isUpdatingSelectAll) {
            return;
        }

        List<SignableItem> items = documentListView.getItems();
        if (items.isEmpty()) {
            setSelectAllState(false, false);
            return;
        }

        long totalEnabled = items.stream().filter(i -> !isItemDisabled(i)).count();
        long totalSelected = items.stream().filter(i -> !isItemDisabled(i) && i.isSelected()).count();

        isUpdatingSelectAll = true;
        try {
            if (totalEnabled == 0 || totalSelected == 0) {
                setSelectAllState(false, false);
            } else if (totalSelected == totalEnabled) {
                setSelectAllState(true, false);
            } else {
                setSelectAllState(false, true);
            }
        } finally {
            isUpdatingSelectAll = false;
        }
    }

    private boolean isItemDisabled(SignableItem item) {
        if (item instanceof DocumentItem documentItem) {
            JsonNode jsonData = documentItem.getJsonData();
            return jsonData != null && jsonData.has("data_envio") && !jsonData.get("data_envio").isNull();
        }
        if (item instanceof AssinaturaItem assinaturaItem) {
            // Itens já assinados não são editáveis (não devem aparecer, mas defesa)
            return "S".equals(assinaturaItem.getStatusAssinatura());
        }
        return false;
    }

    private void removeFileItem(FileItem fileItem) {
        if (fileItem == null) {
            return;
        }

        SignableItem selectedItem = documentListView.getSelectionModel().getSelectedItem();
        boolean removePreview = selectedItem == fileItem;

        closeDocumentIfNecessary(fileItem.getPdDocument());
        closeDocumentIfNecessary(fileItem.getPdDocumentSigned());
        fileItem.setPdDocument(null);
        fileItem.setPdDocumentSigned(null);
        fileItem.setOriginalBytes(null);
        fileItem.setSignedBytes(null);

        documentListView.getItems().remove(fileItem);

        if (removePreview) {
            clearPreview();
        }

        updateSelectAllState();
        log("Arquivo removido da lista: " + fileItem.getHeader() + "\n");
    }

    public interface SignableItem {
        String getHeader();
        String getDescription();
        boolean isSelected();
        void setSelected(boolean selected);
        BooleanProperty selectedProperty();
        int getSavedPageIndex();
        void setSavedPageIndex(int savedPageIndex);
        Rectangle getSavedRect();
        void setSavedRect(Rectangle savedRect);
        PDDocument getPdDocument();
        void setPdDocument(PDDocument pdDocument);
        PDDocument getPdDocumentSigned();
        void setPdDocumentSigned(PDDocument pdDocumentSigned);
        byte[] getOriginalBytes();
        void setOriginalBytes(byte[] originalBytes);
        byte[] getSignedBytes();
        void setSignedBytes(byte[] signedBytes);
    }

    public abstract static class BaseSignableItem implements SignableItem {
        private final String header;
        private final String description;
        private final BooleanProperty selected = new SimpleBooleanProperty(false);
        private int savedPageIndex = 0;
        private Rectangle savedRect = null;
        private PDDocument pdDocument;
        private PDDocument pdDocumentSigned;
        private byte[] originalBytes;
        private byte[] signedBytes;

        protected BaseSignableItem(String header, String description) {
            this.header = header;
            this.description = description;
        }

        @Override
        public String getHeader() {
            return header;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public boolean isSelected() {
            return selected.get();
        }

        @Override
        public void setSelected(boolean selected) {
            this.selected.set(selected);
        }

        @Override
        public BooleanProperty selectedProperty() {
            return selected;
        }

        @Override
        public int getSavedPageIndex() {
            return savedPageIndex;
        }

        @Override
        public void setSavedPageIndex(int savedPageIndex) {
            this.savedPageIndex = savedPageIndex;
        }

        @Override
        public Rectangle getSavedRect() {
            return savedRect;
        }

        @Override
        public void setSavedRect(Rectangle savedRect) {
            this.savedRect = savedRect;
        }

        @Override
        public PDDocument getPdDocument() {
            return pdDocument;
        }

        @Override
        public void setPdDocument(PDDocument pdDocument) {
            this.pdDocument = pdDocument;
        }

        @Override
        public PDDocument getPdDocumentSigned() {
            return pdDocumentSigned;
        }

        @Override
        public void setPdDocumentSigned(PDDocument pdDocumentSigned) {
            this.pdDocumentSigned = pdDocumentSigned;
        }

        @Override
        public byte[] getOriginalBytes() {
            return originalBytes;
        }

        @Override
        public void setOriginalBytes(byte[] originalBytes) {
            this.originalBytes = originalBytes;
        }

        @Override
        public byte[] getSignedBytes() {
            return signedBytes;
        }

        @Override
        public void setSignedBytes(byte[] signedBytes) {
            this.signedBytes = signedBytes;
        }
    }

    public static class DocumentItem extends BaseSignableItem {
        private final JsonNode jsonData;

        public DocumentItem(String header, String description, JsonNode jsonData) {
            super(header, description);
            this.jsonData = jsonData;
        }

        public JsonNode getJsonData() {
            return jsonData;
        }
    }

    public static class FileItem extends BaseSignableItem {
        private final File sourceFile;

        public FileItem(String header, String description, File sourceFile) {
            super(header, description);
            this.sourceFile = sourceFile;
        }

        public File getSourceFile() {
            return sourceFile;
        }
    }

    /** Item representando uma proposição em que o usuário logado é co-signatário. */
    public static class AssinaturaItem extends BaseSignableItem {
        private final JsonNode jsonData;
        private final int proposicaoId;
        /** Status do co-signatário logado: "P" (Pendente), "A" (Em Assinatura), "S" (Assinado). */
        private String statusAssinatura = "P";
        /** autor_id do assinante que detém o lock ativo (obtido via GET /assinantes/). */
        private int autorIdEmAssinatura = -1;

        public AssinaturaItem(String header, String description, JsonNode jsonData) {
            super(header, description);
            this.jsonData = jsonData;
            this.proposicaoId = jsonData.has("id") ? jsonData.get("id").asInt() : -1;
        }

        public JsonNode getJsonData() { return jsonData; }
        public int getProposicaoId() { return proposicaoId; }
        public String getStatusAssinatura() { return statusAssinatura; }
        public void setStatusAssinatura(String statusAssinatura) { this.statusAssinatura = statusAssinatura; }
        public int getAutorIdEmAssinatura() { return autorIdEmAssinatura; }
        public void setAutorIdEmAssinatura(int autorIdEmAssinatura) { this.autorIdEmAssinatura = autorIdEmAssinatura; }
    }
}
