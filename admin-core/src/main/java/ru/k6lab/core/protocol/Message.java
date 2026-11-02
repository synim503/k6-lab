package ru.k6lab.core.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Корень иерархии сообщений. Полиморфизм Jackson по полю {@code type}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        // агент -> сервер
        @Type(value = Messages.Hello.class, name = "HELLO"),
        @Type(value = Messages.Heartbeat.class, name = "HEARTBEAT"),
        @Type(value = Messages.Pong.class, name = "PONG"),
        @Type(value = Messages.CmdAck.class, name = "CMD_ACK"),
        @Type(value = Messages.Prepared.class, name = "PREPARED"),
        @Type(value = Messages.PrepareFailed.class, name = "PREPARE_FAILED"),
        @Type(value = Messages.Status.class, name = "STATUS"),
        @Type(value = Messages.LogBatch.class, name = "LOG_BATCH"),
        @Type(value = Messages.K6Status.class, name = "K6_STATUS"),
        @Type(value = Messages.Summary.class, name = "SUMMARY"),
        @Type(value = Messages.Exit.class, name = "EXIT"),
        // сервер -> агент
        @Type(value = Messages.Registered.class, name = "REGISTERED"),
        @Type(value = Messages.Ack.class, name = "ACK"),
        @Type(value = Messages.Ping.class, name = "PING"),
        @Type(value = Messages.Prepare.class, name = "PREPARE"),
        @Type(value = Messages.Start.class, name = "START"),
        @Type(value = Messages.Stop.class, name = "STOP"),
        @Type(value = Messages.Abort.class, name = "ABORT"),
        @Type(value = Messages.Pause.class, name = "PAUSE"),
        @Type(value = Messages.Resume.class, name = "RESUME"),
        @Type(value = Messages.ScaleVus.class, name = "SCALE_VUS"),
        @Type(value = Messages.CancelPrepare.class, name = "CANCEL_PREPARE"),
})
public interface Message {

    /** Версия протокола; меняется только при несовместимых изменениях. */
    int PROTOCOL_VERSION = 1;

    /** Событие агента, которое должно пережить обрыв связи (буфер на диске, идемпотентность по seq). */
    interface Sequenced extends Message {
        /** Монотонный номер события в рамках агента (больше 0). */
        long seq();

        long ts();
    }

    /** Команда сервера, подтверждаемая CMD_ACK и идемпотентная по cmdId. */
    interface Command extends Message {
        String cmdId();
    }
}
