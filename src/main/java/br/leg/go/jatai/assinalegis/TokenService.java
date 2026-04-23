package br.leg.go.jatai.assinalegis;

import java.io.File;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;

public class TokenService {

    private static final String[] LINUX_LIBS = {
        "/usr/lib/libaetpkss.so",       // SafeSign
        "/usr/lib/libgclib.so",         // Gemalto
        "/usr/lib64/libaetpkss.so",
        "/usr/lib64/libgclib.so",
        "/usr/lib/x86_64-linux-gnu/libaetpkss.so",
        "/usr/lib/x86_64-linux-gnu/libgclib.so",
        "/usr/local/lib/libaetpkss.so",
        "/usr/local/lib/libgclib.so",
        "/usr/lib/libepsng_p11.so",     // Epad
        "/usr/lib/libwdpkcs.so",         // WatchData
        "/usr/lib/libeTokenHID.so",
        "/usr/lib/libeToken.so",
        "/usr/lib64/libeToken.so",
        "/usr/lib/libeTPkcs11.so",      // eToken
        "/usr/lib/x86_64-linux-gnu/libeToken.so"
    };

    private static final String[] WINDOWS_LIBS = {
        "c:/windows/system32/aetpkss1.dll", // SafeSign
        "c:/windows/system32/gclib.dll",    // Gemalto
        "c:/windows/system32/eTPKCS11.dll", // eToken
        "c:/windows/system32/epsng_p11.dll", // Epad
        "c:/windows/system32/wdpkcs.dll"    // WatchData
    };

    public List<String> detectLibraries() {
        String os = System.getProperty("os.name").toLowerCase();
        String[] libs = os.contains("win") ? WINDOWS_LIBS : LINUX_LIBS;
        List<String> found = new ArrayList<>();

        for (String lib : libs) {
            File f = new File(lib);
            if (f.exists()) {
                found.add(lib);
            }
        }
        return found;
    }

    public String detectLibrary() {
        List<String> libs = detectLibraries();
        return libs.isEmpty() ? null : libs.get(0);
    }

    public KeyStore getKeyStore(char[] pin) throws Exception {
        List<String> libraries = detectLibraries();
        if (libraries.isEmpty()) {
            throw new RuntimeException("Nenhuma biblioteca PKCS#11 encontrada no sistema.");
        }

        Exception lastException = null;
        for (String libraryPath : libraries) {
            try {
                return getKeyStore(libraryPath, pin);
            } catch (Exception e) {
                // Se o erro raiz for PIN incorreto, interrompe imediatamente
                // para não arriscar bloquear o token com tentativas repetidas
                if (isPinError(e)) {
                    throw new RuntimeException("PIN incorreto. Verifique a senha informada.", e);
                }
                lastException = e;
                System.err.println("Falha ao carregar token com " + libraryPath + ": " + e.getMessage());
            }
        }
        throw new RuntimeException("Nenhuma biblioteca PKCS#11 conseguiu abrir o token.", lastException);
    }

    private boolean isPinError(Throwable e) {
        while (e != null) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("CKR_PIN_INCORRECT")
                    || msg.contains("CKR_PIN_LOCKED")
                    || msg.contains("CKR_PIN_EXPIRED"))) {
                return true;
            }
            if (e instanceof javax.security.auth.login.FailedLoginException) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }

    public KeyStore getKeyStore(String libraryPath, char[] pin) throws Exception {
        // Configuração inline para o Provider (prefixo "--" obrigatório desde Java 9)
        String config = "--name=SmartCard\nlibrary=" + libraryPath;

        // Recupera o provider SunPKCS11 do sistema
        Provider p = Security.getProvider("SunPKCS11");
        if (p == null) {
             throw new RuntimeException("Provider SunPKCS11 não disponível neste JDK. Verifique se o módulo jdk.crypto.cryptoki está disponível.");
        }

        // Configura uma nova instância do provider com a biblioteca detectada
        p = p.configure(config);

        // Adiciona o provider configurado
        Security.addProvider(p);

        // Instancia o KeyStore usando o provider específico
        KeyStore ks = KeyStore.getInstance("PKCS11", p);

        // Carrega o KeyStore
        ks.load(null, pin);

        return ks;
    }
}
