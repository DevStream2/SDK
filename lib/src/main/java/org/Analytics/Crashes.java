package org.Analytics;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.util.regex.*;

import com.google.gson.*;

public class Crashes {
    private static final int MAX_BREADCRUMBS = 50;
    private static final ConcurrentLinkedQueue<String> breadcrumbs = new ConcurrentLinkedQueue<>();
    private static boolean isInitialized = false;
    private static final String TAG = "CRASHES_SDK";
    private static Thread.UncaughtExceptionHandler defaultExceptionHandler;
    private static volatile Thread mainThread;
    private static String appId;
    private static String backendBaseUrl;
    private static boolean sendToBackend = true;
    private static final Gson gson = new GsonBuilder().create();
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public enum IssueType {
        CRASH("Crash", "crash", "CRITICAL"),
        ERROR("Error", "Error", "HIGH"),
        WARNING("Warning", "Warning", "MEDIUM"),
        INFO("Info", "Info", "LOW"),
        DEBUG("Debug", "Debug", "DEBUG");

        private final String displayName;
        private final String emoji;
        private final String severity;

        IssueType(String displayName, String emoji, String severity) {
            this.displayName = displayName;
            this.emoji = emoji;
            this.severity = severity;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getEmoji() {
            return emoji;
        }

        public String getSeverity() {
            return severity;
        }
    }

    public enum LogLevel {
        VERBOSE, DEBUG, INFO, WARNING, ERROR, CRASH
    }

    public interface CrashReporter {
        void reportCrash(String crashReport, Throwable ex);

        void reportError(String errorReport, Throwable ex);
    }

    private static CrashReporter crashReporter = new DefaultCrashReporter();

    private static class DefaultCrashReporter implements CrashReporter {
        @Override
        public void reportCrash(String crashReport, Throwable ex) {
            System.err.println(crashReport);
            if (sendToBackend) {
                sendToBackend(crashReport, IssueType.CRASH, ex);
            }
        }

        @Override
        public void reportError(String errorReport, Throwable ex) {
            System.err.println(errorReport);
            if (sendToBackend) {
                sendToBackend(errorReport, IssueType.ERROR, ex);
            }
        }
    }

    // Initialisation
    public static void initialize(String appId) {
        initialize(appId, LogLevel.INFO);
    }

    public static void initialize(String appId, LogLevel level) {
        initialize(appId, level, null, true);
    }

    public static void initialize(String appId, LogLevel level, String backendUrl, boolean enableBackendReporting) {
        if (isInitialized) {
            logWarning("Crashes SDK already initialized");
            return;
        }

        backendBaseUrl = backendUrl;
        Crashes.appId = appId;
        mainThread = Thread.currentThread();

        Crashes.sendToBackend = enableBackendReporting;

        interceptDefaultExceptionHandler();
        protectMainThread();

        isInitialized = true;
        logInfo("Crashes SDK initialized successfully with appId: " + appId);
        if (sendToBackend) {
            logInfo("Backend reporting enabled to: " + Crashes.backendBaseUrl);
        }
    }

    // Méthodes principales
    public static void trackCrash(Throwable ex) {
        trackIssue(ex, IssueType.CRASH);
    }

    public static void trackError(Throwable ex) {
        trackError(ex, null, null);
    }

    public static void trackError(Throwable ex, String context) {
        trackError(ex, context, null);
    }

    public static void trackError(Throwable ex, String context, Map<String, String> properties) {
        if (!isInitialized) {
            System.err.println("Crashes SDK not initialized! Error not tracked: " + ex);
            return;
        }

        if (context != null) {
            addBreadcrumb("Error Context: " + context, IssueType.ERROR);
        }

        if (properties != null) {
            properties.forEach((key, value) -> addBreadcrumb(key + ": " + value, IssueType.ERROR));
        }

        String report = buildEnhancedReport(Thread.currentThread(), ex, IssueType.ERROR, null);
        crashReporter.reportError(report, ex);
    }

    public static void addBreadcrumb(String event) {
        addBreadcrumb(event, IssueType.INFO);
    }

    public static void addBreadcrumb(String event, IssueType type) {
        try {
            if (breadcrumbs.size() >= MAX_BREADCRUMBS) {
                breadcrumbs.poll();
            }
            String timestamp = Instant.now().toString();
            breadcrumbs.add(timestamp + " - " + type.getEmoji() + " [" + type.getDisplayName() + "] " + event);
            logDebug("Breadcrumb added: " + event);
        } catch (Exception e) {
            System.err.println("Error adding breadcrumb: " + e.getMessage());
        }
    }

    // Méthodes internes
    private static void interceptDefaultExceptionHandler() {
        defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                handleUncaughtException(thread, ex, IssueType.CRASH);
                if (defaultExceptionHandler != null) {
                    defaultExceptionHandler.uncaughtException(thread, ex);
                }
            }
        });
    }

    private static void protectMainThread() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            try {
                if (mainThread != null && !mainThread.isAlive()) {
                    logError("Main thread has died unexpectedly!", IssueType.CRASH);
                    crashReporter.reportCrash("Main thread has died unexpectedly!",
                            new RuntimeException("Main thread has died unexpectedly!"));
                }
            } catch (Exception e) {
                System.err.println("Error in main thread watchdog: " + e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private static void handleUncaughtException(Thread thread, Throwable ex, IssueType type) {
        try {
            String report = buildEnhancedReport(thread, ex, type, null);
            logIssue(report, type);

            if (type == IssueType.CRASH) {
                crashReporter.reportCrash(report, ex);
            } else {
                crashReporter.reportError(report, ex);
            }
        } catch (Exception e) {
            System.err.println("Error handling uncaught exception: " + e.getMessage());
        }
    }

    private static void trackIssue(Throwable ex, IssueType type) {
        Thread currentThread = Thread.currentThread();
        if (isInitialized) {
            handleUncaughtException(currentThread, ex, type);
        } else {
            logError("Crashes SDK not initialized! Exception: " + ex, IssueType.ERROR);
            System.err.println("Crashes SDK not initialized! Exception: " + ex);
        }
    }

    // Envoi au backend
    private static void sendToBackend(String report, IssueType type, Throwable ex) {
        executor.execute(() -> {
            try {
                String issueId = generateStableIssueId(ex);
                String timestamp = Instant.now().toString();
                DeviceInfo deviceInfo = detectDevice();

                String jsonPayload = buildJsonPayload(report, type, issueId, timestamp, deviceInfo, ex);

                System.out.println("======= SENDING TO BACKEND =======");
                
                System.out.println("Payload: " + jsonPayload);

                HttpURLConnection connection = null;
                try {
                    URL url = new URL(backendBaseUrl + "/crashes");
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("POST");
                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setDoOutput(true);
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);

                    try (OutputStream os = connection.getOutputStream()) {
                        byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                        os.write(input, 0, input.length);
                    }

                    int responseCode = connection.getResponseCode();
                    String response = readResponse(connection);

                    System.out.println("Backend response: " + responseCode);
                    System.out.println("Response body: " + response);

                } catch (Exception e) {
                    System.err.println("Failed to send to backend: " + e.getMessage());
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            } catch (Exception e) {
                System.err.println("Error in sendToBackend: " + e.getMessage());
            }
        });
    }

    private static String readResponse(HttpURLConnection connection) throws IOException {
        InputStream is = connection.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        return response.toString();
    }

    private static String buildJsonPayload(String report, IssueType type, String issueId,
            String timestamp, DeviceInfo deviceInfo, Throwable ex) {
        JsonObject payload = new JsonObject();

        payload.addProperty("type", type.name());
        payload.addProperty("issueId", issueId);
        payload.addProperty("timestamp", timestamp);
        payload.addProperty("appId", appId);
        payload.addProperty("appVersion", getAppVersion());
        payload.addProperty("deviceId", Analytics.getDeviceId());
        payload.addProperty("deviceManufacturer", deviceInfo.manufacturer);
        payload.addProperty("deviceModel", deviceInfo.model);
        payload.addProperty("deviceType", deviceInfo.deviceType);
        payload.addProperty("isEmulator", deviceInfo.isEmulator);
        payload.addProperty("os", "Android");
        payload.addProperty("osVersion", System.getProperty("os.version", "Unknown"));

        JsonObject reportObj = new JsonObject();
        reportObj.addProperty("message", report);
        reportObj.addProperty("exceptionClass", ex.getClass().getName());
        reportObj.addProperty("exceptionMessage", ex.getMessage());

        JsonArray stackTraceArray = new JsonArray();
        for (StackTraceElement element : ex.getStackTrace()) {
            stackTraceArray.add(element.toString());
        }
        reportObj.add("stackTrace", stackTraceArray);

        payload.add("report", reportObj);

        JsonArray breadcrumbsArray = new JsonArray();
        breadcrumbs.forEach(breadcrumbsArray::add);
        payload.add("breadcrumbs", breadcrumbsArray);

        return gson.toJson(payload);
    }

    // Méthode pour générer un ID stable basé sur l'exception
    private static String generateStableIssueId(Throwable ex) {
        try {

            StringBuilder normalizedTrace = new StringBuilder();
            normalizedTrace.append(ex.getClass().getName()).append(":");

            StackTraceElement[] elements = ex.getStackTrace();
            int maxElements = Math.min(10, elements.length); // Limiter à 10 éléments

            for (int i = 0; i < maxElements; i++) {
                StackTraceElement element = elements[i];
                normalizedTrace.append(element.getClassName())
                        .append(".")
                        .append(element.getMethodName())
                        .append("|");
            }

            if (ex.getMessage() != null) {
                normalizedTrace.append("MSG:").append(ex.getMessage().replaceAll("[0-9]", "#"));
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalizedTrace.toString().getBytes());
            StringBuilder hexString = new StringBuilder();

            for (int i = 0; i < 6 && i < hash.length; i++) {
                String hex = Integer.toHexString(0xff & hash[i]);
                if (hex.length() == 1)
                    hexString.append('0');
                hexString.append(hex);
            }

            return "ERR-" + hexString.toString().toUpperCase();
        } catch (Exception e) {
            return "ERR-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    private static class DeviceInfo {
        String manufacturer;
        String model;
        String deviceType;
        boolean isEmulator;
    }

    private static DeviceInfo detectDevice() {
        DeviceInfo info = new DeviceInfo();
        try {
            Class<?> buildClass = Class.forName("android.os.Build");
            info.manufacturer = (String) buildClass.getField("MANUFACTURER").get(null);
            info.model = (String) buildClass.getField("MODEL").get(null);
            info.deviceType = "physical_device";
            info.isEmulator = info.model.toLowerCase().contains("sdk") ||
                    info.model.contains("Emulator");
        } catch (Exception e) {
            info.manufacturer = "unknown";
            info.model = "unknown";
            info.deviceType = "unknown";
            info.isEmulator = false;
        }
        return info;
    }

    private static String getAppVersion() {
        return "1.0.0";
    }

    private static String buildEnhancedReport(Thread thread, Throwable ex, IssueType type, String loggerName) {
        StringBuilder report = new StringBuilder();
        String border = createBorder("═", 60);
        String smallBorder = createBorder("─", 40);

        String issueId = generateStableIssueId(ex);
        String issueTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));

        report.append("\n").append(border);
        report.append("\n").append(type.getEmoji()).append("  ").append(type.getDisplayName()).append(" REPORT - ")
                .append(issueTime);
        report.append("\n").append("ID: ").append(issueId);
        report.append("\n").append(border).append("\n");

        report.append("\n").append(smallBorder).append("\n");
        report.append("  STACK TRACE:\n");
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        report.append("  ").append(sw.toString().replace("\n", "\n  ")).append("\n");

        report.append("\n").append(border);
        report.append("\n").append(type.getEmoji()).append("  END OF ").append(type.getDisplayName())
                .append(" REPORT  ").append(type.getEmoji());
        report.append("\n").append(border).append("\n");

        return report.toString();
    }

    private static String createBorder(String symbol, int length) {
        StringBuilder border = new StringBuilder();
        for (int i = 0; i < length; i++) {
            border.append(symbol);
        }
        return border.toString();
    }

    private static void logIssue(String report, IssueType type) {
        String[] lines = report.split("\n");
        for (String line : lines) {
            log(type, line);
        }
    }

    private static void log(IssueType type, String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME);
        String logMessage = timestamp + " " + TAG + " [" + type + "] " + message;
        System.out.println(logMessage);
    }

    private static void logError(String message, IssueType type) {
        log(type, message);
    }

    private static void logWarning(String message) {
        log(IssueType.WARNING, message);
    }

    private static void logInfo(String message) {
        log(IssueType.INFO, message);
    }

    private static void logDebug(String message) {
        log(IssueType.DEBUG, message);
    }

    public static void setCrashReporter(CrashReporter reporter) {
        if (reporter != null) {
            crashReporter = reporter;
        }
    }

    public static void setBackendUrl(String url) {
        if (url != null && !url.trim().isEmpty()) {
            backendBaseUrl = url;
            logInfo("Backend URL updated to: " + url);
        }
    }

    public static void enableBackendReporting(boolean enable) {
        sendToBackend = enable;
        logInfo("Backend reporting " + (enable ? "enabled" : "disabled"));
    }

    public static void shutdown() {
        isInitialized = false;
        executor.shutdown();
        logInfo("Crashes SDK shutdown");
    }
}