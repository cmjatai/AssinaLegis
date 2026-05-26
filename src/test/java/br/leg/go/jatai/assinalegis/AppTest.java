package br.leg.go.jatai.assinalegis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nível 1 — Smoke / Integridade do Classpath.
 * Verifica que a aplicação está bem montada sem executar lógica de negócio.
 */
@DisplayName("Nível 1 — Smoke / Integridade do Classpath")
class AppTest {

    // -------------------------------------------------------------------------
    // 1. Carregamento das classes principais
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Classe App existe e é carregável")
    void testAppClassExists() {
        assertDoesNotThrow(() -> {
            Class<?> c = Class.forName("br.leg.go.jatai.assinalegis.App");
            assertNotNull(c);
        });
    }

    @Test
    @DisplayName("Classe Launcher existe e é carregável")
    void testLauncherClassExists() {
        assertDoesNotThrow(() -> {
            Class<?> c = Class.forName("br.leg.go.jatai.assinalegis.Launcher");
            assertNotNull(c);
        });
    }

    @Test
    @DisplayName("Classe MainController existe e é carregável")
    void testMainControllerClassExists() {
        assertDoesNotThrow(() -> {
            Class<?> c = Class.forName("br.leg.go.jatai.assinalegis.MainController");
            assertNotNull(c);
        });
    }

    @Test
    @DisplayName("Classe DocumentViewerController existe e é carregável")
    void testDocumentViewerControllerClassExists() {
        assertDoesNotThrow(() -> {
            Class<?> c = Class.forName("br.leg.go.jatai.assinalegis.DocumentViewerController");
            assertNotNull(c);
        });
    }

    @Test
    @DisplayName("Classe ConfigController existe e é carregável")
    void testConfigControllerClassExists() {
        assertDoesNotThrow(() -> {
            Class<?> c = Class.forName("br.leg.go.jatai.assinalegis.ConfigController");
            assertNotNull(c);
        });
    }

    @Test
    @DisplayName("Classe ConfigService existe e é carregável")
    void testConfigServiceClassExists() {
        assertDoesNotThrow(() -> {
            Class<?> c = Class.forName("br.leg.go.jatai.assinalegis.ConfigService");
            assertNotNull(c);
        });
    }

    @Test
    @DisplayName("Classe ApiService existe e é carregável")
    void testApiServiceClassExists() {
        assertDoesNotThrow(() -> {
            Class<?> c = Class.forName("br.leg.go.jatai.assinalegis.ApiService");
            assertNotNull(c);
        });
    }

    @Test
    @DisplayName("Classe TokenService existe e é carregável")
    void testTokenServiceClassExists() {
        assertDoesNotThrow(() -> {
            Class<?> c = Class.forName("br.leg.go.jatai.assinalegis.TokenService");
            assertNotNull(c);
        });
    }

    @Test
    @DisplayName("Classe AssinaturaService existe e é carregável")
    void testAssinaturaServiceClassExists() {
        assertDoesNotThrow(() -> {
            Class<?> c = Class.forName("br.leg.go.jatai.assinalegis.AssinaturaService");
            assertNotNull(c);
        });
    }

    // -------------------------------------------------------------------------
    // 2. application.properties no classpath com campos obrigatórios
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("application.properties está no classpath")
    void testApplicationPropertiesExists() {
        InputStream is = AppTest.class.getResourceAsStream("/application.properties");
        assertNotNull(is, "application.properties não encontrado no classpath");
    }

    @Test
    @DisplayName("application.properties contém app.name preenchido")
    void testApplicationPropertiesHasAppName() throws Exception {
        Properties props = new Properties();
        try (InputStream is = AppTest.class.getResourceAsStream("/application.properties")) {
            assertNotNull(is);
            props.load(is);
        }
        String appName = props.getProperty("app.name");
        assertNotNull(appName, "app.name não encontrado em application.properties");
        assertFalse(appName.isBlank(), "app.name não pode estar vazio");
    }

    @Test
    @DisplayName("application.properties contém app.version preenchido")
    void testApplicationPropertiesHasAppVersion() throws Exception {
        Properties props = new Properties();
        try (InputStream is = AppTest.class.getResourceAsStream("/application.properties")) {
            assertNotNull(is);
            props.load(is);
        }
        String appVersion = props.getProperty("app.version");
        assertNotNull(appVersion, "app.version não encontrado em application.properties");
        assertFalse(appVersion.isBlank(), "app.version não pode estar vazio");
    }

    // -------------------------------------------------------------------------
    // 3. Recursos FXML no classpath
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("main.fxml está no classpath")
    void testMainFxmlExists() {
        InputStream is = AppTest.class.getResourceAsStream(
                "/br/leg/go/jatai/assinalegis/main.fxml");
        assertNotNull(is, "main.fxml não encontrado no classpath");
    }

    @Test
    @DisplayName("document_viewer.fxml está no classpath")
    void testDocumentViewerFxmlExists() {
        InputStream is = AppTest.class.getResourceAsStream(
                "/br/leg/go/jatai/assinalegis/document_viewer.fxml");
        assertNotNull(is, "document_viewer.fxml não encontrado no classpath");
    }

    @Test
    @DisplayName("config.fxml está no classpath")
    void testConfigFxmlExists() {
        InputStream is = AppTest.class.getResourceAsStream(
                "/br/leg/go/jatai/assinalegis/config.fxml");
        assertNotNull(is, "config.fxml não encontrado no classpath");
    }

    // -------------------------------------------------------------------------
    // 4. Launcher possui método main acessível
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Launcher possui método main(String[]) público e estático")
    void testLauncherHasMainMethod() throws Exception {
        Class<?> launcher = Class.forName("br.leg.go.jatai.assinalegis.Launcher");
        Method main = launcher.getMethod("main", String[].class);
        assertNotNull(main);
        assertTrue(java.lang.reflect.Modifier.isPublic(main.getModifiers()),
                "main() deve ser público");
        assertTrue(java.lang.reflect.Modifier.isStatic(main.getModifiers()),
                "main() deve ser estático");
    }
}
