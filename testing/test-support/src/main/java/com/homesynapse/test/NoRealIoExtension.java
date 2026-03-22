/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.test;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;

/**
 * JUnit 5 extension that fails tests attempting non-localhost network I/O.
 *
 * <p>Overrides {@link ProxySelector#getDefault()} with a guard that throws
 * {@link AssertionError} when a connection to a non-localhost address is
 * attempted via the standard Java networking stack.
 *
 * <p><b>Scope:</b> This is a safety net, not a comprehensive firewall. It catches
 * accidental HTTP calls via {@code java.net.http.HttpClient} and
 * {@code java.net.URL.openConnection()} that use the default proxy selector.
 * It does NOT catch raw {@code Socket} or NIO channel connections. The primary
 * enforcement layer is architectural: all I/O flows through injectable interfaces
 * with in-memory test replacements. The ArchUnit rules (Session 3) provide the
 * compile-time enforcement layer.
 *
 * <p>Tests that intentionally perform real network I/O should annotate the test
 * class or method with {@link RealIo} — this extension checks for that
 * annotation and skips the guard when present.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @ExtendWith(NoRealIoExtension.class)
 * class MyServiceTest {
 *     @Test
 *     void shouldNotCallNetwork() {
 *         // Any non-localhost network call here will throw AssertionError
 *     }
 * }
 * }</pre>
 *
 * @see RealIo
 */
public final class NoRealIoExtension implements BeforeEachCallback, AfterEachCallback {

    /** Creates a new {@code NoRealIoExtension}. */
    public NoRealIoExtension() {}

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(NoRealIoExtension.class);
    private static final String ORIGINAL_SELECTOR_KEY = "originalProxySelector";

    @Override
    public void beforeEach(ExtensionContext context) {
        if (isRealIoAnnotated(context)) {
            return;
        }

        ProxySelector original = ProxySelector.getDefault();
        context.getStore(NAMESPACE).put(ORIGINAL_SELECTOR_KEY, original);
        ProxySelector.setDefault(new GuardProxySelector(original));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        Object stored = context.getStore(NAMESPACE).remove(ORIGINAL_SELECTOR_KEY);
        if (stored instanceof ProxySelector original) {
            ProxySelector.setDefault(original);
        }
    }

    private boolean isRealIoAnnotated(ExtensionContext context) {
        if (context.getRequiredTestMethod().isAnnotationPresent(RealIo.class)) {
            return true;
        }
        return context.getRequiredTestClass().isAnnotationPresent(RealIo.class);
    }

    /**
     * Proxy selector that rejects non-localhost URIs with an AssertionError.
     */
    private static final class GuardProxySelector extends ProxySelector {

        private final ProxySelector delegate;

        GuardProxySelector(ProxySelector delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Proxy> select(URI uri) {
            String host = uri.getHost();
            if (host != null && !isLocalhost(host)) {
                throw new AssertionError(
                        "Non-localhost network I/O detected in test: " + uri
                                + ". Use in-memory test replacements or annotate with @RealIo.");
            }
            return delegate != null ? delegate.select(uri) : List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            if (delegate != null) {
                delegate.connectFailed(uri, sa, ioe);
            }
        }

        private static boolean isLocalhost(String host) {
            return "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host)
                    || "0:0:0:0:0:0:0:1".equals(host);
        }
    }
}
