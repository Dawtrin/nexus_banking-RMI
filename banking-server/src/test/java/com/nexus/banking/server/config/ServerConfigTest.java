package com.nexus.banking.server.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerConfigTest {
    private static final String SECRET = "0123456789abcdef-secret";

    private static Map<String, String> base() {
        Map<String, String> m = new HashMap<>();
        m.put("database.enabled", "false");
        m.put("server.replication.secret", SECRET);
        return m;
    }

    private static ServerConfig of(Map<String, String> cli, Map<String, String> env, Properties file) {
        return new ServerConfig(cli, Map.of(), env, file);
    }

    @Test
    void databaseEnabledMustBeExplicit() {
        Map<String, String> m = base();
        m.remove("database.enabled");
        assertThatThrownBy(() -> of(m, Map.of(), null)).isInstanceOf(ConfigException.class).hasMessageContaining("database.enabled");
    }

    @Test
    void replicationSecretIsMandatoryAndLongEnough() {
        Map<String, String> m = base();
        m.put("server.replication.secret", "short");
        assertThatThrownBy(() -> of(m, Map.of(), null)).isInstanceOf(ConfigException.class).hasMessageContaining("replication.secret");
        m.remove("server.replication.secret");
        assertThatThrownBy(() -> of(m, Map.of(), null)).isInstanceOf(ConfigException.class);
    }

    @Test
    void adminTokenHasNoDefaultAndIsLengthChecked() {
        assertThat(of(base(), Map.of(), null).getAdminToken()).isNull();
        Map<String, String> m = base();
        m.put("server.admin.token", "tiny");
        assertThatThrownBy(() -> of(m, Map.of(), null)).isInstanceOf(ConfigException.class).hasMessageContaining("admin.token");
    }

    @Test
    void commandLineBeatsEnvironmentBeatsFile() {
        Properties file = new Properties();
        file.setProperty("server.id", "from-file");
        file.setProperty("server.peer.id", "peer-file");
        assertThat(of(base(), Map.of(), file).getServerId()).isEqualTo("from-file");
        assertThat(of(base(), Map.of("NEXUS_SERVER_ID", "from-env"), file).getServerId()).isEqualTo("from-env");
        Map<String, String> cli = base();
        cli.put("server.id", "from-cli");
        assertThat(of(cli, Map.of("NEXUS_SERVER_ID", "from-env"), file).getServerId()).isEqualTo("from-cli");
    }

    @Test
    void fileValuesMayReferenceEnvironmentVariables() {
        Properties file = new Properties();
        file.setProperty("database.password", "${DB_SECRET}");
        ServerConfig c = of(base(), Map.of("DB_SECRET", "s3cret"), file);
        assertThat(c.getDatabaseConfig().getPassword()).isEqualTo("s3cret");
        // an unset variable means "not configured": the default applies, nothing is silently invented
        assertThat(of(base(), Map.of(), file).getDatabaseConfig().getPassword()).isEmpty();
        Properties secretFile = new Properties();
        secretFile.setProperty("server.replication.secret", "${NEXUS_REPLICATION_SECRET}");
        Map<String, String> noSecret = new HashMap<>();
        noSecret.put("database.enabled", "false");
        assertThatThrownBy(() -> of(noSecret, Map.of(), secretFile)).isInstanceOf(ConfigException.class).hasMessageContaining("replication.secret");
    }

    @Test
    void loopbackAdvertisedHostWithRemotePeerIsRejected() {
        Map<String, String> m = base();
        m.put("server.peer.host", "192.168.1.20");
        assertThatThrownBy(() -> of(m, Map.of(), null)).isInstanceOf(ConfigException.class).hasMessageContaining("advertised");
        m.put("server.advertised.host", "192.168.1.10");
        assertThat(of(m, Map.of(), null).getAdvertisedHost()).isEqualTo("192.168.1.10");
    }

    @Test
    void sameServerAndPeerIdAreRejected() {
        Map<String, String> m = base();
        m.put("server.id", "x");
        m.put("server.peer.id", "x");
        assertThatThrownBy(() -> of(m, Map.of(), null)).isInstanceOf(ConfigException.class);
    }

    @Test
    void invalidPortIsRejected() {
        Map<String, String> m = base();
        m.put("server.rmi.registry.port", "70000");
        assertThatThrownBy(() -> of(m, Map.of(), null)).isInstanceOf(ConfigException.class).hasMessageContaining("registry.port");
    }
}
