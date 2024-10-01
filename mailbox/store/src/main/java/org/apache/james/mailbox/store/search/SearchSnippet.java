package org.apache.james.mailbox.store.search;

public record SearchSnippet(String id,
                            String highlightedSubject,
                            String highlightedBody) {
}
