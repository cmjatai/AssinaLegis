package br.leg.go.jatai.assinalegis;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class ApiService {

    private static ApiService instance;
    private final ConfigService configService;
    private final HttpClient client;
    private final ObjectMapper mapper;

    private ApiService() {
        this.configService = ConfigService.getInstance();
        this.mapper = new ObjectMapper();
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public static synchronized ApiService getInstance() {
        if (instance == null) {
            instance = new ApiService();
        }
        return instance;
    }

    public InputStream get(String appLabel, String modelName, Integer id, String action, Map<String, Object> params) throws Exception {
        return request("GET", appLabel, modelName, id, action, null, params);
    }

    public InputStream post(String appLabel, String modelName, Integer id, String action, Object form, Map<String, Object> params) throws Exception {
        return request("POST", appLabel, modelName, id, action, form, params);
    }

    public InputStream put(String appLabel, String modelName, Integer id, String action, Object form, Map<String, Object> params) throws Exception {
        return request("PUT", appLabel, modelName, id, action, form, params);
    }

    public InputStream patch(String appLabel, String modelName, Integer id, String action, Object form, Map<String, Object> params) throws Exception {
        return request("PATCH", appLabel, modelName, id, action, form, params);
    }

    @SuppressWarnings("unchecked")
    private InputStream request(String method, String appLabel, String modelName, Integer id, String action, Object form, Map<String, Object> params) throws Exception {
        String baseUrl = configService.getUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IllegalArgumentException("URL_BASE não configurada.");
        }

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        StringBuilder urlBuilder = new StringBuilder(baseUrl);
        urlBuilder.append("/api");

        if (appLabel != null && !appLabel.isEmpty()) {
            urlBuilder.append("/").append(appLabel);
        }

        if (modelName != null && !modelName.isEmpty()) {
            urlBuilder.append("/").append(modelName);
        }

        if (id != null) {
            urlBuilder.append("/").append(id);
        }

        if (action != null && !action.isEmpty()) {
            urlBuilder.append("/").append(action);
        }

        // Garante a barra final antes dos parâmetros
        if (urlBuilder.charAt(urlBuilder.length() - 1) != '/') {
            if (!urlBuilder.toString().endsWith("token"))
                urlBuilder.append("/");
        }

        if (params != null && !params.isEmpty()) {
            urlBuilder.append("?");
            String query = params.entrySet().stream()
                    .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));
            urlBuilder.append(query);
        }

        URI uri = URI.create(urlBuilder.toString());
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri);

        String token = configService.getToken();
        if (token != null && !token.isEmpty()) {
            requestBuilder.header("Authorization", "Token " + token);
        }

        HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.noBody();

        if (form != null && (method.equals("POST") || method.equals("PUT") || method.equals("PATCH"))) {
            if (isMultipart(form)) {
                String boundary = "--WebKitFormBoundary" + UUID.randomUUID().toString().replace("-", "") + "---";
                requestBuilder.header("Content-Type", "multipart/form-data; boundary=" + boundary);
                bodyPublisher = ofMimeMultipartData((Map<String, Object>) form, boundary);
            } else {
                requestBuilder.header("Content-Type", "application/json");
                String json = mapper.writeValueAsString(form);
                bodyPublisher = HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8);
            }
        }

        requestBuilder.method(method, bodyPublisher);

        HttpResponse<InputStream> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() >= 400) {
            try (InputStream is = response.body()) {
                String error = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                throw new RuntimeException("API Error: " + response.statusCode() + " - " + error);
            }
        }

        return response.body();
    }

    private boolean isMultipart(Object form) {
        if (form instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) form;
            for (Object value : map.values()) {
                if (value instanceof InputStream || value instanceof File || value instanceof byte[] || value instanceof FileData) {
                    return true;
                }
            }
        }
        return false;
    }

    private HttpRequest.BodyPublisher ofMimeMultipartData(Map<String, Object> data, String boundary) throws IOException {
        List<byte[]> byteArrays = new ArrayList<>();
        String separator = "--" + boundary + "\r\n";

        for (Map.Entry<String, Object> entry : data.entrySet()) {
            byteArrays.add(separator.getBytes(StandardCharsets.UTF_8));

            if (entry.getValue() instanceof File) {
                File file = (File) entry.getValue();
                String mimeType = Files.probeContentType(file.toPath());
                if (mimeType == null) mimeType = "application/octet-stream";

                String header = "Content-Disposition: form-data; name=\"" + entry.getKey() + "\"; filename=\"" + file.getName() + "\"\r\n" +
                        "Content-Type: " + mimeType + "\r\n\r\n";
                byteArrays.add(header.getBytes(StandardCharsets.UTF_8));
                byteArrays.add(Files.readAllBytes(file.toPath()));
                byteArrays.add("\r\n".getBytes(StandardCharsets.UTF_8));
            } else if (entry.getValue() instanceof InputStream) {
                 InputStream is = (InputStream) entry.getValue();
                 String header = "Content-Disposition: form-data; name=\"" + entry.getKey() + "\"; filename=\"blob\"\r\n" +
                        "Content-Type: application/octet-stream\r\n\r\n";
                 byteArrays.add(header.getBytes(StandardCharsets.UTF_8));
                 byteArrays.add(is.readAllBytes());
                 byteArrays.add("\r\n".getBytes(StandardCharsets.UTF_8));
            } else if (entry.getValue() instanceof byte[]) {
                 byte[] bytes = (byte[]) entry.getValue();
                 String header = "Content-Disposition: form-data; name=\"" + entry.getKey() + "\"; filename=\"blob\"\r\n" +
                        "Content-Type: application/octet-stream\r\n\r\n";
                 byteArrays.add(header.getBytes(StandardCharsets.UTF_8));
                 byteArrays.add(bytes);
                 byteArrays.add("\r\n".getBytes(StandardCharsets.UTF_8));
            } else if (entry.getValue() instanceof FileData) {
                 FileData fileData = (FileData) entry.getValue();
                 String header = "Content-Disposition: form-data; name=\"" + entry.getKey() + "\"; filename=\"" + fileData.fileName + "\"\r\n" +
                        "Content-Type: " + fileData.mimeType + "\r\n" +
                        "Content-Transfer-Encoding: binary\r\n\r\n";
                 byteArrays.add(header.getBytes(StandardCharsets.UTF_8));

                 byteArrays.add(fileData.content);
                 byteArrays.add("\r\n".getBytes(StandardCharsets.UTF_8));
            } else {
                String header = "Content-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n";
                byteArrays.add(header.getBytes(StandardCharsets.UTF_8));
                byteArrays.add(String.valueOf(entry.getValue()).getBytes(StandardCharsets.UTF_8));
                byteArrays.add("\r\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        byteArrays.add(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        return HttpRequest.BodyPublishers.ofByteArrays(byteArrays);
    }

    public static class FileData {
        public final String fileName;
        public final byte[] content;
        public final String mimeType;

        public FileData(String fileName, byte[] content, String mimeType) {
            this.fileName = fileName;
            this.content = content;
            this.mimeType = mimeType;
        }
    }
}
