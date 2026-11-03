package ru.k6lab.server.generator;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface GeneratorRepository extends JpaRepository<Generator, Long> {

    List<Generator> findAllByOrderByNameAsc();

    Optional<Generator> findByName(String name);

    boolean existsByNameIgnoreCase(String name);

    Optional<Generator> findByAgentTokenHash(String hash);

    Optional<Generator> findByOneTimeTokenHash(String hash);

    long countByStatus(Generator.Status status);

    @Query("select g from Generator g where g.status = ru.k6lab.server.generator.Generator.Status.ONLINE "
            + "and (g.lastHeartbeatAt is null or g.lastHeartbeatAt < :threshold)")
    List<Generator> findStaleOnline(Instant threshold);

    /** Захват генераторов под run: FOR UPDATE SKIP LOCKED - параллельные диспетчеры не захватят одни и те же. */
    @Query(value = "select * from generator where id in (:ids) and status = 'ONLINE' and maintenance = false "
            + "and active_runs < max_concurrent_runs for update skip locked", nativeQuery = true)
    List<Generator> lockAvailable(java.util.Collection<Long> ids);

    /** Точечные обновления из потока heartbeat без загрузки сущности и без конфликтов версий. */
    @Modifying
    @Query("update Generator g set g.lastHeartbeatAt = :ts, g.k6Version = :k6, g.agentVersion = :agent where g.id = :id")
    void touchHeartbeat(Long id, Instant ts, String k6, String agent);

    @Modifying
    @Query("update Generator g set g.clockOffsetMs = :offset where g.id = :id")
    void updateClockOffset(Long id, long offset);

    @Modifying
    @Query("update Generator g set g.lastSeq = :seq where g.id = :id")
    void updateLastSeq(Long id, long seq);
}
