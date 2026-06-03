package com.deploymate.service;

import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes the next pre-release tag name from the most recent existing tag.
 *
 * Rules:
 *   • Last tag ends with "rc" + number (e.g., v1.0.0rc1)   → increment RC counter (v1.0.0rc2)
 *   • Last tag contains "staging" + number (e.g., v1.0.0-staging3) → increment staging counter
 *   • Last tag contains "staging" without a trailing number → append "2"
 *   • Last tag is a clean semver (e.g., v1.0.0)            → bump patch, add "rc1" (v1.0.1rc1)
 *   • Fallback                                              → append "rc1"
 */
@Service
public class TagGeneratorService {

    private static final Pattern RC_PATTERN =
        Pattern.compile("^(.+?)rc(\\d+)$", Pattern.CASE_INSENSITIVE);

    private static final Pattern STAGING_NUM_PATTERN =
        Pattern.compile("^(.+?staging)(\\d+)$", Pattern.CASE_INSENSITIVE);

    private static final Pattern SEMVER_PATTERN =
        Pattern.compile("^(v?)(\\d+)\\.(\\d+)\\.(\\d+)$");

    public String computeNextTag(String lastTagName) {
        if (lastTagName == null || lastTagName.isBlank()) return "v1.0.0rc1";

        // Case 1: ends with "rc" + number
        Matcher rcMatcher = RC_PATTERN.matcher(lastTagName);
        if (rcMatcher.matches()) {
            String base = rcMatcher.group(1);
            int num     = Integer.parseInt(rcMatcher.group(2));
            return base + "rc" + (num + 1);
        }

        // Case 2: ends with "staging" + number
        Matcher stagingNumMatcher = STAGING_NUM_PATTERN.matcher(lastTagName);
        if (stagingNumMatcher.matches()) {
            String base = stagingNumMatcher.group(1);
            int num     = Integer.parseInt(stagingNumMatcher.group(2));
            return base + (num + 1);
        }

        // Case 3: contains "staging" without a trailing number
        if (lastTagName.toLowerCase().contains("staging")) {
            return lastTagName + "2";
        }

        // Case 4: clean semver → bump patch, add rc1
        Matcher semverMatcher = SEMVER_PATTERN.matcher(lastTagName);
        if (semverMatcher.matches()) {
            String prefix = semverMatcher.group(1);
            String major  = semverMatcher.group(2);
            String minor  = semverMatcher.group(3);
            int patch     = Integer.parseInt(semverMatcher.group(4));
            return prefix + major + "." + minor + "." + (patch + 1) + "rc1";
        }

        return lastTagName + "rc1";
    }
}
