package org.Analytics;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.Duration;
import java.nio.file.Files;
import java.util.TimeZone;
import java.util.Locale;
import java.util.Map;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class Analytics {
    private static Set<String> activeUsers = ConcurrentHashMap.newKeySet();
    private static Map<String, Instant> sessionStartTimes = new HashMap<>();
    private static String appVersion = "1.0.0";
    private static String backendBaseUrl;
    private static String deviceId = null;
    private static String appId = null;
    private static boolean initialized = false;
    private static String currentUserId = null;
    private static String anonymousUserIdPrefix = "anon_";

    static {
        initializeAppVersion();
        initializeDeviceId();
    }

    public static void initialize(String applicationId, boolean autoDetectDevice, boolean trackLocation,
            String backendUrl) {
        if (initialized) {
            System.out.println("Analytics SDK already initialized");
            return;
        }

        backendBaseUrl = backendUrl;

        if (applicationId == null || applicationId.trim().isEmpty()) {
            throw new IllegalArgumentException("Application ID cannot be null or empty");
        }

        if (!applicationId.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
            throw new IllegalArgumentException("Invalid Application ID format. Should be a UUID.");
        }

        appId = applicationId;
        initialized = true;
        System.out.println("Analytics SDK initialized - App ID: " + appId + ", Device ID: " + deviceId);

        JsonObject initData = new JsonObject();
        initData.addProperty("eventType", "sdk_initialized");
        initData.addProperty("appId", appId);
        initData.addProperty("deviceId", deviceId);
        initData.addProperty("appVersion", appVersion);
        initData.addProperty("autoDetectDevice", autoDetectDevice);
        initData.addProperty("trackLocation", trackLocation);
        sendToBackend("", initData);

        if (autoDetectDevice) {
            detectDevice();
        }

        if (trackLocation) {
            trackLocation();
        }
    }

    // Ancienne méthode initializeAppId conservée pour compatibilité

    public static void initializeAppId(String applicationId) {
        if (applicationId == null || applicationId.trim().isEmpty()) {
            throw new IllegalArgumentException("Application ID cannot be null or empty");
        }

        if (!applicationId.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
            throw new IllegalArgumentException("Invalid Application ID format. Should be a UUID.");
        }

        appId = applicationId;
        System.out.println("📱 Application ID initialized: " + appId);

        JsonObject initData = new JsonObject();
        initData.addProperty("eventType", "app_id_initialized");
        initData.addProperty("appId", appId);
        initData.addProperty("deviceId", deviceId);
        sendToBackend("", initData);
    }

    private static void initializeDeviceId() {
        // 1. Essayer de lire un ID déjà persisté
        String persistedId = readPersistedDeviceId();
        if (persistedId != null) {
            deviceId = persistedId;
            System.out.println("📱 Using persisted device ID: " + deviceId);
            return;
        }

        // 2. Essayer d'obtenir l'adresse MAC comme identifiant principal
        try {
            String macAddress = getMacAddress();
            if (macAddress != null && !macAddress.isEmpty()) {
                deviceId = "mac_" + macAddress;
                persistDeviceId(deviceId);
                System.out.println("Using MAC address: " + deviceId);
                return;
            }
        } catch (Exception e) {
            System.out.println("MAC address not available: " + e.getMessage());
        }

        // 3. Essayer d'obtenir l'ID Android pour les appareils Android
        try {
            Class<?> settingsSecure = Class.forName("android.provider.Settings$Secure");
            Class<?> contextClass = Class.forName("android.content.Context");

            Object context = null; // Dans une vraie app Android, ce serait le contexte

            String androidId = (String) settingsSecure.getMethod("getString",
                    contextClass, String.class).invoke(null, context, "android_id");

            if (androidId != null && !androidId.isEmpty() && !"9774d56d682e549c".equals(androidId)) {
                deviceId = "android_" + androidId;
                persistDeviceId(deviceId);
                System.out.println("📱 Using Android ID: " + deviceId);
                return;
            }
        } catch (Exception e) {
            System.out.println("Android ID not available: " + e.getMessage());
        }

        // 4. Essayer d'obtenir le hostname + nom d'utilisateur
        try {
            String hostName = java.net.InetAddress.getLocalHost().getHostName();
            String userName = System.getProperty("user.name");
            if (hostName != null && !hostName.isEmpty()) {
                deviceId = "host_" + userName + "@" + hostName;
                persistDeviceId(deviceId);
                System.out.println("📱 Using host identifier: " + deviceId);
                return;
            }
        } catch (Exception e) {
            System.out.println("Hostname not available: " + e.getMessage());
        }

        // 5. Dernier recours - UUID persistant
        deviceId = "uuid_" + UUID.randomUUID().toString();
        persistDeviceId(deviceId);
        System.out.println("📱 Generated new persistent device ID: " + deviceId);
    }

    private static String readPersistedDeviceId() {
        try {
            // Essayer plusieurs emplacements possibles pour le fichier
            Path[] possiblePaths = {
                    Paths.get("analytics_device_id"),
                    Paths.get(System.getProperty("user.home"), "analytics_device_id"),
                    Paths.get(System.getProperty("java.io.tmpdir"), "analytics_device_id")
            };

            for (Path path : possiblePaths) {
                if (Files.exists(path)) {
                    List<String> lines = Files.readAllLines(path);
                    if (!lines.isEmpty()) {
                        return lines.get(0);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error reading persisted device ID: " + e.getMessage());
        }
        return null;
    }

    private static void persistDeviceId(String id) {
        try {
            // Essayer plusieurs emplacements possibles pour le fichier
            Path[] possiblePaths = {
                    Paths.get("analytics_device_id"),
                    Paths.get(System.getProperty("user.home"), "analytics_device_id"),
                    Paths.get(System.getProperty("java.io.tmpdir"), "analytics_device_id")
            };

            for (Path path : possiblePaths) {
                try {
                    Files.write(path, id.getBytes(StandardCharsets.UTF_8));
                    System.out.println("Device ID persisted to: " + path.toAbsolutePath());
                    return;
                } catch (Exception e) {
                    // Continuer à essayer le prochain emplacement
                }
            }
        } catch (Exception e) {
            System.err.println("Could not persist device ID: " + e.getMessage());
        }
    }

    private static String getMacAddress() throws Exception {
        Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
        while (networkInterfaces.hasMoreElements()) {
            NetworkInterface ni = networkInterfaces.nextElement();
            byte[] hardwareAddress = ni.getHardwareAddress();
            if (hardwareAddress != null && hardwareAddress.length > 0 && !ni.isLoopback()) {
                StringBuilder sb = new StringBuilder();
                for (byte b : hardwareAddress) {
                    sb.append(String.format("%02X", b));
                }
                return sb.toString();
            }
        }
        return null;
    }

    private static void initializeAppVersion() {
        try {
            String[] pathsToCheck = {
                    "app/build.gradle",
                    "../app/build.gradle",
                    "build.gradle"
            };

            for (String path : pathsToCheck) {
                Path fullPath = Paths.get(path).toAbsolutePath();
                if (Files.exists(fullPath)) {
                    String version = fetchRealAppVersion(fullPath.toString());
                    if (version != null && !version.isEmpty()) {
                        appVersion = version;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error initializing app version: " + e.getMessage());
        } finally {
            System.out.println("App version: " + appVersion);
            trackAppStart();
        }
    }

    private static String fetchRealAppVersion(String buildGradlePath) {
        try {
            List<String> lines = Files.readAllLines(Paths.get(buildGradlePath));
            for (String line : lines) {
                if (line.trim().startsWith("versionName")) {
                    String[] parts = line.split("\"");
                    if (parts.length >= 2) {
                        return parts[1];
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error reading build.gradle: " + e.getMessage());
        }
        return null;
    }

    private static void trackAppStart() {
        JsonObject eventData = new JsonObject();
        eventData.addProperty("eventType", "app_start");
        eventData.addProperty("appVersion", appVersion);
        eventData.addProperty("deviceId", deviceId);
        if (appId != null) {
            eventData.addProperty("appId", appId);
        }
        sendToBackend("", eventData);
    }

    public static void detectDevice() {
        try {
            Class<?> buildClass = Class.forName("android.os.Build");

            String manufacturer = (String) buildClass.getField("MANUFACTURER").get(null);
            String model = (String) buildClass.getField("MODEL").get(null);
            String brand = (String) buildClass.getField("BRAND").get(null);
            String product = (String) buildClass.getField("PRODUCT").get(null);

            manufacturer = capitalizeFirstLetter(manufacturer);

            boolean isEmulator = model.toLowerCase().contains("sdk") ||
                    model.contains("Emulator") ||
                    product.contains("emulator") ||
                    brand.equalsIgnoreCase("generic");

            String deviceType = isEmulator ? "emulator" : "physical_device";
            String deviceInfo = manufacturer + " " + model;

            JsonObject deviceData = new JsonObject();
            JsonObject data = new JsonObject();
            data.addProperty("deviceType", deviceType);
            data.addProperty("deviceInfo", deviceInfo);
            data.addProperty("manufacturer", manufacturer);
            data.addProperty("model", model);
            data.addProperty("isEmulator", isEmulator);
            data.addProperty("deviceId", deviceId);
            if (appId != null) {
                data.addProperty("appId", appId);
            }

            deviceData.addProperty("eventType", "device_info");
            deviceData.add("data", data);

            sendToBackend("/device", deviceData);

        } catch (ClassNotFoundException e) {
            trackSystemInfo();
        } catch (Exception e) {
            System.err.println("Device detection error: " + e.getMessage());
        }
    }

    private static void trackSystemInfo() {
        JsonObject systemData = new JsonObject();
        JsonObject data = new JsonObject();
        data.addProperty("osName", System.getProperty("os.name"));
        data.addProperty("deviceId", deviceId);
        if (appId != null) {
            data.addProperty("appId", appId);
        }

        systemData.addProperty("eventType", "system_info");
        systemData.add("data", data);

        sendToBackend("", systemData);
    }

    public static void setCurrentUser(String userId) {
        currentUserId = userId;
    }

    public synchronized static void userLoggedIn() {
        // Générer un ID anonyme si currentUserId est null
        if (currentUserId == null || currentUserId.trim().isEmpty()) {
            currentUserId = anonymousUserIdPrefix + UUID.randomUUID().toString();
            System.out.println("Generated anonymous user ID: " + currentUserId);
        }

        activeUsers.add(currentUserId);
        sessionStartTimes.put(currentUserId, Instant.now());

        JsonObject eventData = new JsonObject();
        JsonObject data = new JsonObject();
        data.addProperty("userId", currentUserId);
        data.addProperty("activeUsers", activeUsers.size());
        data.addProperty("deviceId", deviceId);
        if (appId != null) {
            data.addProperty("appId", appId);
        }

        eventData.addProperty("eventType", "user_login");
        eventData.add("data", data);

        sendToBackend("", eventData);
        trackLocation();
    }

    // Modifier la méthode userLoggedOut() pour gérer les utilisateurs anonymes
    public synchronized static void userLoggedOut() {
        if (currentUserId == null || currentUserId.trim().isEmpty()) {
            System.err.println("No current user set. Nothing to logout.");
            return;
        }

        activeUsers.remove(currentUserId);

        JsonObject eventData = new JsonObject();
        JsonObject data = new JsonObject();
        data.addProperty("userId", currentUserId);
        data.addProperty("activeUsers", activeUsers.size());
        data.addProperty("deviceId", deviceId);
        if (appId != null) {
            data.addProperty("appId", appId);
        }

        eventData.addProperty("eventType", "user_logout");
        eventData.add("data", data);

        sendToBackend("", eventData);
        trackSessionDuration(currentUserId);

        // Ne pas réinitialiser currentUserId s'il s'agit d'un utilisateur anonyme
        if (!currentUserId.startsWith(anonymousUserIdPrefix)) {
            currentUserId = null;
        }
    }

    private static void trackSessionDuration(String userId) {
        Instant startTime = sessionStartTimes.get(userId);
        if (startTime == null)
            return;

        Instant endTime = Instant.now();
        Duration duration = Duration.between(startTime, endTime);

        JsonObject sessionData = new JsonObject();
        JsonObject data = new JsonObject();
        data.addProperty("userId", userId);
        data.addProperty("startTime", startTime.toString());
        data.addProperty("endTime", endTime.toString());
        data.addProperty("durationSeconds", duration.getSeconds());
        data.addProperty("deviceId", deviceId);
        if (appId != null) {
            data.addProperty("appId", appId);
        }

        sessionData.addProperty("eventType", "session_duration");
        sessionData.add("data", data);

        sendToBackend("/session", sessionData);
        sessionStartTimes.remove(userId);
    }

    private static void trackLocation() {
        new Thread(() -> {
            String location = getIPBasedLocation();
            if (location == null) {
                location = getTimezoneBasedLocation();
            }

            JsonObject locationData = new JsonObject();
            JsonObject data = new JsonObject();
            data.addProperty("location", location);
            data.addProperty("deviceId", deviceId);
            if (appId != null) {
                data.addProperty("appId", appId);
            }

            locationData.addProperty("eventType", "location_info");
            locationData.add("data", data);

            sendToBackend("", locationData);
        }).start();
    }

    private static String getIPBasedLocation() {
        try {
            URL url = new URL("https://ipapi.co/json");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }

                JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
                String city = json.get("city").getAsString();
                String country = json.get("country_name").getAsString();
                return city + ", " + country;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String getTimezoneBasedLocation() {
        TimeZone tz = TimeZone.getDefault();
        Locale locale = Locale.getDefault();
        return locale.getDisplayCountry() + " (" + tz.getID() + ")";
    }

    private static String capitalizeFirstLetter(String str) {
        if (str == null || str.isEmpty())
            return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }

    private static void sendToBackend(String endpoint, JsonObject data) {
        new Thread(() -> {
            if (!data.has("deviceId")) {
                data.addProperty("deviceId", deviceId);
            }
            if (appId != null && !data.has("appId")) {
                data.addProperty("appId", appId);
            }

            System.out.println("Sending data for device: " + deviceId + ", app: " + (appId != null ? appId : "N/A"));
            System.out.println("Payload: " + data.toString());

            HttpURLConnection conn = null;
            try {
                URL url = new URL(backendBaseUrl + "/analytics" + endpoint);
                conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("x-app-version", appVersion);
                conn.setRequestProperty("User-Agent", "AnalyticsSDK/1.0");
                conn.setRequestProperty("x-device-id", deviceId);
                if (appId != null) {
                    conn.setRequestProperty("x-app-id", appId);
                }

                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);
                conn.setUseCaches(false);

                String json = data.toString();
                System.out.println("Sending JSON: " + json);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = json.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                    os.flush();
                }

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    System.out.println("Response OK: " + readStream(conn.getInputStream()));
                } else {
                    System.err.println("Server error: " + code + " - " + readStream(conn.getErrorStream()));
                }
            } catch (Exception e) {
                System.err.println("Network error: " + e.getClass().getSimpleName());
                e.printStackTrace();
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }).start();
    }

    private static String readStream(InputStream inputStream) throws IOException {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            return response.toString();
        }
    }

    public static String getDeviceId() {
        return deviceId;
    }

    public static String getAppId() {
        return appId;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static String getCurrentUserId() {
        return currentUserId;
    }

    public static String getAppVersion() {
        return appVersion;
    }
}
