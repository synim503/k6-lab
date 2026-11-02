package ru.k6lab.server.sut;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SutRepository extends JpaRepository<Sut, Long> {
    List<Sut> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);
}
