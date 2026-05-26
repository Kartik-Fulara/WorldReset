package com.worldreset.utils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * A lightweight CRON parser supporting standard 5-field syntax:
 * [minute] [hour] [day of month] [month] [day of week]
 * 
 * Supports:
 *  - * (all)
 *  - , (list)
 *  - - (range)
 *  - / (increments, e.g. * / 5)
 */
public class CronParser {

    private final String expression;
    private final Field minute, hour, dom, month, dow;

    public CronParser(String expression) {
        this.expression = expression;
        String[] parts = expression.trim().split("\\s+");
        if (parts.length != 5) {
            throw new IllegalArgumentException("Invalid CRON expression (must have 5 fields): " + expression);
        }

        this.minute = new Field(parts[0], 0, 59);
        this.hour   = new Field(parts[1], 0, 23);
        this.dom    = new Field(parts[2], 1, 31);
        this.month  = new Field(parts[3], 1, 12);
        this.dow    = new Field(parts[4], 0, 7); // 0 or 7 is Sunday
    }

    public boolean matches(LocalDateTime dateTime) {
        int d = dateTime.getDayOfWeek().getValue();
        int cronDow = (d == 7) ? 0 : d; // Adjust ISO Sunday (7) to Cron Sunday (0)

        return minute.matches(dateTime.getMinute()) &&
               hour.matches(dateTime.getHour()) &&
               dom.matches(dateTime.getDayOfMonth()) &&
               month.matches(dateTime.getMonthValue()) &&
               (dow.matches(cronDow) || (d == 7 && dow.matches(7)));
    }

    private static class Field {
        private final Set<Integer> values = new HashSet<>();

        Field(String part, int min, int max) {
            if (part.equals("*")) {
                for (int i = min; i <= max; i++) values.add(i);
                return;
            }

            for (String piece : part.split(",")) {
                if (piece.contains("/")) {
                    String[] split = piece.split("/");
                    int start = split[0].equals("*") ? min : Integer.parseInt(split[0]);
                    int step = Integer.parseInt(split[1]);
                    for (int i = start; i <= max; i += step) values.add(i);
                } else if (piece.contains("-")) {
                    String[] range = piece.split("-");
                    int start = Integer.parseInt(range[0]);
                    int end = Integer.parseInt(range[1]);
                    for (int i = start; i <= end; i++) values.add(i);
                } else {
                    values.add(Integer.parseInt(piece));
                }
            }
        }

        boolean matches(int val) {
            return values.contains(val);
        }
    }
}
