package ru.k6lab.server.generator;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;


/** Форма добавления генератора (одиночного и массового). */
@Getter
@Setter
public class GeneratorForm {

    @Pattern(regexp = "^[A-Za-z0-9_.-]{1,100}$", message = "Имя: латиница, цифры и _ . - (до 100 символов)")
    private String name;

    private String host;

    @Min(1)
    @Max(65535)
    private int sshPort = 22;

    @NotNull(message = "Выберите SSH-учётку")
    private Long credentialId;

    @NotNull
    private Generator.InstallMode mode = Generator.InstallMode.SYSTEM;

    /** Пусто -> /opt/k6-agent (системный) или ~/k6-agent (пользовательский). */
    private String dir;

    @NotBlank(message = "Укажите URL сервера, доступный с генератора")
    private String serverUrl;

    private boolean tuneOs;

    @Min(value = 1, message = "Не меньше 1")
    private int maxConcurrentRuns = 1;

    private Long agentArtifactId;
    private Long k6ArtifactId;

    /** Только для массового добавления: по хосту в строке, «host[:port] [имя]». */
    private String hosts;
}
