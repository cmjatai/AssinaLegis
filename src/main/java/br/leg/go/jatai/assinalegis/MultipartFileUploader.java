package br.leg.go.jatai.assinalegis;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MultipartFileUploader {

    private static final String LINE_FEED = "\r\n";

    /**
     * Envia arquivo em bytes via PATCH para backend Django
     *
     * @param url URL do endpoint Django (ex: http://localhost:8000/api/upload/)
     * @param fileBytes Array de bytes do arquivo
     * @param fieldName Nome do campo no formulário (Django procurará em request.FILES)
     * @param fileName Nome do arquivo (pode ser qualquer nome)
     * @param mimeType Tipo MIME do arquivo (ex: application/octet-stream, image/png, etc)
     * @param authToken Token de autenticação (opcional)
     * @return Resposta do servidor
     * @throws IOException
     */
    public static String sendFilePatch(
            String url,
            byte[] fileBytes,
            String fieldName,
            String fileName,
            String mimeType,
            String authToken
    ) throws IOException {

        String boundary = generateBoundary();

        URL urlObj = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) urlObj.openConnection();

        try {
            setupConnection(connection, boundary, authToken);

            try (OutputStream os = connection.getOutputStream()) {
                writeMultipartBody(os, fileBytes, fieldName, fileName, mimeType, boundary);
            }

            return getResponseBody(connection);

        } finally {
            connection.disconnect();
        }
    }

    /**
     * Configurar headers e método HTTP
     * @throws ProtocolException 
     */
    private static void setupConnection(
            HttpURLConnection connection,
            String boundary,
            String authToken
    ) throws ProtocolException {
        connection.setRequestMethod("PATCH");
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setUseCaches(false);

        // Header Content-Type obrigatório com boundary
        connection.setRequestProperty(
            "Content-Type",
            "multipart/form-data; boundary=" + boundary
        );

        // Se tiver autenticação
        if (authToken != null && !authToken.isEmpty()) {
            connection.setRequestProperty("Authorization", "Token " + authToken);
            // ou "Token " + authToken dependendo do seu setup
        }

        connection.setRequestProperty("Accept", "application/json");
    }

    /**
     * Escrever o corpo do request em formato multipart/form-data
     */
    private static void writeMultipartBody(
            OutputStream os,
            byte[] fileBytes,
            String fieldName,
            String fileName,
            String mimeType,
            String boundary
    ) throws IOException {

        PrintWriter writer = new PrintWriter(
            new OutputStreamWriter(os, StandardCharsets.UTF_8),
            true
        );

        // Escribir boundary inicial
        writer.append("--").append(boundary).append(LINE_FEED);

        // Escribir headers de Content-Disposition
        writer.append("Content-Disposition: form-data; ");
        writer.append("name=\"").append(fieldName).append("\"; ");
        writer.append("filename=\"").append(fileName).append("\"").append(LINE_FEED);

        // Escribir Content-Type do arquivo
        writer.append("Content-Type: ").append(mimeType).append(LINE_FEED);

        // Header de encoding binário
        writer.append("Content-Transfer-Encoding: binary").append(LINE_FEED);

        // Línea en blanco separando headers del contenido
        writer.append(LINE_FEED);
        writer.flush();

        // Escribir los bytes del arquivo directamente
        os.write(fileBytes);
        os.flush();

        // Final com nova línea
        writer.append(LINE_FEED);
        writer.flush();

        // Boundary final
        writer.append("--").append(boundary).append("--").append(LINE_FEED);
        writer.flush();

        writer.close();
    }

    /**
     * Gerar um boundary único
     */
    private static String generateBoundary() {
        return "===" + System.currentTimeMillis() +
               System.nanoTime() +
               Math.random() + "===";
    }

    /**
     * Ler resposta do servidor
     */
    private static String getResponseBody(HttpURLConnection connection) throws IOException {
        int responseCode = connection.getResponseCode();

        InputStream is;
        if (responseCode >= 400) {
            is = connection.getErrorStream();
        } else {
            is = connection.getInputStream();
        }

        if (is == null) {
            return "";
        }

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(is, StandardCharsets.UTF_8)
        );
        StringBuilder response = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            response.append(line).append("\n");
        }

        reader.close();
        return response.toString();
    }
}