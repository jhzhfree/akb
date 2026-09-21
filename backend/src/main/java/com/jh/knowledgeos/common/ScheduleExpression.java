package com.jh.knowledgeos.common;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A small cron expression: minute, hour, day of month, month, day of week.
 *
 * <p>Planned derivation needs a schedule an operator can read and change, but the alternative is
 * either a polling table that cannot express "every Monday at 03:00" or a scheduler framework for
 * a handful of rules. This covers what the console actually offers — wildcards, steps, lists and
 * ranges — and nothing else, so an unsupported expression fails at save time with a clear message
 * instead of silently firing at the wrong moment.</p>
 */
public final class ScheduleExpression {

    private static final int MAX_DAYS = 366;

    private final Set<Integer> minutes;

    private final Set<Integer> hours;

    private final Set<Integer> days;

    private final Set<Integer> months;

    private final Set<Integer> weekdays;

    private final String source;

    private ScheduleExpression(String source, Set<Integer> minutes, Set<Integer> hours, Set<Integer> days, Set<Integer> months, Set<Integer> weekdays) {
        this.source = source;
        this.minutes = minutes;
        this.hours = hours;
        this.days = days;
        this.months = months;
        this.weekdays = weekdays;
    }

    /**
     * Parse a five field expression; throws with the offending field when it cannot be read.
     */
    public static ScheduleExpression of(String expression) {
        String raw = expression == null ? "" : expression.trim();
        String[] parts = raw.split("\\s+");
        if (parts.length != 5) {
            throw new IllegalArgumentException("计划表达式需要 5 段（分 时 日 月 周），例如：0 3 * * 1");
        }
        return new ScheduleExpression(raw, parseField(parts[0], 0, 59, "分钟"), parseField(parts[1], 0, 23, "小时"), parseField(parts[2], 1, 31, "日"), parseField(parts[3], 1, 12, "月"), parseField(parts[4], 1, 7, "星期"));
    }

    /**
     * Next matching time strictly after the given moment. Days of month and days of week are
     * OR-ed, as cron does: either one being enough for the day to match.
     */
    public LocalDateTime nextAfter(LocalDateTime from) {
        LocalDateTime candidate = from.plusMinutes(1).withSecond(0).withNano(0);
        for (int offset = 0; offset < MAX_DAYS; offset++) {
            LocalDateTime day = candidate.plusDays(offset);
            if (!dayMatches(day)) {
                continue;
            }
            for (int hour : hours) {
                for (int minute : minutes) {
                    LocalDateTime hit = day.withHour(hour).withMinute(minute);
                    if (!hit.isAfter(from)) {
                        continue;
                    }
                    if (offset > 0 || hit.isAfter(from)) {
                        return hit;
                    }
                }
            }
        }
        throw new IllegalArgumentException("计划表达式在未来一年内没有匹配时间：" + source);
    }

    private boolean dayMatches(LocalDateTime day) {
        boolean monthOk = months.contains(day.getMonthValue());
        boolean dayOfMonthOk = days.contains(day.getDayOfMonth());
        int weekday = day.getDayOfWeek() == DayOfWeek.SUNDAY ? 7 : day.getDayOfWeek().getValue();
        boolean weekdayOk = weekdays.contains(weekday);
        return monthOk && (dayOfMonthOk || weekdayOk);
    }

    public String source() {
        return source;
    }

    /**
     * Human readable summary for the rule list, so a schedule can be checked at a glance.
     */
    public String describe() {
        if (minutes.size() == 60) {
            return "每分钟";
        }
        if (hours.size() == 24 && minutes.size() == 1) {
            return "每小时第 " + minutes.iterator().next() + " 分";
        }
        StringBuilder text = new StringBuilder();
        text.append(join(hours)).append(" 时 ").append(join(minutes)).append(" 分");
        if (days.size() < 31) {
            text.append("，每月 ").append(join(days)).append(" 日");
        }
        if (weekdays.size() < 7) {
            text.append("，每周 ").append(joinWeekdays(weekdays));
        }
        return text.toString();
    }

    private static String join(Set<Integer> values) {
        List<String> parts = new ArrayList<String>();
        for (Integer value : values) {
            parts.add(String.valueOf(value));
        }
        if (parts.size() > 5) {
            return parts.get(0) + "…" + parts.get(parts.size() - 1);
        }
        return String.join("、", parts);
    }

    private static String joinWeekdays(Set<Integer> values) {
        String[] names = { "一", "二", "三", "四", "五", "六", "日" };
        List<String> parts = new ArrayList<String>();
        for (Integer value : values) {
            parts.add(names[value - 1]);
        }
        return String.join("、", parts);
    }

    /**
     * Parses one field: a wildcard, a stepped wildcard, a list or a range.
     */
    private static Set<Integer> parseField(String field, int min, int max, String label) {
        Set<Integer> values = new TreeSet<Integer>();
        for (String part : field.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            if ("*".equals(token)) {
                for (int value = min; value <= max; value++) {
                    values.add(value);
                }
                continue;
            }
            if (token.startsWith("*/")) {
                int step = number(token.substring(2), label);
                if (step <= 0) {
                    throw new IllegalArgumentException(label + "步长必须大于 0");
                }
                for (int value = min; value <= max; value += step) {
                    values.add(value);
                }
                continue;
            }
            if (token.contains("-")) {
                String[] bounds = token.split("-");
                if (bounds.length != 2) {
                    throw new IllegalArgumentException(label + "范围写法不正确：" + token);
                }
                int from = number(bounds[0], label);
                int to = number(bounds[1], label);
                if (from > to) {
                    throw new IllegalArgumentException(label + "范围起点不能大于终点：" + token);
                }
                for (int value = from; value <= to; value++) {
                    values.add(value);
                }
                continue;
            }
            values.add(number(token, label));
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException(label + "字段不能为空");
        }
        for (Integer value : values) {
            if (value < min || value > max) {
                throw new IllegalArgumentException(label + "取值必须在 " + min + " 到 " + max + " 之间");
            }
        }
        return Collections.unmodifiableSet(new LinkedHashSet<Integer>(values));
    }

    private static int number(String value, String label) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "字段不是数字：" + value);
        }
    }
}
