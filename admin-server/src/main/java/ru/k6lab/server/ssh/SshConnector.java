package ru.k6lab.server.ssh;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.k6lab.server.ssh.SshExceptions.HostKeyMismatch;
import ru.k6lab.server.ssh.SshExceptions.HostKeyUnconfirmed;
import ru.k6lab.server.ssh.SshExceptions.SshFailure;

import java.io.ByteArrayInputStream;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Открывает SSH-соединения (Apache MINA SSHD). Проверка ключа хоста обязательна и не отключается:
 * неизвестный ключ -> {@link HostKeyUnconfirmed} (UI показывает fingerprint и просит подтверждение),
 * несовпадение с подтверждённым -> {@link HostKeyMismatch} (операция прерывается).
 */
@Service
public class SshConnector {

    private static final Logger log = LoggerFactory.getLogger(SshConnector.class);

    private final KnownHostService knownHosts;

    public SshConnector(KnownHostService knownHosts) {
        this.knownHosts = knownHosts;
    }

    public SshConnection open(String host, int port, ResolvedCredential credential, Duration timeout) {
        List<AutoCloseable> closers = new ArrayList<>();
        try {
            String connectHost = host;
            int connectPort = port;
            if (credential.jump() != null && credential.jumpHost() != null) {
                int jumpPort = credential.jumpPort() == null ? 22 : credential.jumpPort();
                ClientSession jump = connectAndAuth(credential.jumpHost(), jumpPort, credential.jumpHost(), jumpPort,
                        credential.jump(), timeout, closers);
                closers.add(() -> jump.close(false).await(3000L));
                SshdSocketAddress bound = jump.startLocalPortForwarding(
                        new SshdSocketAddress("127.0.0.1", 0), new SshdSocketAddress(host, port));
                closers.add(() -> jump.stopLocalPortForwarding(bound));
                connectHost = "127.0.0.1";
                connectPort = bound.getPort();
            }
            ClientSession session = connectAndAuth(connectHost, connectPort, host, port, credential, timeout, closers);
            return new SshConnection(host, credential, session, closers);
        } catch (SshFailure e) {
            closeAll(closers);
            throw e;
        } catch (Exception e) {
            closeAll(closers);
            throw new SshFailure("Не удалось подключиться к " + host + ":" + port + ": " + rootMessage(e), e);
        }
    }

    private ClientSession connectAndAuth(String connectHost, int connectPort, String identityHost, int identityPort,
                                         ResolvedCredential cred, Duration timeout, List<AutoCloseable> closers)
            throws Exception {
        HostKeyVerifier verifier = new HostKeyVerifier(identityHost, identityPort);
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(verifier);
        // По умолчанию MINA пробует ключи из ~/.ssh пользователя ОС, под которым работает админка: это и утечка чужих
        // идентичностей на целевые хосты, и исчерпание MaxAuthTries до проверки пароля. Используем только учётку из БД.
        client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        client.setUserAuthFactories(java.util.List.of(
                org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory.INSTANCE,
                org.apache.sshd.client.auth.password.UserAuthPasswordFactory.INSTANCE));
        client.start();
        closers.add(client::stop);
        try {
            ClientSession session = client.connect(cred.username(), connectHost, connectPort)
                    .verify(timeout).getClientSession();
            closers.add(() -> session.close(false).await(3000L));
            if (cred.authType() == SshCredential.AuthType.KEY) {
                for (KeyPair kp : loadKeys(cred)) {
                    session.addPublicKeyIdentity(kp);
                }
            } else {
                session.addPasswordIdentity(cred.secret());
            }
            session.auth().verify(timeout);
            return session;
        } catch (Exception e) {
            if (verifier.problem.get() != null) {
                throw verifier.problem.get();
            }
            if (e instanceof SshFailure sf) {
                throw sf;
            }
            if (rootMessage(e).toLowerCase().contains("no more authentication methods")
                    || rootMessage(e).toLowerCase().contains("authentication")) {
                throw new SshFailure("Не удалось аутентифицироваться на " + identityHost + " пользователем «"
                        + cred.username() + "» (учётка «" + cred.name() + "»): " + rootMessage(e), e);
            }
            throw e;
        }
    }

    private Iterable<KeyPair> loadKeys(ResolvedCredential cred) {
        try {
            Iterable<KeyPair> keys = SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName(cred.name()),
                    new ByteArrayInputStream(cred.secret().getBytes(StandardCharsets.UTF_8)),
                    (SessionContext s, NamedResource r, int retry) -> cred.passphrase());
            if (keys == null || !keys.iterator().hasNext()) {
                throw new SshFailure("В учётке «" + cred.name() + "» не найден приватный ключ");
            }
            return keys;
        } catch (SshFailure e) {
            throw e;
        } catch (Exception e) {
            throw new SshFailure("Не удалось прочитать приватный ключ учётки «" + cred.name()
                    + "» (проверьте формат и passphrase): " + rootMessage(e), e);
        }
    }

    private static void closeAll(List<AutoCloseable> closers) {
        for (int i = closers.size() - 1; i >= 0; i--) {
            try {
                closers.get(i).close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) {
            r = r.getCause();
        }
        return r.getMessage() != null ? r.getMessage() : r.toString();
    }

    private final class HostKeyVerifier implements ServerKeyVerifier {
        private final String host;
        private final int port;
        final AtomicReference<SshFailure> problem = new AtomicReference<>();

        HostKeyVerifier(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public boolean verifyServerKey(ClientSession clientSession, SocketAddress remoteAddress, PublicKey serverKey) {
            String fingerprint = KeyUtils.getFingerPrint(serverKey);
            String type = KeyUtils.getKeyType(serverKey);
            var known = knownHosts.find(host, port);
            if (known.isPresent()) {
                if (known.get().getFingerprintSha256().equals(fingerprint)) {
                    return true;
                }
                problem.set(new HostKeyMismatch(host, port, known.get().getFingerprintSha256(), fingerprint));
                return false;
            }
            problem.set(new HostKeyUnconfirmed(host, port, type, fingerprint, PublicKeyEntry.toString(serverKey)));
            log.info("Неизвестный ключ хоста {}:{} - {} {}", host, port, type, fingerprint);
            return false;
        }
    }
}
