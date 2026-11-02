package ru.k6lab.server.ssh;

/** Расшифрованная учётка - живёт только в памяти на время операции. */
public record ResolvedCredential(Long id, String name, String username, SshCredential.AuthType authType,
                                 String secret, String passphrase, String sudoPassword,
                                 String jumpHost, Integer jumpPort, ResolvedCredential jump) {

    public boolean isRoot() {
        return "root".equals(username);
    }

    @Override
    public String toString() {
        return "ResolvedCredential[" + name + "]";
    }
}
