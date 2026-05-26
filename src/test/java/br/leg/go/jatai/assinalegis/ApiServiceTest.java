package br.leg.go.jatai.assinalegis;

import okhttp3.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nível 5 — ApiService: construção de URL, cabeçalhos, métodos HTTP,
 * detecção de multipart vs JSON e tratamento de erros HTTP.
 *
 * Estratégia: interceptor OkHttp captura a requisição antes de enviá-la
 * à rede e devolve uma resposta sintética. Nenhuma dependência nova —
 * okhttp3 já é requires no module-info.java.
 */
class ApiServiceTest {

    private static final String PREFS_NODE = "assinalegis_test_nivel5";

    private CapturadorDeRequisicao capturador;

    @AfterEach
    void tearDown() throws Exception {
        ApiService.clearInstanceForTest();
        ConfigService.clearInstanceForTest();
        // Apenas limpa os valores; não remove o nó para evitar
        // IllegalStateException em threads de background que possam
        // ainda estar acessando os dados de configuração deste nó.
        Preferences.userRoot().node(PREFS_NODE).clear();
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        // Remove o nó somente depois que todos os testes concluíram.
        try {
            Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
            prefs.removeNode();
            prefs.flush();
        } catch (Exception ignored) {
            // Nó pode não existir se todos os testes foram ignorados
        }
    }

    /**
     * Monta ConfigService e ApiService com interceptor falso.
     * @param url        URL base (null = não definida)
     * @param token      Token de auth (null = não definido)
     * @param statusCode Código HTTP que o interceptor deve devolver
     * @param corpo      Corpo da resposta sintética
     */
    private ApiService configurar(String url, String token, int statusCode, String corpo) {
        capturador = new CapturadorDeRequisicao(statusCode, corpo);
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(capturador)
                .build();

        Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
        if (url != null)   prefs.put("url",   url);
        if (token != null) prefs.put("token", token);

        ConfigService.resetForTest(prefs);
        ApiService.resetForTest(ConfigService.getInstance(), client);
        return ApiService.getInstance();
    }

    // -------------------------------------------------------------------------
    // Construção de URL
    // -------------------------------------------------------------------------

    @Test
    void urlComBaseUrlSemBarraFinalAdicionaBarraFinal() throws Exception {
        ApiService api = configurar("http://servidor", "tok", 200, "[]");
        api.get("app", "model", null, null, null);

        String url = capturador.ultima.url().toString();
        assertTrue(url.startsWith("http://servidor/api/app/model/"),
                "URL esperada: http://servidor/api/app/model/... — obtida: " + url);
    }

    @Test
    void urlComBaseUrlComBarraFinalNaoDuplicaABarra() throws Exception {
        ApiService api = configurar("http://servidor/", "tok", 200, "[]");
        api.get("app", "model", null, null, null);

        String url = capturador.ultima.url().toString();
        assertFalse(url.contains("//api"),
                "URL não deve conter '//api': " + url);
    }

    @Test
    void urlInclueAppLabelModelNameIdEAction() throws Exception {
        ApiService api = configurar("http://servidor", "tok", 200, "[]");
        api.get("legislativo", "documentos", 42, "assinar", null);

        String url = capturador.ultima.url().toString();
        assertTrue(url.contains("/api/legislativo/documentos/42/assinar"),
                "URL deve conter /api/legislativo/documentos/42/assinar: " + url);
    }

    @Test
    void urlComIdSemActionInclueId() throws Exception {
        ApiService api = configurar("http://servidor", "tok", 200, "[]");
        api.get("app", "model", 99, null, null);

        String url = capturador.ultima.url().toString();
        assertTrue(url.contains("/99/"),
                "URL deve conter /99/: " + url);
    }

    @Test
    void urlComQueryParamsDecodificadosCorretamente() throws Exception {
        ApiService api = configurar("http://servidor", "tok", 200, "[]");
        Map<String, Object> params = new HashMap<>();
        params.put("busca", "documento assinado");
        params.put("pagina", 2);
        api.get("app", "model", null, null, params);

        HttpUrl url = capturador.ultima.url();
        assertEquals("documento assinado", url.queryParameter("busca"),
                "'busca' deve ser decodificado para 'documento assinado'");
        assertEquals("2", url.queryParameter("pagina"),
                "'pagina' deve ser '2'");
    }

    @Test
    void urlSemParamsNaoContemInterrogacao() throws Exception {
        ApiService api = configurar("http://servidor", "tok", 200, "[]");
        api.get("app", "model", 1, "action", null);

        assertNull(capturador.ultima.url().query(),
                "URL sem params não deve ter query string");
    }

    // -------------------------------------------------------------------------
    // Cabeçalho Authorization
    // -------------------------------------------------------------------------

    @Test
    void headerAuthorizationEnviadoComToken() throws Exception {
        ApiService api = configurar("http://servidor", "meu-token-123", 200, "[]");
        api.get("app", "model", null, null, null);

        assertEquals("Token meu-token-123",
                capturador.ultima.header("Authorization"),
                "Authorization deve ser 'Token meu-token-123'");
    }

