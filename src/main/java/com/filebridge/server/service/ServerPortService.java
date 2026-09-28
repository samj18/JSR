package com.filebridge.server.service;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Changes the HTTP server port at runtime by fully destroying the old connector
 * and creating a fresh one on the requested port.
 *
 * <p>The naive stop() then start() approach doesn't work because Tomcat's
 * previous connector may still hold the old port during the switch, and Spring
 * Boot then re-adds it — causing an "address already in use" on the old port
 * or leaving both connectors bound. Instead we:
 * <ol>
 *   <li>Get direct access to the underlying Tomcat instance</li>
 *   <li>Remove and destroy every existing connector</li>
 *   <li>Add a new HTTP connector on the new port</li>
 * </ol>
 */
@Service
public class ServerPortService {

    private static final Logger log = LoggerFactory.getLogger(ServerPortService.class);

    private final ApplicationContext appContext;
    private final SettingsService settingsService;

    public ServerPortService(ApplicationContext appContext, SettingsService settingsService) {
        this.appContext = appContext;
        this.settingsService = settingsService;
    }

    public int currentPort() {
        WebServer ws = getWebServer();
        return ws == null ? -1 : ws.getPort();
    }

    /** True if {@code port} is free right now. Note: pre-check only. */
    public boolean isPortAvailable(int port) {
        if (port < 1 || port > 65535) return false;
        if (port == currentPort()) return true;
        try (ServerSocket s = new ServerSocket(port)) {
            s.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Change the port at runtime. On success returns the new port and persists it.
     * On failure returns -1 and does its best to leave the server running on the old port.
     */
    public synchronized int changePort(int newPort) {
        if (newPort < 1 || newPort > 65535) return -1;

        WebServer ws = getWebServer();
        if (!(ws instanceof TomcatWebServer tomcatWs)) {
            log.error("Web server is not a TomcatWebServer - cannot change port at runtime");
            return -1;
        }

        int oldPort = tomcatWs.getPort();
        if (newPort == oldPort) {
            settingsService.saveServerPort(newPort);
            return newPort;
        }

        Tomcat tomcat = tomcatWs.getTomcat();
        Connector oldConnector = tomcat.getConnector();

        try {
            log.info("Rebinding server from port {} to {}", oldPort, newPort);

            // 1) Remove and DESTROY every existing connector so its socket is released now.
            for (Connector c : tomcat.getService().findConnectors()) {
                tomcat.getService().removeConnector(c);
                try {
                    c.stop();
                } catch (LifecycleException ignored) {}
                try {
                    c.destroy();
                } catch (LifecycleException ignored) {}
            }

            // 2) Small pause: on Windows the OS may hold the TIME_WAIT briefly.
            //    Retry the bind a few times if needed.
            Connector newConnector = buildConnector(oldConnector, newPort);

            IOException lastError = null;
            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    tomcat.getService().addConnector(newConnector);
                    tomcat.setConnector(newConnector);
                    // If we got here, the connector started successfully.
                    log.info("Server now running on port {}", newPort);
                    settingsService.saveServerPort(newPort);
                    return newPort;
                } catch (Exception e) {
                    // Remove the failed connector before retrying so we don't leak.
                    try {
                        tomcat.getService().removeConnector(newConnector);
                        newConnector.destroy();
                    } catch (Exception ignored) {}

                    Throwable root = rootCause(e);
                    if (root instanceof java.net.BindException) {
                        lastError = (java.net.BindException) root;
                        Thread.sleep(200);
                        newConnector = buildConnector(oldConnector, newPort); // fresh instance
                    } else {
                        throw e;
                    }
                }
            }
            throw lastError != null ? lastError : new IOException("Could not bind to port " + newPort);

        } catch (Exception e) {
            log.error("Failed to rebind to port {}: {}", newPort, e.getMessage());
            // Best-effort recovery: put the OLD connector back so the app keeps working.
            try {
                Connector recovery = buildConnector(oldConnector, oldPort);
                tomcat.getService().addConnector(recovery);
                tomcat.setConnector(recovery);
                log.info("Recovered on original port {}", oldPort);
            } catch (Exception recover) {
                log.error("Recovery to port {} also failed: {}", oldPort, recover.getMessage());
            }
            return -1;
        }
    }

    /** Build a fresh connector cloning key attributes from an existing one. */
    private Connector buildConnector(Connector template, int port) {
        Connector c = new Connector(template.getProtocol());
        c.setPort(port);
        c.setScheme(template.getScheme());
        c.setSecure(template.getSecure());
        c.setURIEncoding(template.getURIEncoding());
        c.setThrowOnFailure(true);
        return c;
    }

    private Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur;
    }

    private WebServer getWebServer() {
        if (appContext instanceof WebServerApplicationContext wsac) {
            return wsac.getWebServer();
        }
        return null;
    }
}
