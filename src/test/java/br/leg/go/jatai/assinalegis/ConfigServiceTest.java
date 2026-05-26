package br.leg.go.jatai.assinalegis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nível 2 — ConfigService: valores, defaults e persistência JSON.
 * Usa nó de Preferences isolado para não contaminar dados do usuário.
 */
@DisplayName("Nível 2 — ConfigService: valores e defaults")
class ConfigServiceTest {

    private static final String TEST_NODE = "assinalegis_test_nivel2";
    private Preferences testPrefs;
    private ConfigService service;

    @BeforeEach
    void setUp() throws Exception {
        testPrefs = Preferences.userRoot().node(TEST_NODE);
        testPrefs.clear();
        testPrefs.flush();
        ConfigService.resetForTest(testPrefs);
        service = ConfigService.getInstance();
    }

    @AfterEach
    void tearDown() throws Exception {
        ConfigService.clearInstanceForTest();
        testPrefs.clear();
        testPrefs.removeNode();
        testPrefs.flush();
    }

    // -------------------------------------------------------------------------
    // 1. Valores padrão
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getUrl() retorna string vazia como default")
    void deveRetornarUrlVaziaComoDefault() {
        assertEquals("", service.getUrl());
    }

    @Test
    @DisplayName("getToken() retorna string vazia como default")
    void deveRetornarTokenVazioComoDefault() {
        assertEquals("", service.getToken());
    }

    @Test
    @DisplayName("getCertPath() retorna string vazia como default")
    void deveRetornarCaminhoVazioComoDefault() {
        assertEquals("", service.getCertPath());
    }

    @Test
    @DisplayName("getCertPassword() retorna string vazia como default")
    void deveRetornarSenhaVaziaComoDefault() {
        assertEquals("", service.getCertPassword());
    }

    @Test
    @DisplayName("getSignatureBgColor() retorna #003d71 como default")
    void deveRetornarCorFundoDefault() {
        assertEquals("#003d71", service.getSignatureBgColor());
    }

    @Test
    @DisplayName("getSignatureNameColor() retorna #ffffff como default")
    void deveRetornarCorNomeDefault() {
        assertEquals("#ffffff", service.getSignatureNameColor());
    }

    @Test
    @DisplayName("getSignatureDateColor() retorna #ffff00 como default")
    void deveRetornarCorDataDefault() {
        assertEquals("#ffff00", service.getSignatureDateColor());
    }

    @Test
    @DisplayName("getCasaLegislativa() retorna null quando não configurada")
    void deveRetornarNullParaCasaNaoConfigurada() {
        assertNull(service.getCasaLegislativa(JsonNode.class));
    }

    // -------------------------------------------------------------------------
    // 2. Round-trip de strings simples
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("setUrl / getUrl faz round-trip corretamente")
    void deveSalvarERecuperarUrl() {
        service.setUrl("https://sistema.jatai.go.leg.br");
        assertEquals("https://sistema.jatai.go.leg.br", service.getUrl());
    }

    @Test
    @DisplayName("setToken / getToken faz round-trip corretamente")
    void deveSalvarERecuperarToken() {
        service.setToken("9944b09199c62bcfabc03");
        assertEquals("9944b09199c62bcfabc03", service.getToken());
    }

    @Test
    @DisplayName("setCertPath / getCertPath faz round-trip corretamente")
    void deveSalvarERecuperarCertPath() {
        service.setCertPath("/home/usuario/certificado.pfx");
        assertEquals("/home/usuario/certificado.pfx", service.getCertPath());
    }

    @Test
    @DisplayName("setCertPassword / getCertPassword faz round-trip corretamente")
    void deveSalvarERecuperarCertPassword() {
        service.setCertPassword("senha_secreta");
        assertEquals("senha_secreta", service.getCertPassword());
    }

    // -------------------------------------------------------------------------
    // 3. Round-trip de cores
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("setSignatureBgColor / getSignatureBgColor faz round-trip")
    void deveSalvarERecuperarCorFundo() {
        service.setSignatureBgColor("#1a2b3c");
        assertEquals("#1a2b3c", service.getSignatureBgColor());
    }

    @Test
    @DisplayName("setSignatureNameColor / getSignatureNameColor faz round-trip")
    void deveSalvarERecuperarCorNome() {
        service.setSignatureNameColor("#aabbcc");
        assertEquals("#aabbcc", service.getSignatureNameColor());
    }

    @Test
    @DisplayName("setSignatureDateColor / getSignatureDateColor faz round-trip")
    void deveSalvarERecuperarCorData() {
        service.setSignatureDateColor("#112233");
        assertEquals("#112233", service.getSignatureDateColor());
    }

    // -------------------------------------------------------------------------
    // 4. Casa Legislativa — round-trip JSON
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("setCasaLegislativa / getCasaLegislativa preserva JSON intacto")
    void deveSalvarERecuperarCasaComoJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode casa = mapper.readTree("{\"id\":1,\"nome\":\"Câmara Municipal de Jataí\",\"sigla\":\"CMJ\"}");

        service.setCasaLegislativa(casa);

        JsonNode recuperado = service.getCasaLegislativa(JsonNode.class);
        assertNotNull(recuperado);
        assertEquals("Câmara Municipal de Jataí", recuperado.get("nome").asText());
        assertEquals("CMJ", recuperado.get("sigla").asText());
        assertEquals(1, recuperado.get("id").asInt());
    }

    @Test
    @DisplayName("getCasaLegislativa retorna null após limpar as preferências")
    void deveRetornarNullAposLimpar() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        service.setCasaLegislativa(mapper.readTree("{\"nome\":\"Teste\"}"));
        assertNotNull(service.getCasaLegislativa(JsonNode.class));

        testPrefs.remove("casalegislativa");

        assertNull(service.getCasaLegislativa(JsonNode.class));
    }

    // -------------------------------------------------------------------------
    // 5. Tratamento de nulls
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("setToken(null) armazena string vazia")
    void setTokenNullDeveArmazenarStringVazia() {
        service.setToken(null);
        assertEquals("", service.getToken());
    }

    @Test
    @DisplayName("setCertPassword(null) armazena string vazia")
    void setCertPasswordNullDeveArmazenarStringVazia() {
        service.setCertPassword(null);
        assertEquals("", service.getCertPassword());
    }

    // -------------------------------------------------------------------------
    // 6. Modo debug
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("isDebug() retorna false por padrão (sem propriedade de sistema)")
    void isDebugDeveRetornarFalsePorPadrao() {
        // Remove a propriedade de sistema caso esteja definida no ambiente
        String anterior = System.getProperty("app.debug");
        System.clearProperty("app.debug");
        try {
            // Recria a instância sem a propriedade de sistema
            ConfigService.resetForTest(testPrefs);
            assertFalse(ConfigService.getInstance().isDebug());
        } finally {
            if (anterior != null) {
                System.setProperty("app.debug", anterior);
            }
        }
    }
}
