package br.leg.go.jatai.assinalegis;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ApiService {

    private static ApiService instance;
    private final ConfigService configService;
    private final OkHttpClient client;
    private final ObjectMapper mapper;

    private ApiService() {
        this.configService = ConfigService.getInstance();
        this.mapper = new ObjectMapper();
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
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

        Request.Builder requestBuilder = new Request.Builder()
                .url(urlBuilder.toString());

        String token = configService.getToken();
        if (token != null && !token.isEmpty()) {
            requestBuilder.header("Authorization", "Token " + token);
        }

        RequestBody body = null;

        if (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
            if (form != null) {
                if (isMultipart(form)) {
                    body = buildMultipartBody((Map<String, Object>) form);
                } else {
                    String json = mapper.writeValueAsString(form);
                    body = RequestBody.create(json, MediaType.parse("application/json; charset=utf-8"));
                }
            } else {
                body = RequestBody.create(new byte[0], null);
            }
        }

        requestBuilder.method(method, body);

        Response response = client.newCall(requestBuilder.build()).execute();

        if (!response.isSuccessful()) {
            try (ResponseBody responseBody = response.body()) {
                String error = responseBody != null ? responseBody.string() : "Unknown error";
                throw new RuntimeException("API Error: " + response.code() + " - " + error);
            }
        }

        return response.body().byteStream();
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

    private RequestBody buildMultipartBody(Map<String, Object> data) {
        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);

        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof File) {
                File file = (File) value;
                String mimeType = "application/octet-stream";
                try {
                    String probe = Files.probeContentType(file.toPath());
                    if (probe != null) mimeType = probe;
                } catch (IOException e) {
                    // ignore
                }
                builder.addFormDataPart(key, file.getName(), RequestBody.create(file, MediaType.parse(mimeType)));
            } else if (value instanceof FileData) {
                FileData fileData = (FileData) value;
                builder.addFormDataPart(key, fileData.fileName, RequestBody.create(fileData.content, MediaType.parse(fileData.mimeType)));
            } else if (value instanceof byte[]) {
                builder.addFormDataPart(key, "blob", RequestBody.create((byte[]) value, MediaType.parse("application/octet-stream")));
            } else if (value instanceof InputStream) {
                try {
                    byte[] bytes = ((InputStream) value).readAllBytes();
                    builder.addFormDataPart(key, "blob", RequestBody.create(bytes, MediaType.parse("application/octet-stream")));
                } catch (IOException e) {
                    throw new RuntimeException("Erro ao ler InputStream para multipart", e);
                }
            } else {
                builder.addFormDataPart(key, String.valueOf(value));
            }
        }
        return builder.build();
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
