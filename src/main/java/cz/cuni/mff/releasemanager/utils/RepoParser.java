package cz.cuni.mff.releasemanager.utils;

import cz.cuni.mff.releasemanager.types.RepoRef;

// turns provider:owner/repo into a reference, reporting bad input once
public final class RepoParser {

    private RepoParser() {}

    public static RepoRef parseOrNull(String value) {
        try {
            return RepoRef.parse(value);
        } catch (IllegalArgumentException e) {
            Output.error(e.getMessage());
            return null;
        }
    }
}
