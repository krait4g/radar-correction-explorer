package io.github.krait4g.radarexplorer.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalOnlyServerGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.0.0.2", "localhost", "::1", "[::1]", "0:0:0:0:0:0:0:1"})
    void acceptsExplicitLoopbackAddresses(String address) {
        assertThatCode(() -> LocalOnlyServerGuard.requireLoopback(address)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0.0.0.0", "::", "192.0.2.10", "example.invalid"})
    void rejectsWildcardRemoteAndNonLiteralHosts(String address) {
        assertThatThrownBy(() -> LocalOnlyServerGuard.requireLoopback(address))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local-only");
    }
}
