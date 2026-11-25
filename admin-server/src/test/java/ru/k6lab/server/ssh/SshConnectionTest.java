package ru.k6lab.server.ssh;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Тесты {@link SshConnection} против встроенного Apache MINA SshServer:
 * проверка немедленного EOF на stdin, завершения по EXIT_STATUS при открытом stdout потомка и быстрого таймаута с частичным выводом.
 */
class SshConnectionTest {

    private SshServer server;
    private SshConnector connector;
    private ResolvedCredential cred;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        SimpleGeneratorHostKeyProvider keyProvider = new SimpleGeneratorHostKeyProvider();
        keyProvider.setAlgorithm("RSA");
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(keyProvider);
        server.setPasswordAuthenticator((user, pass, session) -> "tester".equals(user) && "secret".equals(pass));
        server.setCommandFactory((channel, command) -> new TestCommand(command));
        server.start();
        port = server.getPort();

        KeyPair serverKey = keyProvider.loadKeys(null).iterator().next();
        String fp = KeyUtils.getFingerPrint(serverKey.getPublic());

        KnownHostService knownHosts = mock(KnownHostService.class);
        KnownHost kh = new KnownHost();
        kh.setHost("127.0.0.1");
        kh.setPort(port);
        kh.setFingerprintSha256(fp);
        when(knownHosts.find(anyString(), anyInt())).thenReturn(Optional.of(kh));

        connector = new SshConnector(knownHosts);
        cred = new ResolvedCredential(1L, "test", "tester", SshCredential.AuthType.PASSWORD,
                "secret", null, "sudopass", null, null, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop(true);
        }
    }

    @Test
    void execClosesStdinImmediatelySoStdinReadingCommandsDoNotHang() {
        long start = System.currentTimeMillis();
        try (SshConnection conn = connector.open("127.0.0.1", port, cred, Duration.ofSeconds(5))) {
            var res = conn.exec("READ_STDIN", Duration.ofSeconds(10));
            long elapsed = System.currentTimeMillis() - start;
            assertThat(res.ok()).isTrue();
            assertThat(res.stdout()).isEqualTo("EOF:[]");
            assertThat(elapsed).isLessThan(4000L);
        }
    }

    @Test
    void sudoPassesPasswordAndClosesStdinImmediately() {
        try (SshConnection conn = connector.open("127.0.0.1", port, cred, Duration.ofSeconds(5))) {
            var res = conn.sudo("READ_STDIN", Duration.ofSeconds(10));
            assertThat(res.ok()).isTrue();
            assertThat(res.stdout()).isEqualTo("EOF:[sudopass\n]");
        }
    }

    @Test
    void execDoesNotHangOnTimeoutWhenBackgroundChildHoldsStdoutOpen() {
        long start = System.currentTimeMillis();
        try (SshConnection conn = connector.open("127.0.0.1", port, cred, Duration.ofSeconds(5))) {
            var res = conn.exec("LEAK_STDOUT", Duration.ofSeconds(10));
            long elapsed = System.currentTimeMillis() - start;
            assertThat(res.ok()).isTrue();
            assertThat(res.stdout()).contains("daemon-started");
            assertThat(elapsed).isLessThan(4000L);
        }
    }

    @Test
    void execTimeoutThrowsCommandTimeoutWithPartialOutputAndClosesFast() {
        long start = System.currentTimeMillis();
        try (SshConnection conn = connector.open("127.0.0.1", port, cred, Duration.ofSeconds(5))) {
            assertThatThrownBy(() -> conn.exec("HANG_WITH_OUTPUT", Duration.ofSeconds(1)))
                    .isInstanceOf(SshExceptions.CommandTimeout.class)
                    .satisfies(ex -> {
                        SshExceptions.CommandTimeout ct = (SshExceptions.CommandTimeout) ex;
                        assertThat(ct.getMessage()).contains("Таймаут (1 с)");
                        assertThat(ct.stdout()).contains("step-1-ok");
                        assertThat(ct.stderr()).contains("still-waiting");
                    });
        }
        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).isLessThan(5000L);
    }

    private static final class TestCommand implements Command {
        private final String cmd;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback exit;
        private final CountDownLatch destroyed = new CountDownLatch(1);
        private Thread worker;

        TestCommand(String cmd) {
            this.cmd = cmd;
        }

        @Override
        public void setInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
            this.err = err;
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.exit = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) {
            worker = new Thread(() -> {
                try {
                    if (cmd.contains("READ_STDIN")) {
                        byte[] all = in.readAllBytes();
                        String s = new String(all, StandardCharsets.UTF_8);
                        out.write(("EOF:[" + s + "]").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        exit.onExit(0);
                    } else if (cmd.contains("LEAK_STDOUT")) {
                        out.write("daemon-started\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        // Отправляем только exit-status без закрытия канала (имитация фонового потомка, держащего stdout)
                        channel.getSession().writePacket(createExitStatusBuffer(channel, 0));
                        destroyed.await(15, TimeUnit.SECONDS);
                    } else if (cmd.contains("HANG_WITH_OUTPUT")) {
                        out.write("step-1-ok\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        err.write("still-waiting\n".getBytes(StandardCharsets.UTF_8));
                        err.flush();
                        destroyed.await(15, TimeUnit.SECONDS);
                    } else {
                        out.write("ok\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        exit.onExit(0);
                    }
                } catch (Exception ignored) {
                    // прервано при закрытии канала
                }
            }, "test-ssh-cmd");
            worker.setDaemon(true);
            worker.start();
        }

        private static org.apache.sshd.common.util.buffer.Buffer createExitStatusBuffer(ChannelSession ch, int status) {
            var buf = ch.getSession().createBuffer(org.apache.sshd.common.SshConstants.SSH_MSG_CHANNEL_REQUEST, 32);
            buf.putUInt(ch.getRecipient());
            buf.putString("exit-status");
            buf.putBoolean(false);
            buf.putUInt(status);
            return buf;
        }

        @Override
        public void destroy(ChannelSession channel) {
            destroyed.countDown();
            if (worker != null) {
                worker.interrupt();
            }
        }
    }
}
