package ru.k6lab.server.ssh;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import ru.k6lab.server.common.Auditable;

/** SSH-учётка. Секреты хранятся только в зашифрованном виде (AES-GCM) и не показываются в UI. */
@Getter
@Setter
@Entity
@Table(name = "ssh_credential")
public class SshCredential extends Auditable {

    public enum AuthType { PASSWORD, KEY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false)
    private AuthType authType;

    @Column(name = "secret_enc")
    private byte[] secretEnc;

    @Column(name = "passphrase_enc")
    private byte[] passphraseEnc;

    @Column(name = "sudo_password_enc")
    private byte[] sudoPasswordEnc;

    @Column(name = "jump_host")
    private String jumpHost;

    @Column(name = "jump_port")
    private Integer jumpPort;

    @Column(name = "jump_credential_id")
    private Long jumpCredentialId;
}
