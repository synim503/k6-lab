package ru.k6lab.server.git;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GitRepoRepository extends JpaRepository<GitRepo, Long> {

    List<GitRepo> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);
}
