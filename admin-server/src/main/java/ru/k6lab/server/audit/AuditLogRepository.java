package ru.k6lab.server.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("select a from AuditLog a where lower(concat(coalesce(a.action,''), ' ', coalesce(a.username,''), ' ', coalesce(a.entityType,''), ' ', coalesce(cast(a.entityId as string),''))) like lower(concat('%', :q, '%'))")
    Page<AuditLog> search(@Param("q") String q, Pageable pageable);
}
