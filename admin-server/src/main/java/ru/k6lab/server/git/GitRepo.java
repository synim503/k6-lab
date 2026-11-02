package ru.k6lab.server.git;

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

@Getter
@Setter
@Entity
@Table(name = "git_repo")
public class GitRepo extends Auditable {

    public enum AuthType { NONE, HTTPS_TOKEN, SSH }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false)
    private AuthType authType = AuthType.NONE;

    /** HTTPS-токен («токен» либо «пользователь:токен»), зашифрован. */
    @Column(name = "token_enc")
    private byte[] tokenEnc;

    @Column(name = "ssh_credential_id")
    private Long sshCredentialId;

    /** Подкаталог репозитория, из которого берутся файлы скрипта. */
    @Column(nullable = false)
    private String subdir = "";
}
