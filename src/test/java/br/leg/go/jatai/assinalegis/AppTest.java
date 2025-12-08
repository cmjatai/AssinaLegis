package br.leg.go.jatai.assinalegis;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários para a aplicação AssinaLegis.
 */
class AppTest {

    @Test
    void testAppMainClassExists() {
        // Verifica se a classe principal existe
        assertDoesNotThrow(() -> {
            Class<?> appClass = Class.forName("br.leg.go.jatai.assinalegis.App");
            assertNotNull(appClass);
        });
    }

    @Test
    void testMainControllerClassExists() {
        // Verifica se o controlador existe
        assertDoesNotThrow(() -> {
            Class<?> controllerClass = Class.forName("br.leg.go.jatai.assinalegis.MainController");
            assertNotNull(controllerClass);
        });
    }
}
