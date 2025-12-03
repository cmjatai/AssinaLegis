package br.leg.go.jatai.assinalegis;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Pair;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Controlador principal da interface do AssinaLegis.
 */
public class MainController {

    @FXML
    private Label statusLabel;

    @FXML
    private Label statusUserLabel;

    @FXML
    private TextArea logArea;

    @FXML
    private Button selectFileButton;

    @FXML
    private Button authButton;

    @FXML
    private ListView<DocumentItem> documentListView;

    private File selectedFile;
    private ConfigService configService;

    @FXML
    public void initialize() {
        configService = ConfigService.getInstance();
        statusLabel.setText("Pronto para assinar documentos");
        logArea.setText("AssinaLegis iniciado.\n");

        updateAuthButton();
        initializeDocumentList();

        Platform.runLater(() -> {
            Stage stage = (Stage) statusLabel.getScene().getWindow();
            stage.setMaximized(true);
        });
    }

    @FXML
    private void onRefreshDocuments() {
        logArea.appendText("Atualizando lista de documentos...\n");
        try {
            refreshDocumentList();
        } catch (Exception e) {
            e.printStackTrace();
            logArea.appendText("Erro ao atualizar documentos: " + e.getMessage() + "\n");
        }
    }
    private void refreshDocumentList() throws Exception {
        InputStream response = ApiService.getInstance().get("materia", "proposicao", null, null, null);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(response);

        // Basta manipular a lista existente. A UI será atualizada automaticamente.
        ObservableList<DocumentItem> items = documentListView.getItems();
        items.clear(); // Limpa os itens atuais

        if (root.has("results") && root.get("results").isArray()) {
            for (JsonNode node : root.get("results")) {
                String header = node.has("__str__") ? node.get("__str__").asText() : "";
                String description = node.has("descricao") ? node.get("descricao").asText() : "";
                items.add(new DocumentItem(header, description, node.toString()));
            }
        }

        logArea.appendText("Lista de documentos atualizada com " + items.size() + " itens.\n");
    }

    private void initializeDocumentList() {
        ObservableList<DocumentItem> items = FXCollections.observableArrayList();

        for (int i = 1; i <= 7; i++) {
            items.add(new DocumentItem(
                "Documento " + i,
                "Descrição do documento " + i + " para assinatura.",
                "{\"id\": " + i + ", \"type\": \"doc\", \"status\": \"pending\"}"
            ));
        }

        documentListView.setItems(items);
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
                    descLabel.setStyle("-fx-text-fill: #666666;");

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
        logArea.appendText("Item selecionado: " + item.getHeader() + "\n");
        logArea.appendText("JSON: " + item.getJsonData() + "\n");
        // Aqui será implementada a funcionalidade futura
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

    @FXML
    private void onConfig() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("config.fxml"));
            Parent root = loader.load();

            Stage stage = new Stage();
            stage.setTitle("Configurações");
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.setScene(new Scene(root));

            // Tenta carregar o ícone se disponível
            try {
                stage.getIcons().add(new Image(Objects.requireNonNull(App.class.getResourceAsStream("/icon.png"))));
            } catch (Exception ignored) {}

            ConfigController controller = loader.getController();
            controller.setDialogStage(stage);

