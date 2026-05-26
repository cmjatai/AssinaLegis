package br.leg.go.jatai.assinalegis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
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

    // =========================================================================
    // Nível 3 — Observer Pattern
    // =========================================================================

    @Nested
    @DisplayName("Nível 3 — ConfigService: Observer Pattern")
    class ObserverTest {

        /** Registro imutável de uma chamada ao observer. */
        record Chamada(String chave, Object valor) {}

        @Test
        @DisplayName("Observer recebe KEY_TOKEN e valor correto ao chamar setToken")
        void observerRecebeNotificacaoDeToken() {
            List<Chamada> chamadas = new ArrayList<>();
            service.addObserver((key, val) -> chamadas.add(new Chamada(key, val)));

            service.setToken("novo-token");

            assertEquals(1, chamadas.size());
            assertEquals(ConfigService.KEY_TOKEN, chamadas.get(0).chave());
            assertEquals("novo-token", chamadas.get(0).valor());
        }

        @Test
        @DisplayName("Observer recebe KEY_CASA ao chamar setCasaLegislativa")
        void observerRecebeNotificacaoDeCasaLegislativa() throws Exception {
            List<Chamada> chamadas = new ArrayList<>();
            service.addObserver((key, val) -> chamadas.add(new Chamada(key, val)));

            JsonNode casa = new ObjectMapper().readTree("{\"nome\":\"CMJ\"}");
            service.setCasaLegislativa(casa);

            assertEquals(1, chamadas.size());
            assertEquals(ConfigService.KEY_CASA, chamadas.get(0).chave());
        }

        @Test
        @DisplayName("Após removeObserver, nenhuma notificação é recebida")
        void aposRemoveObserverNaoRecebeNotificacao() {
            List<Chamada> chamadas = new ArrayList<>();
            ConfigService.ConfigObserver obs = (key, val) -> chamadas.add(new Chamada(key, val));

            service.addObserver(obs);
            service.setToken("antes");
            assertEquals(1, chamadas.size());

            service.removeObserver(obs);
            service.setToken("depois");

            assertEquals(1, chamadas.size(), "Não deve receber notificação após remoção");
        }

        @Test
        @DisplayName("Múltiplos observers são notificados de forma independente")
        void multiplosObserversSaoNotificadosIndependentemente() {
            List<String> obs1 = new ArrayList<>();
            List<String> obs2 = new ArrayList<>();

            service.addObserver((key, val) -> obs1.add(key));
            service.addObserver((key, val) -> obs2.add(key));

            service.setToken("token-teste");

            assertEquals(1, obs1.size());
            assertEquals(1, obs2.size());
        }

        @Test
        @DisplayName("Observer recebe todos os valores em sequência ao alterar token múltiplas vezes")
        void observerRecebeValoresEmSequencia() {
            List<String> tokens = new ArrayList<>();
            service.addObserver((key, val) -> {
                if (ConfigService.KEY_TOKEN.equals(key)) tokens.add((String) val);
            });

            service.setToken("token-1");
            service.setToken("token-2");
            service.setToken("token-3");

            assertEquals(List.of("token-1", "token-2", "token-3"), tokens);
        }

        @Test
        @DisplayName("Remover observer não afeta outros observers registrados")
        void removerUmObserverNaoAfetaOutros() {
            List<String> obs1 = new ArrayList<>();
            List<String> obs2 = new ArrayList<>();

            ConfigService.ConfigObserver primeiro = (key, val) -> obs1.add(key);
            service.addObserver(primeiro);
            service.addObserver((key, val) -> obs2.add(key));

            service.removeObserver(primeiro);
            service.setToken("token-apos-remocao");

            assertTrue(obs1.isEmpty(), "Observer removido não deve ser notificado");
            assertEquals(1, obs2.size(), "Observer ativo deve continuar recebendo notificações");
        }

        @Test
        @DisplayName("setUrl não dispara notificação síncrona para observers")
        void setUrlNaoNotificaObserversDiretamente() throws InterruptedException {
            List<String> chaves = new ArrayList<>();
            service.addObserver((key, val) -> chaves.add(key));

            // setUrl só notifica via thread de API (assíncrona e silenciosa sem backend)
            service.setUrl("http://backend-inexistente");

            // Verifica imediatamente — não deve haver notificação síncrona de KEY_URL
            assertFalse(chaves.contains("url"),
                    "setUrl não deve notificar observers com a chave 'url' de forma síncrona");
        }
    }
}
