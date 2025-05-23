package org.Analytics;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import java.util.stream.Collectors;

public class EventTracker {
    private static final String TAG = "EventTracker";
    private static EventStorageHandler storageHandler = null;
    private static boolean debugMode = true;
    private static final int MAX_RETRIES = 2;
    private static final int TIMEOUT_MS = 8000;
    private static String backendBaseUrl;

    public static void configureBackendUrl(String baseUrl) {
        backendBaseUrl = baseUrl;
    }

    public interface EventStorageHandler {
        void storeEvent(String eventName, Map<String, String> properties);
    }

    public static void setStorageHandler(EventStorageHandler handler) {
        storageHandler = handler;
    }

    public static void setDebugMode(boolean enabled) {
        debugMode = enabled;
    }

    public static void trackEvent(String eventName, String appId, Map<String, String> eventProperties) {
        String deviceId = Analytics.getDeviceId();
        String safeDeviceId = deviceId != null ? deviceId : "Unknown";

        Map<String, String> properties = eventProperties != null
                ? new HashMap<>(eventProperties)
                : new HashMap<>();

        if (storageHandler != null) {
            storageHandler.storeEvent(eventName, properties);
        }

        if (debugMode) {
            log("Event: " + eventName);
            log("Device ID: " + safeDeviceId);
            properties.forEach((k, v) -> log("  " + k + ": " + v));
        }

        sendToNestAnalyticsWithRetry(eventName, appId, safeDeviceId, properties, MAX_RETRIES);
    }

    private static void sendToNestAnalyticsWithRetry(String eventName, String appId,
            String deviceId, Map<String, String> properties, int retriesLeft) {
        new Thread(() -> {
            try {
                sendToNestAnalytics(eventName, appId, deviceId, properties);
            } catch (Exception e) {
                if (retriesLeft > 0) {
                    log("Retrying (" + (MAX_RETRIES - retriesLeft + 1) + "/" + MAX_RETRIES + ")...");
                    sendToNestAnalyticsWithRetry(eventName, appId, deviceId, properties, retriesLeft - 1);
                } else {
                    logError("All retries failed for event: " + eventName, e);
                }
            }
        }).start();
    }

    private static void sendToNestAnalytics(String eventName, String appId,
            String deviceId, Map<String, String> properties) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(backendBaseUrl + "/analytics-event/track");
            log("🔗 Connecting to: " + url);

            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            JSONObject json = new JSONObject()
                    .put("eventName", eventName)
                    .put("appId", appId)
                    .put("deviceId", deviceId)
                    .put("properties", new JSONObject(properties));

            String payload = json.toString();
            log("Payload: " + payload);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            String responseMsg = conn.getResponseMessage();
            log("Response: " + responseCode + " - " + responseMsg);

            if (responseCode >= 400) {
                String errorBody = readErrorStream(conn);
                throw new IOException("Server error: " + responseCode + " - " + errorBody);
            }

            String responseBody = readInputStream(conn.getInputStream());
            log("Response body: " + responseBody);

        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readInputStream(InputStream is) throws IOException {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return br.lines().collect(Collectors.joining("\n"));
        }
    }

    private static String readErrorStream(HttpURLConnection conn) {
        try {
            InputStream es = conn.getErrorStream();
            return es != null ? readInputStream(es) : "No error body";
        } catch (Exception e) {
            return "Error reading error stream: " + e.getMessage();
        }
    }

    private static void log(String message) {
        if (debugMode) {
            System.out.println(TAG + " - " + message);
        }
    }

    private static void logError(String message, Throwable e) {
        System.err.println(TAG + " - " + message);
        if (debugMode && e != null) {
            e.printStackTrace();
        }
    }
}