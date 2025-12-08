package br.leg.go.jatai.assinalegis;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.GridPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Pair;

import java.io.IOException;
import java.io.InputStream;
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

    private TextArea logArea;
    private Stage logStage;

    @FXML
    private Button authButton;

    @FXML
    private DocumentViewerController documentViewerController;

    private ConfigService configService;

    @FXML
    public void initialize() {
        configService = ConfigService.getInstance();

        // Inicializa logArea e logStage
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);

        logStage = new Stage();
        logStage.setTitle("Log do Sistema");
        logStage.initModality(Modality.NONE);

        javafx.scene.layout.VBox logRoot = new javafx.scene.layout.VBox(logArea);
        logRoot.setPadding(new javafx.geometry.Insets(10));
        javafx.scene.layout.VBox.setVgrow(logArea, javafx.scene.layout.Priority.ALWAYS);

        Scene logScene = new Scene(logRoot, 600, 400);
        logStage.setScene(logScene);

        logStage.setOnCloseRequest(event -> {
            event.consume();
            logStage.hide();
        });

        statusLabel.setText("Pronto para assinar documentos");
        logArea.setText("AssinaLegis iniciado.\n");

        updateAuthButton();

        if (documentViewerController != null) {
            documentViewerController.setLogAction(msg -> logArea.appendText(msg));
        }

        Platform.runLater(() -> {
            Stage stage = (Stage) statusLabel.getScene().getWindow();
            if (stage != null) {
                stage.setMaximized(true);
                stage.setOnCloseRequest(event -> {
                    Platform.exit();
                    System.exit(0);
                });
            }
        });
    }

    @FXML
    private void onViewLog() {
        if (logStage != null) {
            logStage.show();
            logStage.toFront();
        }
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
    private void onExit() {
        Platform.exit();
    }

    @FXML
    private void onAbout() {
        logArea.appendText("AssinaLegis\n");
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
