package com.wolfiy.twitchhud.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LinkUtils {
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)(?:https?://)?"
                    + "(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+"
                    + "[a-z]{2,63}"
                    + "(?::\\d{1,5})?"
                    + "(?:[/?#][^\\s<>\"']*)?"
    );

    private LinkUtils() {
    }

    public record LinkSpan(
            int start,
            int end,
            String url
    ) {
    }

    public static List<LinkSpan> findLinks(String text) {
        List<LinkSpan> links = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return links;
        }

        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            int end = trimEnd(
                    text,
                    matcher.start(),
                    matcher.end()
            );
            if (end <= matcher.start()) {
                continue;
            }

            String match = text.substring(
                    matcher.start(),
                    end
            );
            String url = startsWithHttp(match)
                    ? match
                    : "https://" + match;
            links.add(new LinkSpan(
                    matcher.start(),
                    end,
                    url
            ));
        }
        return links;
    }

    private static boolean startsWithHttp(String value) {
        return value.regionMatches(
                true,
                0,
                "http://",
                0,
                7
        ) || value.regionMatches(
                true,
                0,
                "https://",
                0,
                8
        );
    }

    private static int trimEnd(
            String text,
            int start,
            int end
    ) {
        int result = end;
        while (result > start) {
            char last = text.charAt(result - 1);
            if (last == '.'
                    || last == ','
                    || last == ';'
                    || last == ':'
                    || last == '!') {
                result--;
                continue;
            }
            if (last == ')'
                    && count(text, start, result, '(')
                    < count(text, start, result, ')')) {
                result--;
                continue;
            }
            if (last == ']'
                    && count(text, start, result, '[')
                    < count(text, start, result, ']')) {
                result--;
                continue;
            }
            if (last == '}'
                    && count(text, start, result, '{')
                    < count(text, start, result, '}')) {
                result--;
                continue;
            }
            break;
        }
        return result;
    }

    private static int count(
            String value,
            int start,
            int end,
            char target
    ) {
        int count = 0;
        for (int i = start; i < end; i++) {
            if (value.charAt(i) == target) {
                count++;
            }
        }
        return count;
    }
}
