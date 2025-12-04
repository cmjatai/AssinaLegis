package br.leg.go.jatai.assinalegis;

import br.leg.go.jatai.assinalegis.DocumentViewerController.DocumentItem;
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

import java.io.ByteArrayOutputStream;
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
     * Assina uma lista de DocumentItems.
     *
     * @param itens    Lista de DocumentItems a serem assinados
     * @param keyStore KeyStore contendo o certificado e chave privada
     * @param alias    Alias do certificado no KeyStore
     * @param senha    Senha da chave privada
     * @throws Exception Em caso de erro na assinatura
     */
    public void assinarDocumentos(List<DocumentItem> itens, KeyStore keyStore, String alias, char[] senha) throws Exception {

        // 1. Recupera Chave Privada e Cadeia de Certificados
        PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, senha);
        Certificate[] certificateChain = keyStore.getCertificateChain(alias);

        if (privateKey == null || certificateChain == null) {
            throw new Exception("Chave privada ou cadeia de certificados não encontrada para o alias: " + alias);
        }

        for (DocumentItem item : itens) {
            byte[] originalBytes = item.getOriginalBytes();
            if (originalBytes == null) {
                // Fallback se não tiver bytes originais (ex: carregado via loadPdfPreview sem salvar no item)
                // Mas idealmente preloadPdf deve garantir isso.
                // Se não tiver, tentamos usar o PDDocument existente, mas isso pode quebrar assinaturas anteriores.
                PDDocument doc = item.getPdDocument();
                if (doc != null) {
                    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        doc.save(baos);
                        originalBytes = baos.toByteArray();
                    }
                } else {
                    continue;
                }
            }

            try (PDDocument docToSign = Loader.loadPDF(originalBytes);
                 ByteArrayOutputStream baosSigned = new ByteArrayOutputStream()) {

                // 3. Cria a estrutura da assinatura no PDF
                PDSignature signature = new PDSignature();
                signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
                signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
                signature.setName("AssinaLegis");
                signature.setLocation("Jataí - GO");
                signature.setReason("Assinatura Digital ICP-Brasil");
                signature.setSignDate(Calendar.getInstance());

                // 4. Registra a interface de assinatura que fará o trabalho criptográfico
                docToSign.addSignature(signature, new SignatureInterface() {
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
                docToSign.saveIncremental(baosSigned);

                // 6. Carrega o documento assinado e salva no item
                PDDocument signedDoc = Loader.loadPDF(baosSigned.toByteArray());
                item.setPdDocumentSigned(signedDoc);

                // 7. Opcional: Salva o documento assinado em arquivo para verificação
                String userHome = System.getProperty("user.home");
                String nomeArquivo = userHome + File.separator + "documento_assinado_" + item.getJsonData().get("id") + ".pdf";
                try (FileOutputStream fos = new FileOutputStream(new File(nomeArquivo))) {
                    fos.write(baosSigned.toByteArray());
                }
            }
        }
    }
}