    @Test
    void headerAuthorizationAusenteQuandoTokenVazio() throws Exception {
        ApiService api = configurar("http://servidor", "", 200, "[]");
        api.get("app", "model", null, null, null);

        assertNull(capturador.ultima.header("Authorization"),
                "Sem token o cabeçalho Authorization não deve ser enviado");
    }

    // -------------------------------------------------------------------------
    // Métodos HTTP
    // -------------------------------------------------------------------------

    @Test
    void getUsaMetodoHTTPGET() throws Exception {
        ApiService api = configurar("http://servidor", "tok", 200, "[]");
        api.get("app", "model", null, null, null);
        assertEquals("GET", capturador.ultima.method());
    }

    @Test
    void postUsaMetodoHTTPPOST() throws Exception {
        ApiService api = configurar("http://servidor", "tok", 200, "{}");
        api.post("app", "model", null, null, null, null);
        assertEquals("POST", capturador.ultima.method());
    }

    @Test
    void putUsaMetodoHTTPPUT() throws Exception {
        ApiService api = configurar("http://servidor", "tok", 200, "{}");
        api.put("app", "model", 1, null, null, null);
        assertEquals("PUT", capturador.ultima.method());
    }

    @Test
    void patchUsaMetodoHTTPPATCH() throws Exception {
        ApiService api = configurar("http://servidor", "tok", 200, "{}");
        api.patch("app", "model", 1, null, null, null);
        assertEquals("PATCH", capturador.ultima.method());
    }

    // -------------------------------------------------------------------------
    // Corpo da requisição
    // -------------------------------------------------------------------------

    @Test
    void postComMapSimplesEnviaJSON() throws Exception {
        ApiService api = configurar("http://servidor", "tok", 200, "{}");
        Map<String, Object> form = new HashMap<>();
        form.put("campo", "valor");
        api.post("app", "model", null, null, form, null);

        RequestBody body = capturador.ultima.body();
        assertNotNull(body, "POST com mapa deve enviar body");
        assertTrue(body.contentType().toString().contains("application/json"),
                "Content-Type deve ser application/json: " + body.contentType());
    }

    @Test
    void postComByteArrayEnviaMultipart() throws Exception {
        ApiService api = configurar("http://servidor", "tok", 200, "{}");
        Map<String, Object> form = new HashMap<>();
        form.put("arquivo", "conteúdo".getBytes(StandardCharsets.UTF_8));
        api.post("app", "model", null, null, form, null);

        RequestBody body = capturador.ultima.body();
        assertNotNull(body);
        assertTrue(body.contentType().toString().contains("multipart"),
                "Content-Type deve ser multipart: " + body.contentType());
    }

    @Test
    void postSemFormEnviaBodyVazioDeZeroBytes() throws Exception {
        ApiService api = configurar("http://servidor", "tok", 200, "{}");
        api.post("app", "model", null, null, null, null);

        RequestBody body = capturador.ultima.body();
        assertNotNull(body, "POST sem form deve ter body (vazio)");
        assertEquals(0, body.contentLength(),
                "Body vazio deve ter contentLength == 0");
    }

    // -------------------------------------------------------------------------
    // Tratamento de erros HTTP
    // -------------------------------------------------------------------------

    @Test
    void http404LancaRuntimeExceptionComCodigo() throws Exception {
        ApiService api = configurar("http://servidor", "tok", 404, "Not Found");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> api.get("app", "model", 1, null, null));
        assertTrue(ex.getMessage().contains("404"),
                "Mensagem deve conter '404': " + ex.getMessage());
    }

    @Test
    void http500LancaRuntimeExceptionComCodigo() throws Exception {
        ApiService api = configurar("http://servidor", "tok", 500, "Server Error");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> api.get("app", "model", 1, null, null));
        assertTrue(ex.getMessage().contains("500"),
                "Mensagem deve conter '500': " + ex.getMessage());
    }

    // -------------------------------------------------------------------------
    // Validação de configuração
    // -------------------------------------------------------------------------

    @Test
    void urlBaseVaziaLancaIllegalArgumentException() {
        ApiService api = configurar("", null, 200, "[]");

        assertThrows(IllegalArgumentException.class,
                () -> api.get("app", "model", null, null, null),
                "URL base vazia deve lançar IllegalArgumentException");
    }

    // -------------------------------------------------------------------------
    // Classe auxiliar: interceptor que captura a requisição e devolve resposta falsa
    // -------------------------------------------------------------------------

    static class CapturadorDeRequisicao implements Interceptor {

        volatile Request ultima;
        final int statusCode;
        final String corpoResposta;

        CapturadorDeRequisicao(int statusCode, String corpoResposta) {
            this.statusCode = statusCode;
            this.corpoResposta = corpoResposta;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            ultima = chain.request();
            byte[] bytes = corpoResposta.getBytes(StandardCharsets.UTF_8);
            return new Response.Builder()
                    .request(ultima)
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message(statusCode < 400 ? "OK" : "Erro")
                    .body(ResponseBody.create(
                            MediaType.parse("application/json; charset=utf-8"), bytes))
                    .build();
        }
    }
}
