package ru.k6lab.server.ssh;

import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
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

    private ExecResult execWithStdin(String command, String stdin, Duration timeout) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (ChannelExec channel = session.createExecChannel(command)) {
            channel.setOut(out);
            channel.setErr(err);
            if (stdin != null) {
                channel.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
            }
            channel.open().verify(timeout);
            var events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), timeout);
            if (events.contains(ClientChannelEvent.TIMEOUT)) {
                throw new SshFailure("Таймаут (" + timeout.toSeconds() + " с) выполнения команды");
            }
            Integer status = channel.getExitStatus();
            return new ExecResult(status == null ? -1 : status, out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SshFailure("Ошибка выполнения команды: " + e.getMessage(), e);
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
        for (int i = closers.size() - 1; i >= 0; i--) {
            try {
                closers.get(i).close();
            } catch (Exception ignored) {
                // закрытие best-effort
            }
        }
        try {
            session.close(false).await(3000L);
        } catch (Exception ignored) {
            // best-effort
        }
    }

    /** Для тестов. */
    ClientSession session() {
        return session;
    }
}
