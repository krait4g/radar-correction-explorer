package io.github.krait4g.radarexplorer.config;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/** Rejects accidental LAN or public exposure before the embedded web server is created. */
public final class LocalOnlyServerGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        String address = applicationContext.getEnvironment().getProperty("server.address", "127.0.0.1");
        requireLoopback(address);
    }

    public static void requireLoopback(String address) {
        if (!isLoopback(address)) {
            throw new IllegalStateException(
                    "Radar Correction Explorer is local-only. server.address must be a loopback address."
            );
        }
    }

    static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String normalized = address.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.equals("localhost")) {
            return true;
        }
        // Hostnames other than localhost are intentionally rejected even if local DNS currently
        // resolves them to loopback. Only an explicit loopback literal is a stable bind contract.
        if (!normalized.matches("[0-9.]+") && !normalized.contains(":")) {
            return false;
        }
        try {
            return InetAddress.getByName(normalized).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}
