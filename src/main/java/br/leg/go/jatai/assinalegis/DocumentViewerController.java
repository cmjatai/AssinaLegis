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

    @FXML private Button btnFirstPage;
    @FXML private Button btnPrevPage;
    @FXML private Button btnZoomOut;
    @FXML private Button btnFitWidth;
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

    @FXML
    public void initialize() {
        configService = ConfigService.getInstance();
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
        double rectHeight = (1.5 / 2.54) * 200;

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

                Rectangle rect = new Rectangle(rectWidth, rectHeight);
                rect.setFill(Color.rgb(0, 115, 183, 0.6));
                rect.setStroke(Color.rgb(0, 115, 183, 1.0));

                rect.setX(event.getX());
                rect.setY(event.getY() - rectHeight);

                group.getChildren().add(rect);
                lastRect.set(rect);
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
        new Thread(() -> {
            try {
                Map<String, Object> params = new HashMap<>();
                params.put("o", "-data_envio,-id");
                params.put("page_size", 100);
                params.put("data_envio__isnull", "True");
                params.put("data_recebimento__isnull", "True");
                params.put("expand", "autor");
                InputStream response = ApiService.getInstance().get("materia", "proposicao", null, null, params);

                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response);

                Platform.runLater(() -> {
                    ObservableList<DocumentItem> items = documentListView.getItems();
                    items.clear();

                    if (root.has("results") && root.get("results").isArray()) {
                        for (JsonNode node : root.get("results")) {
                            String header = node.has("__str__") ? node.get("__str__").asText() : "";
                            String description = node.has("descricao") ? node.get("descricao").asText() : "";
                            DocumentItem item = new DocumentItem(header, description, node);

                            if (node.has("data_devolucao") && node.get("data_devolucao").isNull()) {
                                preloadPdf(item);
                            }

                            items.add(item);
                        }
                    }
                    log("Lista de documentos atualizada com " + items.size() + " itens.\n");
                });

            } catch (Exception e) {
                e.printStackTrace();
                log("Erro ao atualizar documentos: " + e.getMessage() + "\n");
            }
        }).start();
    }

    private void preloadPdf(DocumentItem item) {
        JsonNode jsonNode = item.getJsonData();
        if (jsonNode.has("texto_original")) {
            String urlString = jsonNode.get("texto_original").asText();
            if (urlString != "null" && urlString != null && !urlString.isEmpty()) {
                new Thread(() -> {
                    try {
                        URL url = new URL(urlString);
                        try (InputStream is = url.openStream()) {
                            PDDocument doc = org.apache.pdfbox.Loader.loadPDF(is.readAllBytes());
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
                        boolean hasDataEnvio = jsonData.has("data_devolucao") && !jsonData.get("data_devolucao").isNull();
                        checkBox.setDisable(hasDataEnvio);

                        Label headerLabel = new Label(item.getHeader());
                        headerLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
                        headerLabel.setWrapText(true);

                        // Vincula a largura do header para evitar scroll horizontal
                        headerLabel.prefWidthProperty().bind(getListView().widthProperty().subtract(65));

                        headerHBox.getChildren().addAll(checkBox, headerLabel);
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
                        boolean isDisabled = jsonData.has("data_devolucao") && !jsonData.get("data_devolucao").isNull();
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

            log("Texto Original:\n" + textoOriginal + "\n");

            if (textoOriginal != null && !textoOriginal.isEmpty()) {
                loadPdfPreview(textoOriginal, item.getSavedPageIndex(), item.getSavedRect());
            }
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
                URL url = new URL(urlString);
                try (InputStream is = url.openStream()) {
                    currentDocument = org.apache.pdfbox.Loader.loadPDF(is.readAllBytes());
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
                    onFitWidth();
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
    }

    @FXML
    private void onFirstPage() {
        if (currentPageIndex > 0) {
            currentPageIndex = 0;
            new Thread(this::renderCurrentPage).start();
        }
    }

    @FXML
    private void onPrevPage() {
        if (currentPageIndex > 0) {
            currentPageIndex--;
            new Thread(this::renderCurrentPage).start();
        }
    }

    @FXML
    private void onNextPage() {
        if (currentPageIndex < totalPages - 1) {
            currentPageIndex++;
            new Thread(this::renderCurrentPage).start();
        }
    }

    @FXML
    private void onLastPage() {
        if (currentPageIndex < totalPages - 1) {
            currentPageIndex = totalPages - 1;
            new Thread(this::renderCurrentPage).start();
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
        String senha = solicitarSenha();
        if (senha == null) {
            log("Operação cancelada pelo usuário.\n");
            return;
        }

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
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Senha do Certificado");
        dialog.setHeaderText("Digite a senha do certificado digital:");
        dialog.setContentText("Senha:");

        Optional<String> result = dialog.showAndWait();
        return result.orElse(null);
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
    }
}
