package ru.k6lab.agent.ws;

import ru.k6lab.core.protocol.Message;
import ru.k6lab.core.protocol.Messages;

/** Обработчик команд сервера (PREPARE/START/STOP/...). Реализация запуска k6 появляется на этапе 4. */
public interface CommandHandler {

    Messages.CmdAck handle(Message.Command command);
}
