package ru.k6lab.server.ssh;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "known_host")
public class KnownHost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private int port;

    @Column(name = "key_type", nullable = false)
    private String keyType;

    @Column(name = "fingerprint_sha256", nullable = false)
    private String fingerprintSha256;

    @Column(name = "public_key", nullable = false)
    private String publicKey;

    @Column(name = "confirmed_by")
    private String confirmedBy;

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt = Instant.now();
}
