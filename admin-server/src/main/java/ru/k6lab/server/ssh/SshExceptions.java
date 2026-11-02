package ru.k6lab.server.ssh;

/** Исключения SSH-слоя. */
public final class SshExceptions {

    private SshExceptions() {
    }

    /** Общая ошибка SSH с понятным сообщением. */
    public static class SshFailure extends RuntimeException {
        public SshFailure(String message) {
            super(message);
        }

        public SshFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Ключ хоста ещё не подтверждён пользователем: нужно показать fingerprint и запросить подтверждение. */
    public static class HostKeyUnconfirmed extends SshFailure {
        private final String host;
        private final int port;
        private final String keyType;
        private final String fingerprint;
        private final String publicKey;

        public HostKeyUnconfirmed(String host, int port, String keyType, String fingerprint, String publicKey) {
            super("Ключ хоста " + host + ":" + port + " не подтверждён (" + keyType + " " + fingerprint + ")");
            this.host = host;
            this.port = port;
            this.keyType = keyType;
            this.fingerprint = fingerprint;
            this.publicKey = publicKey;
        }

        public String host() {
            return host;
        }

        public int port() {
            return port;
        }

        public String keyType() {
            return keyType;
        }

        public String fingerprint() {
            return fingerprint;
        }

        public String publicKey() {
            return publicKey;
        }
    }

    /** Ключ хоста изменился относительно подтверждённого: возможна подмена, операция прерывается. */
    public static class HostKeyMismatch extends SshFailure {
        public HostKeyMismatch(String host, int port, String expected, String actual) {
            super("ВНИМАНИЕ: ключ хоста " + host + ":" + port + " не совпадает с подтверждённым (ожидали " + expected
                    + ", получили " + actual + "). Установка прервана. Если ключ сменили намеренно, удалите "
                    + "запись о хосте и подтвердите новый ключ.");
        }
    }
}
