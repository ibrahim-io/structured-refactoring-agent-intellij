package com.example;

/**
 * Cross-file caller used by benchmark tasks.
 * - change-sig-001: after 'send' gains 'int priority', the structured agent's
 *   ChangeSignatureProcessor updates this call to send(message, 0) automatically.
 *   A text-edit agent updates Notifier.java but leaves this call broken,
 *   causing a compilation failure.
 */
public class NotificationController {

    private final Notifier notifier = new Notifier();

    public void notify(String message) {
        notifier.send(message);
    }
}
