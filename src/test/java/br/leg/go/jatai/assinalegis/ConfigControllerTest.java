package br.leg.go.jatai.assinalegis;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nível 7 — ConfigController: testes de UI com TestFX.
 *
 * Requer ambiente gráfico (display X11 no Linux ou equivalente no macOS/Windows).
 * Em CI headless sem display, todos os testes são pulados automaticamente.
 * Para rodar em CI Linux, use: DISPLAY=:99 Xvfb :99 -screen 0 1280x720x24 &
 */
@EnabledIf(value = "ambienteGraficoDisponivel", disabledReason = "Requer display gráfico (DISPLAY não definido em Linux)")
@ExtendWith(ApplicationExtension.class)
class ConfigControllerTest {

    private static final String URL_TESTE = "http://api.exemplo.com.br";
    private static final String TOKEN_TESTE = "token-de-teste-123";

    private static Preferences prefs;
    private ConfigController controller;

    /**
     * Condição para @EnabledIf: verifica se há ambiente gráfico disponível.
     * No Linux, exige que DISPLAY esteja configurado.
     * Em outros sistemas (macOS, Windows), sempre habilitado.
     */
    static boolean ambienteGraficoDisponivel() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux")) {
            return System.getenv("DISPLAY") != null;
        }
        return true;
    }

    @BeforeAll
    static void configurarPreferences() throws BackingStoreException {
        prefs = Preferences.userRoot().node("assinalegis_test_nivel7");
        prefs.clear();
    }

    /**
     * Chamado pelo TestFX antes de cada teste, na thread JavaFX.
     * Configura o ConfigService com valores isolados e carrega o config.fxml.
     */
    @Start
    void iniciar(Stage stage) throws Exception {
        prefs.put("url", URL_TESTE);
        prefs.put(ConfigService.KEY_TOKEN, TOKEN_TESTE);
        ConfigService.resetForTest(prefs);

        FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/br/leg/go/jatai/assinalegis/config.fxml"));
        Parent root = loader.load();
        controller = loader.getController();
        controller.setDialogStage(stage);

        stage.setScene(new Scene(root, 640, 520));
        stage.show();
    }

    @AfterEach
    void tearDown() throws BackingStoreException {
        prefs.clear();
        ConfigService.clearInstanceForTest();
    }

    @AfterAll
    static void removerNode() throws BackingStoreException {
        prefs.removeNode();
    }

    // -----------------------------------------------------------------------
    // Carregamento do FXML
    // -----------------------------------------------------------------------

    @Test
    void configFxmlCarregaSemErro() {
        assertNotNull(controller, "FXMLLoader deve injetar o controller após carregar config.fxml");
    }

    @Test
    void controllerEhDoTipoConfigController() {
        assertInstanceOf(ConfigController.class, controller);
    }

    // -----------------------------------------------------------------------
    // Valores exibidos nos campos
    // -----------------------------------------------------------------------

    @Test
    void urlFieldExibeUrlConfigurada(FxRobot robot) {
        String texto = robot.lookup("#urlField").queryAs(TextField.class).getText();
        assertEquals(URL_TESTE, texto, "Campo URL deve exibir o valor configurado");
    }

    @Test
    void tokenFieldExibeTokenConfigurado(FxRobot robot) {
        String texto = robot.lookup("#tokenField").queryAs(TextField.class).getText();
        assertEquals(TOKEN_TESTE, texto, "Campo Token deve exibir o valor configurado");
    }

    @Test
    void bgColorPickerTemValorNaoNulo(FxRobot robot) {
        ColorPicker picker = robot.lookup("#bgColorPicker").queryAs(ColorPicker.class);
        assertNotNull(picker.getValue(), "ColorPicker de fundo deve ter uma cor selecionada");
    }

    // -----------------------------------------------------------------------
    // Estado dos componentes
    // -----------------------------------------------------------------------

    @Test
    void urlFieldEhEditavel(FxRobot robot) {
        TextField campo = robot.lookup("#urlField").queryAs(TextField.class);
        assertFalse(campo.isDisabled(), "Campo URL deve estar habilitado para edição");
    }

    @Test
    void tokenFieldEhEditavel(FxRobot robot) {
        TextField campo = robot.lookup("#tokenField").queryAs(TextField.class);
        assertFalse(campo.isDisabled(), "Campo Token deve estar habilitado para edição");
    }
}
