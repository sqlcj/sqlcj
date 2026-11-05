package com.example.newsletter;

import com.example.newsletter.repositories.NewsletterRepository;
import com.example.newsletter.rows.SubscribersRow;

/**
 * An upsert: one sign-up form that both creates a subscriber and renames the
 * one an address already names, in one statement.
 */
public final class SubscriberSignup {

    /**
     * Signs {@code email} up, or renames the subscriber it already names, and
     * returns the stored subscriber either way.
     */
    public SubscribersRow subscribe(NewsletterRepository newsletter, String email, String name) {
        return newsletter.upsertSubscriber(email, name);
    }
}
