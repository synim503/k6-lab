package ru.k6lab.server.script;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import ru.k6lab.server.common.Auditable;

/** Скрипт - контейнер версий. Нагрузочная модель привязывается к скрипту, а не к версии. */
@Getter
@Setter
@Entity
@Table(name = "script")
public class Script extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    /** Для скриптов из Git: репозиторий, ветка и entrypoint, по которым делается «последняя версия ветки». */
    @Column(name = "git_repo_id")
    private Long gitRepoId;

    @Column(name = "git_branch")
    private String gitBranch;

    @Column(name = "git_entrypoint")
    private String gitEntrypoint;
}
