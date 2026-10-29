package org.ngoplatform.notification.app;

import org.ngoplatform.notification.domain.Delivery;

/** Provider adapter per channel (e-mail now; push/SMS/WhatsApp adapters plug in here). */
public interface ChannelSender {
    String channel();

    /** Sends or throws; the exception message is stored as the delivery error (no secrets). */
    void send(Delivery d) throws Exception;
}
