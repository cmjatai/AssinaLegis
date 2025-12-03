package br.leg.go.jatai.assinalegis;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.CMSTypedData;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class AssinaturaService {

    /**
     * Assina um arquivo PDF usando um certificado do KeyStore.
     *
     * @param arquivoEntrada Arquivo PDF original
     * @param arquivoSaida   Arquivo PDF onde será salvo o documento assinado
     * @param keyStore       KeyStore contendo o certificado e chave privada
     * @param alias          Alias do certificado no KeyStore
     * @param senha          Senha da chave privada
     * @throws Exception Em caso de erro na assinatura
     */
    public void assinarPdf(File arquivoEntrada, File arquivoSaida, KeyStore keyStore, String alias, char[] senha) throws Exception {

        // 1. Recupera Chave Privada e Cadeia de Certificados
        PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, senha);
        Certificate[] certificateChain = keyStore.getCertificateChain(alias);

        if (privateKey == null || certificateChain == null) {
            throw new Exception("Chave privada ou cadeia de certificados não encontrada para o alias: " + alias);
        }

        // 2. Carrega o documento PDF
        try (PDDocument doc = Loader.loadPDF(arquivoEntrada);
             FileOutputStream fos = new FileOutputStream(arquivoSaida)) {

            // 3. Cria a estrutura da assinatura no PDF
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            signature.setName("AssinaLegis");
            signature.setLocation("Jataí - GO");
            signature.setReason("Assinatura Digital ICP-Brasil");
            signature.setSignDate(Calendar.getInstance());

            // 4. Registra a interface de assinatura que fará o trabalho criptográfico
            doc.addSignature(signature, new SignatureInterface() {
                @Override
                public byte[] sign(InputStream content) throws IOException {
                    try {
                        // Lê o conteúdo do PDF que precisa ser assinado
                        byte[] contentBytes = content.readAllBytes();

                        // Prepara a cadeia de certificados para o Bouncy Castle
                        List<Certificate> certList = new ArrayList<>();
                        for (Certificate cert : certificateChain) {
                            certList.add(cert);
                        }
                        JcaCertStore certs = new JcaCertStore(certList);

                        // Configura o gerador de assinatura CMS (PKCS#7)
                        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();

                        // Define o algoritmo de assinatura (SHA256 com RSA é padrão ICP-Brasil)
                        ContentSigner sha256Signer = new JcaContentSignerBuilder("SHA256withRSA").build(privateKey);

                        gen.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                                new JcaDigestCalculatorProviderBuilder().build())
                                .build(sha256Signer, (X509Certificate) certificateChain[0]));

                        gen.addCertificates(certs);

                        // Gera a assinatura
                        CMSTypedData msg = new CMSProcessableByteArray(contentBytes);
                        // false = detached signature (o PDF contém o conteúdo, a assinatura fica separada na estrutura)
                        CMSSignedData signedData = gen.generate(msg, false);

                        return signedData.getEncoded();
                    } catch (Exception e) {
                        throw new IOException("Erro ao gerar assinatura criptográfica", e);
                    }
                }
            });

            // 5. Salva o documento assinado (Incremental save é obrigatório para assinaturas)
            doc.saveIncremental(fos);
        }
    }
}
