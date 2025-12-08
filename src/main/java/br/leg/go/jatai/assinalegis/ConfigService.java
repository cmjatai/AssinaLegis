package br.leg.go.jatai.assinalegis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.prefs.Preferences;

public class ConfigService {

    public interface ConfigObserver {
        void onConfigChanged(String key, Object newValue);
    }

    private static final String KEY_URL = "url";
    public static final String KEY_TOKEN = "token";
    private static final String KEY_CERT_PATH = "cert_path";
    private static final String KEY_CERT_PASSWORD = "cert_password";
    public static final String KEY_CASA = "casalegislativa";
    private static final String KEY_SIGNATURE_BG_COLOR = "signature_bg_color";
    private static final String KEY_SIGNATURE_NAME_COLOR = "signature_name_color";
    private static final String KEY_SIGNATURE_DATE_COLOR = "signature_date_color";

    private static ConfigService instance;
    private final Preferences prefs;
    private final ObjectMapper mapper;
    private final List<ConfigObserver> observers = new ArrayList<>();
    private boolean debugMode;

    private ConfigService() {
        this.prefs = Preferences.userNodeForPackage(App.class);
        this.mapper = new ObjectMapper();

        loadDebugMode();

        String currentUrl = getUrl();
        if (currentUrl != null && !currentUrl.isEmpty()) {
            updateCasaLegislativa();
        }
    }

    private void loadDebugMode() {
        // Verifica propriedade do sistema primeiro (útil para debug na IDE)
        String systemDebug = System.getProperty("app.debug");
        if (systemDebug != null) {
            this.debugMode = Boolean.parseBoolean(systemDebug);
            return;
        }

        Properties props = new Properties();
        try (InputStream is = App.class.getResourceAsStream("/application.properties")) {
            if (is != null) {
                props.load(is);
                String debugStr = props.getProperty("app.debug", "false");
                this.debugMode = Boolean.parseBoolean(debugStr);
            }
        } catch (Exception e) {
            e.printStackTrace();
            this.debugMode = false;
        }
    }

    public static synchronized ConfigService getInstance() {
        if (instance == null) {
            instance = new ConfigService();
        }
        return instance;
    }

    public void addObserver(ConfigObserver observer) {
        observers.add(observer);
    }

    public void removeObserver(ConfigObserver observer) {
        observers.remove(observer);
    }

    private void notifyObservers(String key, Object newValue) {
        for (ConfigObserver observer : observers) {
            observer.onConfigChanged(key, newValue);
        }
    }

    public String getUrl() {
        return prefs.get(KEY_URL, "");
    }

    public void setUrl(String url) {
        String oldUrl = getUrl();
        prefs.put(KEY_URL, url);

        if (url != null && !url.isEmpty() && !url.equals(oldUrl)) {
            updateCasaLegislativa();
        }
    }

    private void updateCasaLegislativa() {
        new Thread(() -> {
            try {
                InputStream response = ApiService.getInstance().get("base", "casalegislativa", null, null, null);
                JsonNode root = mapper.readTree(response);
                if (root.has("results") && root.get("results").isArray()) {
                    JsonNode results = root.get("results");
                    if (results.size() > 0) {
                        setCasaLegislativa(results.get(0));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    public String getToken() {
        return prefs.get(KEY_TOKEN, "");
    }

    public void setToken(String token) {
        token = token != null ? token : "";
        prefs.put(KEY_TOKEN, token);
        notifyObservers(KEY_TOKEN, token);
    }

    public String getCertPath() {
        return prefs.get(KEY_CERT_PATH, "");
    }

    public void setCertPath(String certPath) {
        prefs.put(KEY_CERT_PATH, certPath);
    }

    public String getCertPassword() {
        return prefs.get(KEY_CERT_PASSWORD, "");
    }

    public void setCertPassword(String password) {
        prefs.put(KEY_CERT_PASSWORD, password != null ? password : "");
    }

    public String getSignatureBgColor() {
        return prefs.get(KEY_SIGNATURE_BG_COLOR, "#003d71");
    }

    public void setSignatureBgColor(String color) {
        prefs.put(KEY_SIGNATURE_BG_COLOR, color);
    }

    public boolean isDebug() {
        return debugMode;
    }

    public String getSignatureNameColor() {
        return prefs.get(KEY_SIGNATURE_NAME_COLOR, "#ffffff");
    }

    public void setSignatureNameColor(String color) {
        prefs.put(KEY_SIGNATURE_NAME_COLOR, color);
    }

    public String getSignatureDateColor() {
        return prefs.get(KEY_SIGNATURE_DATE_COLOR, "#ffff00");
    }

    public void setSignatureDateColor(String color) {
        prefs.put(KEY_SIGNATURE_DATE_COLOR, color);
    }

    public <T> T getCasaLegislativa(Class<T> type) {
        String json = prefs.get(KEY_CASA, null);
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Erro ao ler configuração da Casa Legislativa", e);
        }
    }

    public void setCasaLegislativa(Object casa) {
        try {
            String json = mapper.writeValueAsString(casa);
            prefs.put(KEY_CASA, json);
            notifyObservers(KEY_CASA, casa);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Erro ao salvar configuração da Casa Legislativa", e);
        }
    }
}
