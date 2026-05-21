package com.example;

/**
 * Notification utility used by benchmark tasks.
 * - change-sig-001 adds parameter 'int priority' to method 'send'
 */
public class Notifier {

    public void send(String message) {
        System.out.println("Notification: " + message);
    }
}
