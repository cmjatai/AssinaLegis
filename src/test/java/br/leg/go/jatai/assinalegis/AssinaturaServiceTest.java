package br.leg.go.jatai.assinalegis;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nível 6 — AssinaturaService: assinatura digital de PDF.
 *
 * Usa um certificado RSA autoassinado gerado em memória com BouncyCastle.
 * Nunca utiliza certificados reais ou tokens físicos.
 */
class AssinaturaServiceTest {

    private static KeyPair parChaves;
    private static X509Certificate certTeste;
    private static KeyStore keyStoreTeste;
    private static final String ALIAS_TESTE = "chave-teste";
    private static Preferences prefs;

    private AssinaturaService service;
    private DocumentViewerController.DocumentItem item;

    @BeforeAll
    static void gerarCertificadoDeTeste() throws Exception {
        // Gera par de chaves RSA 2048 bits
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        parChaves = kpg.generateKeyPair();

        // Certificado autoassinado com BouncyCastle (apenas para testes)
        X500Name subject = new X500Name("CN=Assinante Teste,O=Camara Test,C=BR");
        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                subject,
                BigInteger.ONE,
                new Date(System.currentTimeMillis() - 1000L),
                new Date(System.currentTimeMillis() + 365L * 24 * 3600 * 1000L),
                subject,
                parChaves.getPublic()
        );
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(parChaves.getPrivate());
        certTeste = new JcaX509CertificateConverter().getCertificate(certBuilder.build(signer));

        // KeyStore PKCS12 em memória
        keyStoreTeste = KeyStore.getInstance("PKCS12");
        keyStoreTeste.load(null, null);
        keyStoreTeste.setKeyEntry(ALIAS_TESTE, parChaves.getPrivate(), new char[0], new Certificate[]{certTeste});

        prefs = Preferences.userRoot().node("assinalegis_test_nivel6");
    }

    @BeforeEach
    void setUp() throws Exception {
        ConfigService.resetForTest(prefs);
        service = new AssinaturaService();

        // PDF mínimo de uma página em memória
        PDDocument doc = new PDDocument();
        doc.addPage(new PDPage(PDRectangle.A4));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.save(baos);
        doc.close();

        item = new DocumentViewerController.DocumentItem("Documento Teste", "Descrição", null);
        item.setOriginalBytes(baos.toByteArray());
    }

    @AfterEach
    void tearDown() throws BackingStoreException {
        if (item != null && item.getPdDocumentSigned() != null) {
            try { item.getPdDocumentSigned().close(); } catch (Exception ignorado) {}
        }
        prefs.clear();
        ConfigService.clearInstanceForTest();
    }

    @AfterAll
    static void removerNode() throws BackingStoreException {
        prefs.removeNode();
    }

    // -----------------------------------------------------------------------
    // Extração de CN
    // -----------------------------------------------------------------------

    @Test
    void extrairCNComCertificadoValido() {
        String cn = AssinaturaService.extrairCN(certTeste);
        assertEquals("Assinante Teste", cn);
    }

    @Test
    void extrairCNSemAtributoCNRetornaVazio() throws Exception {
        X500Name subjectSemCN = new X500Name("O=Camara Test,C=BR");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subjectSemCN, BigInteger.TWO,
                new Date(System.currentTimeMillis() - 1000L),
                new Date(System.currentTimeMillis() + 1000L),
                subjectSemCN, parChaves.getPublic()
        );
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(parChaves.getPrivate());
        X509Certificate certSemCN = new JcaX509CertificateConverter().getCertificate(builder.build(signer));

        assertEquals("", AssinaturaService.extrairCN(certSemCN));
    }

    // -----------------------------------------------------------------------
    // Fator de escala
    // -----------------------------------------------------------------------

    @Test
    void scaleFactorViewerParaPDF() {
        assertEquals(72.0 / 200.0, AssinaturaService.SCALE_FACTOR_PDF, 1e-10);
    }

    // -----------------------------------------------------------------------
    // Assinatura digital
    // -----------------------------------------------------------------------

    @Test
    void assinarDocumentoCriaSignedBytes() throws Exception {
        service.assinarDocumentos(List.of(item), keyStoreTeste, ALIAS_TESTE, new char[0]);
        assertNotNull(item.getSignedBytes());
        assertTrue(item.getSignedBytes().length > 0);
    }

    @Test
    void pdfAssinadoContemPDSignature() throws Exception {
        service.assinarDocumentos(List.of(item), keyStoreTeste, ALIAS_TESTE, new char[0]);
        try (PDDocument signedDoc = Loader.loadPDF(item.getSignedBytes())) {
            List<PDSignature> sigs = signedDoc.getSignatureDictionaries();
            assertFalse(sigs.isEmpty(), "O PDF assinado deve conter ao menos uma assinatura");
        }
    }

    @Test
    void assinaturaPossuiFiltroAdobePPKLite() throws Exception {
        service.assinarDocumentos(List.of(item), keyStoreTeste, ALIAS_TESTE, new char[0]);
        try (PDDocument signedDoc = Loader.loadPDF(item.getSignedBytes())) {
            PDSignature sig = signedDoc.getSignatureDictionaries().get(0);
            assertEquals("Adobe.PPKLite", sig.getFilter());
        }
    }

    @Test
    void assinaturaPossuiSubfiltroDetached() throws Exception {
        service.assinarDocumentos(List.of(item), keyStoreTeste, ALIAS_TESTE, new char[0]);
        try (PDDocument signedDoc = Loader.loadPDF(item.getSignedBytes())) {
            PDSignature sig = signedDoc.getSignatureDictionaries().get(0);
            assertEquals("adbe.pkcs7.detached", sig.getSubFilter());
        }
    }

    @Test
    void assinaturaContemRazaoIcpBrasil() throws Exception {
        service.assinarDocumentos(List.of(item), keyStoreTeste, ALIAS_TESTE, new char[0]);
        try (PDDocument signedDoc = Loader.loadPDF(item.getSignedBytes())) {
            PDSignature sig = signedDoc.getSignatureDictionaries().get(0);
            assertEquals("Assinatura Digital ICP-Brasil", sig.getReason());
        }
    }

    @Test
    void assinarComAliasInvalidoLancaException() {
        assertThrows(Exception.class, () ->
                service.assinarDocumentos(List.of(item), keyStoreTeste, "alias-inexistente", new char[0])
        );
    }

    @Test
    void pdfAssinadoEhCarregavel() throws Exception {
        service.assinarDocumentos(List.of(item), keyStoreTeste, ALIAS_TESTE, new char[0]);
        assertDoesNotThrow(() -> {
            try (PDDocument doc = Loader.loadPDF(item.getSignedBytes())) {
                assertTrue(doc.getNumberOfPages() > 0);
            }
        });
    }
}
