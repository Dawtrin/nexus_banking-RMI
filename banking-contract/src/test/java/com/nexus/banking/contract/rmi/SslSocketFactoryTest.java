package com.nexus.banking.contract.rmi;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.rmi.server.RMIClientSocketFactory;

import static org.assertj.core.api.Assertions.assertThat;

class SslSocketFactoryTest {

    @Test
    void testSerializationRoundTrip() throws Exception {
        SslTimeoutClientSocketFactory original = new SslTimeoutClientSocketFactory(4000, 18000, true);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(original);
        }

        SslTimeoutClientSocketFactory deserialized;
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            deserialized = (SslTimeoutClientSocketFactory) ois.readObject();
        }

        assertThat(deserialized).isEqualTo(original);
        assertThat(deserialized.getConnectTimeoutMs()).isEqualTo(4000);
        assertThat(deserialized.getReadTimeoutMs()).isEqualTo(18000);
        assertThat(deserialized.isTrustSelfSigned()).isTrue();
    }

    @Test
    void testFactoryHelper() {
        RMIClientSocketFactory plain = RmiSocketFactories.clientFactory(false);
        assertThat(plain).isInstanceOf(TimeoutClientSocketFactory.class);

        RMIClientSocketFactory ssl = RmiSocketFactories.clientFactory(true);
        assertThat(ssl).isInstanceOf(SslTimeoutClientSocketFactory.class);
    }

    @Test
    void testEqualityAndHashCode() {
        SslTimeoutClientSocketFactory f1 = new SslTimeoutClientSocketFactory(3000, 10000, false);
        SslTimeoutClientSocketFactory f2 = new SslTimeoutClientSocketFactory(3000, 10000, false);
        SslTimeoutClientSocketFactory f3 = new SslTimeoutClientSocketFactory(3000, 10000, true);

        assertThat(f1).isEqualTo(f2);
        assertThat(f1.hashCode()).isEqualTo(f2.hashCode());
        assertThat(f1).isNotEqualTo(f3);
    }
}
