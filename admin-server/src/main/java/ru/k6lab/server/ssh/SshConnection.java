package ru.k6lab.server.ssh;

import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import ru.k6lab.server.ssh.SshExceptions.CommandTimeout;
import ru.k6lab.server.ssh.SshExceptions.SshFailure;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Соединение с хостом (при необходимости через jump-host). Только установка/обновление/перезапуск/удаление
 * агента; управление тестами идёт через WebSocket агента, не через SSH.
 */
public class SshConnection implements AutoCloseable {

    public record ExecResult(int exitCode, String stdout, String stderr) {
        public boolean ok() {
            return exitCode == 0;
        }

        public String stdoutTrim() {
            return stdout.trim();
        }
    }

    private final ResolvedCredential credential;
    private final ClientSession session;
    private final List<AutoCloseable> closers;
    private final String host;

    SshConnection(String host, ResolvedCredential credential, ClientSession session, List<AutoCloseable> closers) {
        this.host = host;
        this.credential = credential;
        this.session = session;
        this.closers = new ArrayList<>(closers);
    }

    public String host() {
        return host;
    }

    public ResolvedCredential credential() {
        return credential;
    }

    public ExecResult exec(String command, Duration timeout) {
        return execWithStdin(command, null, timeout);
    }

    /**
     * Выполняет команду с правами root. Пароль sudo передаётся ТОЛЬКО через stdin ({@code sudo -S}), никогда в
     * командной строке. Если логин root - команда выполняется как есть.
     */
    public ExecResult sudo(String command, Duration timeout) {
        if (credential.isRoot()) {
            return exec("sh -c " + quote(command), timeout);
        }
        if (credential.sudoPassword() == null || credential.sudoPassword().isEmpty()) {
            return exec("sudo -n -- sh -c " + quote(command), timeout);
        }
        return execWithStdin("sudo -S -p '' -- sh -c " + quote(command), credential.sudoPassword() + "\n", timeout);
    }

    private static final int MAX_CAPTURE_BYTES = 512 * 1024;
    private static final Duration EXIT_DRAIN_GRACE = Duration.ofMillis(500);

    private ExecResult execWithStdin(String command, String stdin, Duration timeout) {
        CappedOutputStream out = new CappedOutputStream(MAX_CAPTURE_BYTES);
        CappedOutputStream err = new CappedOutputStream(MAX_CAPTURE_BYTES);
        try (ChannelExec channel = session.createExecChannel(command)) {
            channel.setOut(out);
            channel.setErr(err);
            // Всегда передаём конечный InputStream (пустой при stdin == null), чтобы MINA SSHD сразу отправила EOF
            // на удалённый stdin — иначе команды, читающие stdin (sudo, docker prune без -f, read и т.п.), висят до таймаута.
            byte[] stdinBytes = stdin == null ? new byte[0] : stdin.getBytes(StandardCharsets.UTF_8);
            channel.setIn(new ByteArrayInputStream(stdinBytes));
            channel.open().verify(timeout);
            var waitMask = EnumSet.of(
                    ClientChannelEvent.CLOSED,
                    ClientChannelEvent.EXIT_STATUS,
                    ClientChannelEvent.EXIT_SIGNAL);
            var events = channel.waitFor(waitMask, timeout);
            if (events.contains(ClientChannelEvent.TIMEOUT)) {
                channel.close(true);
                throw new CommandTimeout(
                        "Таймаут (" + timeout.toSeconds() + " с) выполнения команды",
                        out.asUtf8(), err.asUtf8());
            }
            if (!events.contains(ClientChannelEvent.CLOSED)) {
                // Команда уже вернула EXIT_STATUS/EXIT_SIGNAL: даём короткую паузу на дочитывание хвоста stdout/stderr,
                // после чего принудительно закрываем канал, чтобы фоновые потомки с открытыми fd не вешали выполнение.
                var after = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), EXIT_DRAIN_GRACE);
                if (!after.contains(ClientChannelEvent.CLOSED)) {
                    channel.close(true);
                }
            }
            Integer status = channel.getExitStatus();
            return new ExecResult(status == null ? -1 : status, out.asUtf8(), err.asUtf8());
        } catch (SshFailure e) {
            throw e;
        } catch (IOException e) {
            throw new SshFailure("Ошибка выполнения команды: " + e.getMessage(), e);
        }
    }

    /** Ограниченный по размеру буфер вывода, чтобы бесконечный stdout/stderr не переполнил память. */
    private static final class CappedOutputStream extends OutputStream {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final int limit;
        private boolean truncated;

        CappedOutputStream(int limit) {
            this.limit = limit;
        }

        @Override
        public synchronized void write(int b) {
            if (buf.size() < limit) {
                buf.write(b);
            } else {
                truncated = true;
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            if (len <= 0) {
                return;
            }
            int avail = limit - buf.size();
            if (avail > 0) {
                int take = Math.min(avail, len);
                buf.write(b, off, take);
                if (take < len) {
                    truncated = true;
                }
            } else {
                truncated = true;
            }
        }

        synchronized String asUtf8() {
            String text = buf.toString(StandardCharsets.UTF_8);
            return truncated ? text + "\n… (вывод обрезан по лимиту 512 КБ)" : text;
        }
    }

    public void upload(InputStream content, String remotePath) {
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
             OutputStream out = sftp.write(remotePath)) {
            content.transferTo(out);
        } catch (IOException e) {
            throw new SshFailure("Ошибка загрузки файла " + remotePath + ": " + e.getMessage(), e);
        }
    }

    public void uploadBytes(byte[] content, String remotePath) {
        upload(new ByteArrayInputStream(content), remotePath);
    }

    public void uploadText(String content, String remotePath) {
        uploadBytes(content.getBytes(StandardCharsets.UTF_8), remotePath);
    }

    public static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    @Override
    public void close() {
        try {
            session.close(true).await(1500L);
        } catch (Exception ignored) {
            // best-effort
        }
        for (int i = closers.size() - 1; i >= 0; i--) {
            try {
                closers.get(i).close();
            } catch (Exception ignored) {
                // закрытие best-effort
            }
        }
    }

    /** Для тестов. */
    ClientSession session() {
        return session;
    }
}
