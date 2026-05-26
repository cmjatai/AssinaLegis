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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        setupViewer();
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
            btnModeM1.setDisable(m1 || !tokenConfigurado);
        }
        if (btnModeM2 != null) {
            btnModeM2.setDisable(!m1);
        }

        updateSelectAllState();
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

        clearPreview();
        clearItemsAndDocuments();
        log("Carregando " + arquivos.size() + " arquivo(s) local(is)...\n");

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
        updateSelectAllState();
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
                            origemLabel.prefWidthProperty().bind(getListView().widthProperty().subtract(65));
                            origemLabel.styleProperty().bind(
                                javafx.beans.binding.Bindings.when(selectedProperty())
                                    .then("-fx-font-size: 11px; -fx-text-fill: -fx-selection-bar-text;")
                                    .otherwise("-fx-font-size: 11px; -fx-text-fill: #555555;")
                            );

                            Label descLabel = new Label(item.getDescription());
                            descLabel.setWrapText(true);
                            descLabel.prefWidthProperty().bind(getListView().widthProperty().subtract(65));
                            descLabel.styleProperty().bind(
                                javafx.beans.binding.Bindings.when(selectedProperty())
                                    .then("-fx-text-fill: -fx-selection-bar-text;")
                                    .otherwise("-fx-text-fill: #666666;")
                            );

                            detailsVBox.getChildren().addAll(origemLabel, descLabel);
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

    @FXML
    private void onSign() {
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
        return false;
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
}
