package ru.k6lab.server.ssh;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface KnownHostRepository extends JpaRepository<KnownHost, Long> {

    Optional<KnownHost> findByHostAndPort(String host, int port);
}
