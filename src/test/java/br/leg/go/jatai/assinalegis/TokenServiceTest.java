package br.leg.go.jatai.assinalegis;

import org.junit.jupiter.api.Test;

import javax.security.auth.login.FailedLoginException;
import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Nível 4 — TokenService: detecção de bibliotecas PKCS#11 e lógica de erro de PIN.
 *
 * Estratégia:
 *  - Nenhum token físico é necessário; testes usam caminhos inexistentes para forçar falhas.
 *  - Testes que dependem da ausência de tokens usam assumeTrue/assumeFalse para serem
 *    ignorados graciosamente em máquinas com token instalado.
 *  - isPinError é package-private (mesma package/módulo) para acesso direto sem reflexão.
 */
class TokenServiceTest {

    // -------------------------------------------------------------------------
    // detectLibraries()
    // -------------------------------------------------------------------------

    @Test
    void detectLibrariesNuncaRetornaNull() {
        assertNotNull(new TokenService().detectLibraries());
    }

    @Test
    void detectLibrariesRetornaApenasArquivosExistentes() {
        List<String> libs = new TokenService().detectLibraries();
        for (String lib : libs) {
            assertTrue(new File(lib).exists(),
                    "Caminho retornado por detectLibraries() não existe no sistema: " + lib);
        }
    }

    @Test
    void detectLibrariesRetornaListaVaziaQuandoNenhumTokenInstalado() {
        List<String> libs = new TokenService().detectLibraries();
        assumeTrue(libs.isEmpty(),
                "Ignorado: biblioteca(s) PKCS#11 encontrada(s) neste ambiente — " + libs);
        assertTrue(libs.isEmpty());
    }

    // -------------------------------------------------------------------------
    // detectLibrary()
    // -------------------------------------------------------------------------

    @Test
    void detectLibraryRetornaNullQuandoListaVazia() {
        TokenService service = new TokenService();
        assumeTrue(service.detectLibraries().isEmpty(),
                "Ignorado: biblioteca(s) PKCS#11 encontrada(s) neste ambiente");
        assertNull(service.detectLibrary());
    }

    @Test
    void detectLibraryRetornaPrimeiraBibliotecaEncontrada() {
        TokenService service = new TokenService();
        List<String> libs = service.detectLibraries();
        assumeFalse(libs.isEmpty(),
                "Ignorado: nenhuma biblioteca PKCS#11 encontrada neste ambiente");
        assertEquals(libs.get(0), service.detectLibrary());
    }

    // -------------------------------------------------------------------------
    // getKeyStore(char[]) — sem bibliotecas
    // -------------------------------------------------------------------------

    @Test
    void getKeyStoreComListaVaziaLancaRuntimeException() {
        TokenService service = new TokenService();
        assumeTrue(service.detectLibraries().isEmpty(),
                "Ignorado: biblioteca(s) PKCS#11 encontrada(s) neste ambiente");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.getKeyStore("1234".toCharArray()));

        assertTrue(ex.getMessage().contains("PKCS#11"),
                "Mensagem de erro deve mencionar 'PKCS#11': " + ex.getMessage());
    }

    // -------------------------------------------------------------------------
    // getKeyStore(String, char[]) — caminho inexistente
    // -------------------------------------------------------------------------

    @Test
    void getKeyStoreComCaminhoInexistenteLancaExcecao() {
        // Independe de token instalado: o caminho definitivamente não existe
        assertThrows(Exception.class,
                () -> new TokenService().getKeyStore("/caminho/inexistente/libtoken.so",
                        "1234".toCharArray()));
    }

    @Test
    void getKeyStoreComCaminhoInexistenteNaoEhErroDePIN() {
        TokenService service = new TokenService();
        Exception ex = assertThrows(Exception.class,
                () -> service.getKeyStore("/caminho/inexistente/libtoken.so",
                        "1234".toCharArray()));

        // Deve ser falha de carregamento da biblioteca, nunca erro de PIN
        assertFalse(service.isPinError(ex),
                "Falha por caminho inexistente não deve ser classificada como erro de PIN");
    }

    // -------------------------------------------------------------------------
    // isPinError() — package-private, acesso direto por estar no mesmo pacote
    // -------------------------------------------------------------------------

    @Test
    void isPinErrorComCKR_PIN_INCORRECT_RetornaTrue() {
        RuntimeException e = new RuntimeException("sun.security.pkcs11.wrapper.PKCS11Exception: CKR_PIN_INCORRECT");
        assertTrue(new TokenService().isPinError(e));
    }

    @Test
    void isPinErrorComCKR_PIN_LOCKED_RetornaTrue() {
        RuntimeException e = new RuntimeException("Erro: CKR_PIN_LOCKED — token bloqueado");
        assertTrue(new TokenService().isPinError(e));
    }

    @Test
    void isPinErrorComCKR_PIN_EXPIRED_RetornaTrue() {
        RuntimeException e = new RuntimeException("CKR_PIN_EXPIRED");
        assertTrue(new TokenService().isPinError(e));
    }

    @Test
    void isPinErrorComFailedLoginExceptionRetornaTrue() {
        FailedLoginException ex = new FailedLoginException("credenciais inválidas");
        assertTrue(new TokenService().isPinError(ex));
    }

    @Test
    void isPinErrorComExcecaoGenericaRetornaFalse() {
        assertFalse(new TokenService().isPinError(new RuntimeException("I/O error ao ler biblioteca")));
        assertFalse(new TokenService().isPinError(new IllegalArgumentException("caminho inválido")));
        assertFalse(new TokenService().isPinError(new java.io.IOException("arquivo não encontrado")));
    }

    @Test
    void isPinErrorComPinErroNaCausaEncadeadaRetornaTrue() {
        // O método deve percorrer a cadeia de causa (while e != null)
        RuntimeException causa = new RuntimeException("CKR_PIN_INCORRECT");
        RuntimeException wrapper = new RuntimeException("Falha ao abrir token", causa);
        assertTrue(new TokenService().isPinError(wrapper));
    }

    @Test
    void isPinErrorComMensagemNulaNaoLancaNPE() {
        RuntimeException e = new RuntimeException((String) null);
        // getMessage() == null não deve causar NullPointerException
        assertDoesNotThrow(() -> new TokenService().isPinError(e));
        assertFalse(new TokenService().isPinError(e));
    }

    @Test
    void isPinErrorComCadeiaCompletamenteSemPinRetornaFalse() {
        RuntimeException causa2 = new RuntimeException("host unreachable");
        RuntimeException causa1 = new RuntimeException("timeout", causa2);
        RuntimeException raiz = new RuntimeException("conexão falhou", causa1);
        assertFalse(new TokenService().isPinError(raiz));
    }
}
