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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class DocumentViewerController {

    @FXML private ListView<DocumentItem> documentListView;
    @FXML private VBox vBoxPreviewPage;
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
    private PDFRenderer pdfRenderer;
    private int currentPageIndex = 0;
    private int totalPages = 0;

    private final DoubleProperty zoomProperty = new SimpleDoubleProperty(1.0);
    private final AtomicReference<Rectangle> lastRect = new AtomicReference<>();
    private Group group;
    private ImageView imageView;
    private Pane imageWrapper;

    @FXML
    public void initialize() {
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
                rect.setFill(Color.rgb(0, 0, 255, 0.3));
                rect.setStroke(Color.BLUE);

                rect.setX(event.getX());
                rect.setY(event.getY() - rectHeight);

                group.getChildren().add(rect);
                lastRect.set(rect);
                event.consume();
            }
        });
    }

    @FXML
    private void onRefreshDocuments() {
        log("Atualizando lista de documentos...\n");
        clearPreview();
        refreshDocumentList();
    }

    private void clearPreview() {
        if (currentDocument != null) {
            try {
                currentDocument.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            currentDocument = null;
        }

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
                            items.add(new DocumentItem(header, description, node.toString()));
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

    private void initializeDocumentList() {
        ObservableList<DocumentItem> items = FXCollections.observableArrayList();
        documentListView.setItems(items);
        documentListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        documentListView.setCellFactory(param -> new ListCell<DocumentItem>() {
            @Override
            protected void updateItem(DocumentItem item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    VBox vBox = new VBox(5);

                    Label headerLabel = new Label(item.getHeader());
                    headerLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
                    headerLabel.setWrapText(true);

                    Label descLabel = new Label(item.getDescription());
                    descLabel.setWrapText(true);

                    // Ajusta a cor do texto quando selecionado para garantir contraste
                    descLabel.styleProperty().bind(
                        javafx.beans.binding.Bindings.when(selectedProperty())
                            .then("-fx-text-fill: -fx-selection-bar-text;")
                            .otherwise("-fx-text-fill: #666666;")
                    );

                    // Vincula a largura dos labels à largura do ListView para evitar scroll horizontal
                    // Subtrai um valor para compensar padding e barra de rolagem
                    headerLabel.prefWidthProperty().bind(getListView().widthProperty().subtract(35));
                    descLabel.prefWidthProperty().bind(getListView().widthProperty().subtract(35));

                    vBox.getChildren().addAll(headerLabel, descLabel);
                    setGraphic(vBox);
                }
            }
        });

        documentListView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                handleDocumentSelection(newValue);
            }
        });
    }

    private void handleDocumentSelection(DocumentItem item) {
        log("Item selecionado: " + item.getHeader() + "\n");
        // convertendo o JSON de volta para exibir detalhes
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode jsonNode = mapper.readTree(item.getJsonData());
            String textoOriginal = jsonNode.get("texto_original").asText();

            log("Texto Original:\n" + textoOriginal + "\n");

            if (textoOriginal != null && !textoOriginal.isEmpty()) {
                loadPdfPreview(textoOriginal);
            }

        } catch (JsonProcessingException e) {
            log("Erro ao processar JSON: " + e.getMessage() + "\n");
        }
    }

    private void loadPdfPreview(String urlString) {
        clearPreview();

        new Thread(() -> {
            try {
                URL url = new URL(urlString);
                try (InputStream is = url.openStream()) {
                    currentDocument = org.apache.pdfbox.Loader.loadPDF(is.readAllBytes());
                    pdfRenderer = new PDFRenderer(currentDocument);
                    totalPages = currentDocument.getNumberOfPages();
                    currentPageIndex = 0;

                    renderCurrentPage();
                }
            } catch (Exception e) {
                e.printStackTrace();
                log("Erro ao carregar PDF: " + e.getMessage() + "\n");
            }
        }).start();
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
        zoomProperty.set(zoomProperty.get() * 1.25);
    }

    @FXML
    private void onZoomOut() {
        zoomProperty.set(zoomProperty.get() * 0.8);
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

    public static class DocumentItem {
        private final String header;
        private final String description;
        private final String jsonData;

        public DocumentItem(String header, String description, String jsonData) {
            this.header = header;
            this.description = description;
            this.jsonData = jsonData;
        }

        public String getHeader() { return header; }
        public String getDescription() { return description; }
        public String getJsonData() { return jsonData; }
    }
}
