package com.kalo.sms;

/**
 * Sends one text message.
 *
 * Deliberately the smallest interface that could work, and deliberately not
 * aware of what the message is for. A recovery code, a ride notification and a
 * verification prompt are all the same operation to a provider, and none of the
 * rules around a recovery code — how long it lives, how many guesses it
 * survives, how often one number may ask — has anything to do with which
 * company carries the message.
 *
 * Adding Infobip or Twilio is therefore a new class implementing this and one
 * more branch in {@link SmsConfig}. Nothing in
 * {@code PasswordResetServiceImpl} changes, which is the whole reason the
 * interface sits here rather than inside the auth package.
 *
 * Implementations must assume the message contains a secret. It does: the body
 * carries a one-time code in plain text. So an implementation may log that it
 * sent something, and must not log what it sent unless a developer has
 * explicitly asked for that on their own machine.
 */
public interface SmsSender {

    /**
     * Delivers {@code message} to {@code phone}, in E.164.
     *
     * Called off the request thread, after the surrounding transaction has
     * committed, so an implementation may block on a provider's HTTP call. It
     * must not throw to signal an undelivered message — there is nobody left
     * to tell. A provider failure is logged and swallowed, because the
     * alternative is an exception on a background thread that no caller will
     * ever see.
     */
    void send(String phone, String message);
}
