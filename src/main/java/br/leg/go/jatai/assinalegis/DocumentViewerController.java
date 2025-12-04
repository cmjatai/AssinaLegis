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
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class DocumentViewerController {

    @FXML
    private ListView<DocumentItem> documentListView;

    @FXML
    private VBox vBoxPreviewPage;

    private Consumer<String> logAction;

    @FXML
    public void initialize() {
        initializeDocumentList();
        onRefreshDocuments();
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
    private void onRefreshDocuments() {
        log("Atualizando lista de documentos...\n");
        refreshDocumentList();
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
        vBoxPreviewPage.getChildren().clear();
        Label loadingLabel = new Label("Carregando visualização...");
        vBoxPreviewPage.getChildren().add(loadingLabel);

        new Thread(() -> {
            try {
                URL url = new URL(urlString);
                try (InputStream is = url.openStream();
                     PDDocument document = org.apache.pdfbox.Loader.loadPDF(is.readAllBytes())) {

                    PDFRenderer pdfRenderer = new PDFRenderer(document);
                    // Renderiza apenas a primeira página para preview rápido
                    // Ajustado para 200 DPI (melhor para tela) e RGB explícito
                    BufferedImage bim = pdfRenderer.renderImageWithDPI(0, 200, org.apache.pdfbox.rendering.ImageType.RGB);
                    WritableImage image = SwingFXUtils.toFXImage(bim, null);

                    Platform.runLater(() -> {
                        vBoxPreviewPage.getChildren().clear();

                        // Controles de Zoom
                        HBox zoomControls = new HBox(10);
                        zoomControls.setAlignment(Pos.CENTER_RIGHT);
                        zoomControls.setPadding(new Insets(5));

                        Button btnZoomIn = new Button("+");
                        Button btnZoomOut = new Button("-");
                        Button btnFit = new Button("Ajustar Largura");

                        zoomControls.getChildren().addAll(btnZoomOut, btnFit, btnZoomIn);

                        ImageView imageView = new ImageView(image);
                        imageView.setPreserveRatio(true);
                        imageView.setSmooth(true);
                        imageView.setCache(true);

                        // Grupo para conter a imagem e o retângulo
                        Group group = new Group(imageView);

                        // Propriedade de Zoom
                        DoubleProperty zoomProperty = new SimpleDoubleProperty(1.0);

                        // Transformação de escala com pivô no canto superior esquerdo (0,0)
                        Scale scaleTransform = new Scale();
                        scaleTransform.xProperty().bind(zoomProperty);
                        scaleTransform.yProperty().bind(zoomProperty);
                        scaleTransform.setPivotX(0);
                        scaleTransform.setPivotY(0);
                        group.getTransforms().add(scaleTransform);

                        // Wrapper para garantir que o tamanho do conteúdo reflita o zoom
                        Pane imageWrapper = new Pane(group);

                        // Bind das dimensões do wrapper ao tamanho escalado da imagem
                        imageWrapper.minWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                            () -> image.getWidth() * zoomProperty.get(), zoomProperty));
                        imageWrapper.minHeightProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                            () -> image.getHeight() * zoomProperty.get(), zoomProperty));
                        imageWrapper.maxWidthProperty().bind(imageWrapper.minWidthProperty());
                        imageWrapper.maxHeightProperty().bind(imageWrapper.minHeightProperty());

                        // Referência para o último retângulo desenhado
                        AtomicReference<Rectangle> lastRect = new AtomicReference<>();

                        // Dimensões do retângulo em pixels (200 DPI)
                        // 6cm = (6 / 2.54) * 200 = 472.44 px
                        // 1.5cm = (1.5 / 2.54) * 200 = 118.11 px
                        double rectWidth = (6.0 / 2.54) * 200;
                        double rectHeight = (1.5 / 2.54) * 200;

                        // Eventos de Mouse no Group (captura cliques na imagem e no retângulo)
                        group.setOnMouseClicked(event -> {
                            if (event.isControlDown()) {
                                // Zoom com Ctrl + Clique
                                if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                                    btnZoomIn.fire();
                                } else if (event.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                                    btnZoomOut.fire();
                                }
                                event.consume();
                            } else if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                                // Desenhar retângulo com Clique Esquerdo (sem Ctrl)

                                // Remove o anterior se existir
                                if (lastRect.get() != null) {
                                    group.getChildren().remove(lastRect.get());
                                }

                                Rectangle rect = new Rectangle(rectWidth, rectHeight);
                                rect.setFill(Color.rgb(0, 0, 255, 0.3)); // Azul semi-transparente
                                rect.setStroke(Color.BLUE);

                                // Posiciona o retângulo começando no clique e indo para cima
                                // O clique define o canto inferior esquerdo
                                rect.setX(event.getX());
                                rect.setY(event.getY() - rectHeight);

                                group.getChildren().add(rect);
                                lastRect.set(rect);
                                event.consume();
                            }
                        });

                        // Ações dos botões de Zoom
                        btnZoomIn.setOnAction(e -> zoomProperty.set(zoomProperty.get() * 1.25));
                        btnZoomOut.setOnAction(e -> zoomProperty.set(zoomProperty.get() * 0.8));

                        Runnable fitAction = () -> {
                            double width = vBoxPreviewPage.getWidth();
                            if (width <= 0) {
                                // Tenta pegar do scene/window se o vbox ainda não tiver tamanho
                                if (vBoxPreviewPage.getScene() != null && vBoxPreviewPage.getScene().getWindow() != null) {
                                    width = vBoxPreviewPage.getScene().getWindow().getWidth() / 2;
                                }
                            }
                            if (width <= 0) width = 800; // Fallback

                            double fitScale = (width - 40) / image.getWidth();
                            if (fitScale > 0) {
                                zoomProperty.set(fitScale);
                            }
                        };

                        btnFit.setOnAction(e -> fitAction.run());

                        // StackPane para centralizar o wrapper no ScrollPane
                        StackPane contentHolder = new StackPane(imageWrapper);

                        ScrollPane scrollPane = new ScrollPane(contentHolder);
                        scrollPane.setFitToWidth(true); // Permite que o StackPane ocupe a largura disponível
                        scrollPane.setFitToHeight(true);
                        scrollPane.setPannable(true);

                        VBox.setVgrow(scrollPane, javafx.scene.layout.Priority.ALWAYS);

                        vBoxPreviewPage.getChildren().addAll(zoomControls, scrollPane);

                        // Ajuste inicial (Fit Width)
                        if (vBoxPreviewPage.getWidth() > 0) {
                            fitAction.run();
                        } else {
                            vBoxPreviewPage.widthProperty().addListener(new javafx.beans.value.ChangeListener<Number>() {
                                @Override
                                public void changed(javafx.beans.value.ObservableValue<? extends Number> obs, Number oldVal, Number newVal) {
                                    if (newVal.doubleValue() > 0) {
                                        Platform.runLater(fitAction);
                                        vBoxPreviewPage.widthProperty().removeListener(this);
                                    }
                                }
                            });
                        }
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    vBoxPreviewPage.getChildren().clear();
                    Label errorLabel = new Label("Erro ao carregar PDF: " + e.getMessage());
                    errorLabel.setWrapText(true);
                    vBoxPreviewPage.getChildren().add(errorLabel);
                });
            }
        }).start();
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
