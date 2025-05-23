package org.Analytics;

import java.util.Map;
import org.Analytics.BuildConfig;
public class DevStream {
    private static boolean isInitialized = false;
    private static String currentAppId;
    private static String backendBaseUrl = BuildConfig.BACKEND_BASE_URL;

    public static void configureBackendUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("Backend URL cannot be null or empty");
        }
        backendBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public static void start(String appId) {
        start(appId, true, true, true);
    }

    public static void start(String appId, boolean enableAnalytics, boolean enableCrashes, boolean enableEvents) {
        if (isInitialized) {
            System.out.println("DevStream SDK already initialized");
            return;
        }

        if (appId == null || appId.trim().isEmpty()) {
            throw new IllegalArgumentException("App ID cannot be null or empty");
        }

        if (!appId.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
            throw new IllegalArgumentException("Invalid Application ID format. Should be a UUID.");
        }

        currentAppId = appId;

        if (enableAnalytics) {
            Analytics.initialize(appId, true, true, backendBaseUrl);
        }

        if (enableCrashes) {
            Crashes.initialize(appId, Crashes.LogLevel.DEBUG, backendBaseUrl, true);
        }

        if (enableEvents) {
            EventTracker.setDebugMode(true);
            EventTracker.configureBackendUrl(backendBaseUrl);
        }

        isInitialized = true;
        System.out.println("DevStream SDK initialized successfully with appId: " + appId);
    }

    public static void trackEvent(String eventName) {
        checkInitialization();
        EventTracker.trackEvent(eventName, currentAppId, null);
    }

    public static void trackEvent(String eventName, Map<String, String> properties) {
        checkInitialization();
        EventTracker.trackEvent(eventName, currentAppId, properties);
    }

    public static void userLoggedIn() {
        checkInitialization();
        Analytics.userLoggedIn();
    }

    public static void userLoggedOut() {
        checkInitialization();
        Analytics.userLoggedOut();
    }

    public static void trackError(Throwable ex) {
        checkInitialization();
        Crashes.trackError(ex);
    }

    public static void trackError(Throwable ex, String context) {
        checkInitialization();
        Crashes.trackError(ex, context);
    }

    public static void trackError(Throwable ex, String context, Map<String, String> properties) {
        checkInitialization();
        Crashes.trackError(ex, context, properties);
    }

    public static void addBreadcrumb(String event) {
        checkInitialization();
        Crashes.addBreadcrumb(event);
    }

    public static boolean isInitialized() {
        return isInitialized;
    }

    private static void checkInitialization() {
        if (!isInitialized) {
            throw new IllegalStateException("DevStream SDK not initialized. Call DevStream.start() first.");
        }
    }
}