            stage.showAndWait();
        } catch (IOException e) {
            e.printStackTrace();
            logArea.appendText("Erro ao abrir configurações: " + e.getMessage() + "\n");
        }
    }

    @FXML
    private void onSelectFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Selecionar arquivo para assinar");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Documentos PDF", "*.pdf"),
            new FileChooser.ExtensionFilter("Todos os arquivos", "*.*")
        );

        selectedFile = fileChooser.showOpenDialog(selectFileButton.getScene().getWindow());

        if (selectedFile != null) {
            logArea.appendText("Arquivo selecionado: " + selectedFile.getName() + "\n");
            statusLabel.setText("Arquivo selecionado: " + selectedFile.getName());
        }
    }

    @FXML
    private void onSign() {
        if (selectedFile == null) {
            logArea.appendText("Erro: Nenhum arquivo selecionado.\n");
            statusLabel.setText("Selecione um arquivo primeiro");
            return;
        }

        // 1. Tenta pegar o certificado da configuração
        String certPath = configService.getCertPath();
        File certificadoFile;

        if (certPath != null && !certPath.isEmpty()) {
            certificadoFile = new File(certPath);
            if (!certificadoFile.exists()) {
                logArea.appendText("Certificado configurado não encontrado: " + certPath + "\n");
                certificadoFile = null;
            } else {
                logArea.appendText("Usando certificado configurado: " + certificadoFile.getName() + "\n");
            }
        } else {
            certificadoFile = null;
        }

        // Se não tiver certificado configurado ou válido, pede para selecionar
        if (certificadoFile == null) {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Selecionar Certificado Digital (.pfx)");
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Certificado PKCS#12", "*.pfx", "*.p12"));
            certificadoFile = fileChooser.showOpenDialog(selectFileButton.getScene().getWindow());

            if (certificadoFile == null) {
                logArea.appendText("Seleção de certificado cancelada.\n");
                return;
            }
        }

        // 2. Solicitar Senha
        String senha = solicitarSenha();
        if (senha == null) {
            logArea.appendText("Operação cancelada pelo usuário.\n");
            return;
        }

        logArea.appendText("Iniciando processo de assinatura...\n");
        statusLabel.setText("Assinando documento...");

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

                // Definir arquivo de saída
                String nomeOriginal = selectedFile.getAbsolutePath();
                String nomeSaida = nomeOriginal.substring(0, nomeOriginal.lastIndexOf(".")) + "_assinado.pdf";
                File arquivoSaida = new File(nomeSaida);

                // Assinar
                AssinaturaService service = new AssinaturaService();
                service.assinarPdf(selectedFile, arquivoSaida, ks, alias, senha.toCharArray());

                Platform.runLater(() -> {
                    logArea.appendText("Sucesso! Arquivo assinado gerado em:\n" + nomeSaida + "\n");
                    statusLabel.setText("Documento assinado com sucesso!");
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Sucesso");
                    alert.setHeaderText(null);
                    alert.setContentText("Documento assinado com sucesso!\nSalvo em: " + nomeSaida);
                    alert.showAndWait();
                });

            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    logArea.appendText("Erro ao assinar: " + e.getMessage() + "\n");
                    statusLabel.setText("Erro na assinatura");
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

        ButtonType loginButtonType = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(loginButtonType, ButtonType.CANCEL);

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Senha");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.add(new Label("Senha:"), 0, 0);
        grid.add(passwordField, 1, 0);

        dialog.getDialogPane().setContent(grid);

        Platform.runLater(passwordField::requestFocus);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == loginButtonType) {
                return passwordField.getText();
            }
            return null;
        });

        Optional<String> result = dialog.showAndWait();
        return result.orElse(null);
    }

    @FXML
    private void onExit() {
        Platform.exit();
    }

    @FXML
    private void onAbout() {
        logArea.appendText("AssinaLegis v1.0.0\n");
        logArea.appendText("Câmara Municipal de Jataí\n");
        logArea.appendText("Desenvolvido para assinatura digital de documentos.\n");
    }

    private void updateAuthButton() {
        String token = configService.getToken();
        if (token == null || token.isEmpty()) {
            authButton.setText("Acessar");
        } else {
            authButton.setText("Sair");
        }
    }

    @FXML
    private void onAuthAction() {
        String token = configService.getToken();
        if (token == null || token.isEmpty()) {
            // Login
            showLoginDialog();
        } else {
            // Logout
            statusUserLabel.setText("");
            configService.setToken(null);
            updateAuthButton();
            logArea.appendText("Logout realizado.\n");
        }
    }

    private void showLoginDialog() {
        Dialog<Pair<String, String>> dialog = new Dialog<>();
        dialog.setTitle("Login");
        dialog.setHeaderText("Entre com suas credenciais");

        ButtonType loginButtonType = new ButtonType("Conectar", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(loginButtonType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new javafx.geometry.Insets(20, 150, 10, 10));

        TextField username = new TextField();
        username.setPromptText("Username");
        PasswordField password = new PasswordField();
        password.setPromptText("Password");

        grid.add(new Label("Username:"), 0, 0);
        grid.add(username, 1, 0);
        grid.add(new Label("Password:"), 0, 1);
        grid.add(password, 1, 1);

        dialog.getDialogPane().setContent(grid);

        Platform.runLater(username::requestFocus);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == loginButtonType) {
                return new Pair<>(username.getText(), password.getText());
            }
            return null;
        });

        Optional<Pair<String, String>> result = dialog.showAndWait();

        result.ifPresent(usernamePassword -> {
            String u = usernamePassword.getKey();
            String p = usernamePassword.getValue();
            performLogin(u, p);
        });
    }

    private void performLogin(String username, String password) {
        new Thread(() -> {
            try {
                Map<String, Object> form = new HashMap<>();
                form.put("username", username);
                form.put("password", password);

                InputStream response = ApiService.getInstance().post("auth", "token", null, null, form, null);

                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response);

                if (root.has("token")) {
                    String token = root.get("token").asText();
                    Platform.runLater(() -> {
                        configService.setToken(token);
                        updateAuthButton();
                        logArea.appendText("Login realizado com sucesso.\n");
                        statusLabel.setText("Logado como " + username);
                        statusUserLabel.setText("Usuário: " + username);
                    });
                } else {
                    throw new Exception("Token não encontrado na resposta.");
                }

            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    logArea.appendText("Erro no login: " + e.getMessage() + "\n");
                    statusUserLabel.setText("");

                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Erro de Login");
                    alert.setHeaderText("Falha ao conectar");
                    alert.setContentText(e.getMessage());
                    alert.showAndWait();
                });
            }
        }).start();
    }
}
