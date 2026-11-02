package ru.k6lab.server.profile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestProfileRepository extends JpaRepository<TestProfile, Long> {
    List<TestProfile> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByTemplateId(Long templateId);
}
