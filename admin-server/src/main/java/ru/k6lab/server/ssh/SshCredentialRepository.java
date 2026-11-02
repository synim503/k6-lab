package ru.k6lab.server.ssh;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SshCredentialRepository extends JpaRepository<SshCredential, Long> {

    List<SshCredential> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByJumpCredentialId(Long id);
}
