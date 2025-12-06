package br.leg.go.jatai.assinalegis;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

/**
 * Aplicativo principal AssinaLegis.
 * Aplicativo desktop para assinatura digital - Câmara Municipal de Jataí.
 */
public class App extends Application implements ConfigService.ConfigObserver {

    private static App instance;
    private static Scene scene;
    private Stage stage;
    private String appTitleBase = "AssinaLegis";

    @Override
    public void start(Stage stage) throws IOException {
        instance = this;
        this.stage = stage;
        ConfigService.getInstance().addObserver(this);

        loadAppProperties();

        scene = new Scene(loadFXML("main"));

        // Define título inicial
        updateTitle(ConfigService.getInstance().getCasaLegislativa(JsonNode.class));

        // Configura o ícone da janela
        stage.getIcons().add(new Image(Objects.requireNonNull(App.class.getResourceAsStream("/icon.png"))));

        stage.setScene(scene);
        stage.show();
    }

    private void loadAppProperties() {
        Properties props = new Properties();
        try (InputStream is = App.class.getResourceAsStream("/application.properties")) {
            if (is != null) {
                props.load(is);
                String name = props.getProperty("app.name", "AssinaLegis");
                String version = props.getProperty("app.version", "");
                if (!version.isEmpty()) {
                    appTitleBase = name + " v" + version;
                } else {
                    appTitleBase = name;
                }
            }
        } catch (IOException e) {
            System.err.println("Erro ao carregar application.properties: " + e.getMessage());
        }
    }

    private void updateTitle(JsonNode casa) {
        if (stage == null) return;

        String title = appTitleBase;
        if (casa != null && casa.has("nome")) {
            title += " - " + casa.get("nome").asText();
        }
        stage.setTitle(title);
    }

    @Override
    public void onConfigChanged(String key, Object newValue) {
        if (ConfigService.KEY_CASA.equals(key) && newValue instanceof JsonNode) {
            JsonNode casa = (JsonNode) newValue;
            Platform.runLater(() -> updateTitle(casa));
        }
    }

    static void setRoot(String fxml) throws IOException {
        scene.setRoot(loadFXML(fxml));
    }

    private static Parent loadFXML(String fxml) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(App.class.getResource(fxml + ".fxml"));
        return fxmlLoader.load();
    }

    public static void main(String[] args) {
        launch(args);
    }

    public static void openUrl(String url) {
        if (instance != null) {
            instance.getHostServices().showDocument(url);
        }
    }
}
