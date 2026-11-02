package ru.k6lab.agent.ws;

import ru.k6lab.core.protocol.Message;
import ru.k6lab.core.protocol.Messages;

/** Заглушка для тестов соединения: не выполняет команды. */
public class UnsupportedCommandHandler implements CommandHandler {

    @Override
    public Messages.CmdAck handle(Message.Command command) {
        return new Messages.CmdAck(command.cmdId(), false,
                "Команда " + command.getClass().getSimpleName() + " не поддерживается этой версией агента");
    }
}
