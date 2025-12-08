package br.leg.go.jatai.assinalegis;

import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Optional;
import java.util.Enumeration;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import javafx.stage.FileChooser;

public class DocumentViewerController {

    @FXML private ListView<DocumentItem> documentListView;
    @FXML private ScrollPane scrollPane;
    @FXML private StackPane contentHolder;
    @FXML private CheckBox chkSelectAll;

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

    @FXML
    public void initialize() {
        configService = ConfigService.getInstance();
        if (chkSelectAll != null) {
            chkSelectAll.setAllowIndeterminate(true);
        }
        initializeDocumentList();
        setupViewer();
        onRefreshDocuments();
        updateNavigationButtons();
    }

    public void setLogAction(Consumer<String> logAction) {
        this.logAction = logAction;
    }

    private void log(String message) {
        if (logAction != null) {
            Platform.runLater(() -> logAction.accept(message));
        }
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

        // Bind das dimensões do wrapper ao tamanho escalado da imagem
        imageWrapper.minWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
            () -> imageView.getImage() != null ? imageView.getImage().getWidth() * zoomProperty.get() : 0.0,
            zoomProperty, imageView.imageProperty()));
        imageWrapper.minHeightProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
            () -> imageView.getImage() != null ? imageView.getImage().getHeight() * zoomProperty.get() : 0.0,
            zoomProperty, imageView.imageProperty()));

        imageWrapper.maxWidthProperty().bind(imageWrapper.minWidthProperty());
        imageWrapper.maxHeightProperty().bind(imageWrapper.minHeightProperty());

        contentHolder.getChildren().add(imageWrapper);

        // Dimensões do retângulo em pixels (200 DPI)
        double rectWidth = (6.0 / 2.54) * 200;
        double rectHeight = (1.7 / 2.54) * 200;

        // Eventos de Mouse no Group
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

                // recupera o documentItem selecionado
                DocumentItem item = documentListView.getSelectionModel().getSelectedItem();
                if (item == null) {
                    log("Nenhum documento selecionado para adicionar a marcação.\n");
                    return;
                }
                // Verifica se o documento já foi enviado, desabilitando a marcação se for o caso
                boolean isDisabled = false;
                JsonNode jsonData = item.getJsonData();
                if (jsonData.has("data_envio") && !jsonData.get("data_envio").isNull()) {
                    isDisabled = true;
                }
                if (isDisabled) {
                    log("O documento selecionado já foi enviado e não pode ser marcado.\n");
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

        // Adiciona suporte a Zoom com Ctrl + Scroll no ScrollPane
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
        log("Atualizando lista de documentos...\n");
        clearPreview();
        refreshDocumentList();
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
            if (imageView != null) imageView.setImage(null);
            if (lastRect.get() != null && group != null) {
                group.getChildren().remove(lastRect.get());
                lastRect.set(null);
            }
            updateNavigationButtons();
        });
    }

    private void refreshDocumentList() {

        ObservableList<DocumentItem> items = documentListView.getItems();
        items.clear();
        new Thread(() -> {
            try {
                Map<String, Object> params = new HashMap<>();
                params.put("o", "-data_envio,-id");
                params.put("page_size", 100);
                //params.put("data_envio__isnull", "True");
                //params.put("data_recebimento__isnull", "True");
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

                            // Adiciona listener para atualizar o "Selecionar Todos" quando um item mudar
                            item.selectedProperty().addListener((obs, wasSelected, isSelected) -> updateSelectAllState());

                            items.add(item);
                        }
                    }
                    updateSelectAllState(); // Atualiza estado inicial após carregar
                    log("Lista de documentos atualizada com " + items.size() + " itens.\n");
                });

            } catch (Exception e) {
                e.printStackTrace();
                log("Erro ao atualizar documentos: " + e.getMessage() + "\n");
            }
        }).start();
    }

    private InputStream getInputStreamFromUrl(String urlString) throws IOException {
        if (urlString != "null" && urlString != null && !urlString.isEmpty()) {
            URL url = java.net.URI.create(urlString).toURL();
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
            String token = configService.getToken();
            if (token != null && !token.isEmpty()) {
                connection.setRequestProperty("Authorization", "Token " + token);
            }
            return connection.getInputStream();
        } else {
            throw new IOException("URL inválida para obter InputStream: " + urlString);
        }
    }

    private void preloadPdf(DocumentItem item) {
        JsonNode jsonNode = item.getJsonData();
        if (jsonNode.has("texto_original")) {
            String urlString = jsonNode.get("texto_original").asText();
            if (urlString != "null" && urlString != null && !urlString.isEmpty()) {
                new Thread(() -> {
                    try {
                        try (InputStream is = getInputStreamFromUrl(urlString)) {
                            byte[] bytes = is.readAllBytes();
                            item.setOriginalBytes(bytes);
                            PDDocument doc = org.apache.pdfbox.Loader.loadPDF(bytes);
                            item.setPdDocument(doc);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        log("Erro ao pré-carregar PDF: " + e.getMessage() + "\n");
                    }
                }).start();
            }
        }
    }

    private void initializeDocumentList() {
        ObservableList<DocumentItem> items = FXCollections.observableArrayList();
        documentListView.setItems(items);
        documentListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        documentListView.setCellFactory(param -> {
            ListCell<DocumentItem> cell = new ListCell<DocumentItem>() {
                @Override
                protected void updateItem(DocumentItem item, boolean empty) {
                    super.updateItem(item, empty);

                    if (empty || item == null) {
                        setText(null);
                        setGraphic(null);
                    } else {
                        VBox mainVBox = new VBox(5);

                        // HBox para CheckBox e Header
                        HBox headerHBox = new HBox(10);
                        headerHBox.setAlignment(Pos.CENTER_LEFT);

                        CheckBox checkBox = new CheckBox();
                        checkBox.selectedProperty().bindBidirectional(item.selectedProperty());

                        // Desabilita o checkbox se data_envio não for nulo
                        JsonNode jsonData = item.getJsonData();
                        boolean hasDataEnvio = jsonData.has("data_envio") && !jsonData.get("data_envio").isNull();
                        checkBox.setDisable(hasDataEnvio);
                        if (hasDataEnvio) {
                            checkBox.setTooltip(new Tooltip("Este documento já foi enviado e não pode ser selecionado."));
                        }

                        Hyperlink headerLink = new Hyperlink(item.getHeader());
                        headerLink.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-border-color: transparent; -fx-padding: 0;");
                        headerLink.setWrapText(true);

                        String urlTemp = ConfigService.getInstance().getUrl();
                        if (urlTemp.endsWith("/")) {
                            urlTemp = urlTemp.substring(0, urlTemp.length() - 1);
                        }
                        if (hasDataEnvio) {
                            headerLink.setStyle(headerLink.getStyle() + " -fx-text-fill: #640606ff;");
                            headerLink.setTooltip(new Tooltip("Este documento já foi enviado."));
                            urlTemp += "/materia/" + jsonData.get("object_id").asText();
                        } else {
                            headerLink.setStyle(headerLink.getStyle() + " -fx-text-fill: #064664ff;");
                            urlTemp += "/proposicao/" + jsonData.get("id").asText();
                        }

                        final String url = urlTemp;
                        // Ação ao clicar no link
                        headerLink.setOnAction(e -> {
                            try {
                                App.openUrl(url);
                            } catch (Exception ex) {
                                log("Erro ao abrir link: " + ex.getMessage() + "\n");
                            }
                        });

                        // Vincula a largura do header para evitar scroll horizontal
                        headerLink.prefWidthProperty().bind(getListView().widthProperty().subtract(65));

                        headerHBox.getChildren().addAll(checkBox, headerLink);
                        mainVBox.getChildren().add(headerHBox);

                        // VBox para detalhes (Autor, Datas, Descrição)
                        VBox detailsVBox = new VBox(2);
                        detailsVBox.setPadding(new Insets(0, 0, 0, 0)); // Indentação para alinhar com o texto do header

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

                        // Ajusta a cor do texto quando selecionado para garantir contraste
                        descLabel.styleProperty().bind(
                            javafx.beans.binding.Bindings.when(selectedProperty())
                                .then("-fx-text-fill: -fx-selection-bar-text;")
                                .otherwise("-fx-text-fill: #666666;")
                        );

                        detailsVBox.getChildren().add(descLabel);
                        mainVBox.getChildren().add(detailsVBox);

                        setGraphic(mainVBox);
                    }
                }
            };

            cell.setOnMouseClicked(event -> {
                if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY && event.getClickCount() == 2 && !cell.isEmpty()) {
                    DocumentItem item = cell.getItem();
                    if (item != null) {
                        JsonNode jsonData = item.getJsonData();
                        boolean isDisabled = jsonData.has("data_envio") && !jsonData.get("data_envio").isNull();
                        if (!isDisabled) {
                            item.setSelected(!item.isSelected());
                        }
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

    private void handleDocumentSelection(DocumentItem item) {
        log("Item selecionado: " + item.getHeader() + "\n");

        if (item.getPdDocument() != null) {
            loadPdfPreview(item.getPdDocument(), item.getSavedPageIndex(), item.getSavedRect());
            return;
        }

        JsonNode jsonNode = item.getJsonData();
        if (jsonNode.has("texto_original")) {
            String textoOriginal = jsonNode.get("texto_original").asText();
            if (textoOriginal == "null" || textoOriginal == null || textoOriginal.isEmpty()) {
                log("O documento selecionado não possui PDF disponível.\n");
                clearPreview();
                return;
            }
            loadPdfPreview(textoOriginal, item.getSavedPageIndex(), item.getSavedRect());
        }
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
            try {
                try (InputStream is = getInputStreamFromUrl(urlString)) {
                    byte[] bytes = is.readAllBytes();
                    currentDocument = org.apache.pdfbox.Loader.loadPDF(bytes);
                    currentDocumentIsOwnedByItem = false;
                    pdfRenderer = new PDFRenderer(currentDocument);
                    totalPages = currentDocument.getNumberOfPages();
                    currentPageIndex = initialPage;

                    renderCurrentPage();

                    if (initialRect != null) {
                        Platform.runLater(() -> restoreRect(initialRect));
                    }
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
            ((Group)rect.getParent()).getChildren().remove(rect);
        }
        group.getChildren().add(rect);
        lastRect.set(rect);
    }

    private void renderCurrentPage() {
        if (currentDocument == null || pdfRenderer == null) return;

        try {
            BufferedImage bim = pdfRenderer.renderImageWithDPI(currentPageIndex, 200, org.apache.pdfbox.rendering.ImageType.RGB);
            WritableImage image = SwingFXUtils.toFXImage(bim, null);

            Platform.runLater(() -> {
                imageView.setImage(image);
                updateNavigationButtons();

                // Se for a primeira carga (ou se o usuário quiser), ajusta a largura
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
        DocumentItem selectedItem = documentListView.getSelectionModel().getSelectedItem();
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
        if (imageView.getImage() == null) return;

        double width = scrollPane.getWidth();
        if (width <= 0) width = 800; // Fallback

        double fitScale = (width - 40) / imageView.getImage().getWidth();
        if (fitScale > 0) {
            zoomProperty.set(fitScale);
        }
    }

    @FXML
    private void onFitHeight() {
        if (imageView.getImage() == null) return;

        double height = scrollPane.getHeight();
        if (height <= 0) height = 600; // Fallback

        double fitScale = (height - 40) / imageView.getImage().getHeight();
        if (fitScale > 0) {
            zoomProperty.set(fitScale);
        }
    }

    @FXML
    private void onSend() {
        List<DocumentItem> itemsToSend = documentListView.getItems().stream()
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
                        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
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
        List<DocumentItem> selectedItems = documentListView.getItems().stream()
                .filter(DocumentItem::isSelected)
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

        // 1. Tenta pegar o certificado da configuração
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

        // Se não tiver certificado configurado ou válido, pede para selecionar
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

        // 2. Solicitar Senha
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
                // Carregar KeyStore
                KeyStore ks = KeyStore.getInstance("PKCS12");
                try (FileInputStream fis = new FileInputStream(finalCertificadoFile)) {
                    ks.load(fis, senha.toCharArray());
                }

                // Pegar o primeiro alias que tem chave
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

                // Assinar
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
        if (isUpdatingSelectAll) return;

        boolean select = chkSelectAll.isSelected();

        if (chkSelectAll.isIndeterminate()) {
            select = true;
            chkSelectAll.setIndeterminate(false);
            chkSelectAll.setSelected(true);
        }

        isUpdatingSelectAll = true;
        try {
            for (DocumentItem item : documentListView.getItems()) {
                if (!isItemDisabled(item)) {
                    item.setSelected(select);
                }
            }
        } finally {
            isUpdatingSelectAll = false;
        }
        chkSelectAll.setIndeterminate(false);
    }

    private void updateSelectAllState() {
        if (isUpdatingSelectAll) return;

        List<DocumentItem> items = documentListView.getItems();
        if (items.isEmpty()) {
            if (chkSelectAll != null) {
                chkSelectAll.setSelected(false);
                chkSelectAll.setIndeterminate(false);
            }
            return;
        }

        long totalEnabled = items.stream().filter(i -> !isItemDisabled(i)).count();
        long totalSelected = items.stream().filter(i -> !isItemDisabled(i) && i.isSelected()).count();

        isUpdatingSelectAll = true;
        try {
            if (totalEnabled == 0) {
                chkSelectAll.setSelected(false);
                chkSelectAll.setIndeterminate(false);
            } else if (totalSelected == totalEnabled) {
                chkSelectAll.setSelected(true);
                chkSelectAll.setIndeterminate(false);
            } else if (totalSelected == 0) {
                chkSelectAll.setSelected(false);
                chkSelectAll.setIndeterminate(false);
            } else {
                chkSelectAll.setIndeterminate(true);
                chkSelectAll.setSelected(false);
            }
        } finally {
            isUpdatingSelectAll = false;
        }
    }

    private boolean isItemDisabled(DocumentItem item) {
        JsonNode jsonData = item.getJsonData();
        return jsonData.has("data_envio") && !jsonData.get("data_envio").isNull();
    }

    public static class DocumentItem {
        private final String header;
        private final String description;
        private final JsonNode jsonData;
        private final javafx.beans.property.BooleanProperty selected = new javafx.beans.property.SimpleBooleanProperty(false);
        private int savedPageIndex = 0;
        private Rectangle savedRect = null;
        private PDDocument pdDocument;
        private PDDocument pdDocumentSigned;
        private byte[] originalBytes;
        private byte[] signedBytes;

        public DocumentItem(String header, String description, JsonNode jsonData) {
            this.header = header;
            this.description = description;
            this.jsonData = jsonData;
        }

        public String getHeader() { return header; }
        public String getDescription() { return description; }
        public JsonNode getJsonData() { return jsonData; }

        public boolean isSelected() { return selected.get(); }
        public void setSelected(boolean selected) { this.selected.set(selected); }
        public javafx.beans.property.BooleanProperty selectedProperty() { return selected; }

        public int getSavedPageIndex() { return savedPageIndex; }
        public void setSavedPageIndex(int savedPageIndex) { this.savedPageIndex = savedPageIndex; }

        public Rectangle getSavedRect() { return savedRect; }
        public void setSavedRect(Rectangle savedRect) { this.savedRect = savedRect; }

        public PDDocument getPdDocument() { return pdDocument; }
        public void setPdDocument(PDDocument pdDocument) { this.pdDocument = pdDocument; }

        public PDDocument getPdDocumentSigned() { return pdDocumentSigned; }
        public void setPdDocumentSigned(PDDocument pdDocumentSigned) { this.pdDocumentSigned = pdDocumentSigned; }

        public byte[] getOriginalBytes() { return originalBytes; }
        public void setOriginalBytes(byte[] originalBytes) { this.originalBytes = originalBytes; }

        public byte[] getSignedBytes() { return signedBytes; }
        public void setSignedBytes(byte[] signedBytes) { this.signedBytes = signedBytes; }
    }
}
