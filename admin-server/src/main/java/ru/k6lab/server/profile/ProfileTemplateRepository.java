package ru.k6lab.server.profile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProfileTemplateRepository extends JpaRepository<ProfileTemplate, Long> {
    List<ProfileTemplate> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);
}
