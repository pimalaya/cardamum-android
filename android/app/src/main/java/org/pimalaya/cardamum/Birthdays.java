package org.pimalaya.cardamum;

import android.app.AlertDialog;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import org.pimalaya.cardamum.client.Cards;

/**
 * The next-birthday peek behind the app bar's cake icon: one sentence
 * telling who celebrates the next birthday and when, computed off the
 * main thread from the merged rows' projected BDAY (full dates only).
 */
final class Birthdays {
    private final MainActivity host;

    Birthdays(MainActivity host) {
        this.host = host;
    }

    /** Computes the sentence over the given rows, then shows the dialog. */
    void show(List<Group> groups) {
        host.io.execute(
                () -> {
                    Calendar today = Calendar.getInstance();
                    today.set(Calendar.HOUR_OF_DAY, 0);
                    today.set(Calendar.MINUTE, 0);
                    today.set(Calendar.SECOND, 0);
                    today.set(Calendar.MILLISECOND, 0);

                    long soonest = Long.MAX_VALUE;
                    Calendar date = null;
                    List<String> names = new ArrayList<>();
                    for (Group group : groups) {
                        // The group's birthday: the first replica
                        // projecting one wins, like the merged form.
                        String birthday = null;
                        for (Entry entry : group.replicas) {
                            try {
                                String projected =
                                        Cards.projectCard(entry.card).optString("birthday");
                                if (projected.matches("\\d{4}-\\d{2}-\\d{2}")) {
                                    birthday = projected;
                                    break;
                                }
                            } catch (Exception error) {
                                // NOTE: an unparsable card does not compete.
                            }
                        }
                        if (birthday == null) {
                            continue;
                        }

                        // The next occurrence, at local midnight; a lenient
                        // calendar rolls Feb 29 to Mar 1 off leap years. The
                        // rounding absorbs the DST hour between midnights.
                        Calendar next = (Calendar) today.clone();
                        next.set(Calendar.MONTH, Integer.parseInt(birthday.substring(5, 7)) - 1);
                        next.set(
                                Calendar.DAY_OF_MONTH,
                                Integer.parseInt(birthday.substring(8, 10)));
                        if (next.before(today)) {
                            next.add(Calendar.YEAR, 1);
                        }
                        long days =
                                Math.round(
                                        (next.getTimeInMillis() - today.getTimeInMillis())
                                                / 86400000.0);

                        if (days < soonest) {
                            soonest = days;
                            date = next;
                            names.clear();
                        }
                        if (days == soonest) {
                            names.add(group.primary().displayName());
                        }
                    }

                    // One readable sentence: "Alice and Bob celebrate
                    // their birthdays on 12 April, in 3 days."
                    String message;
                    if (names.isEmpty()) {
                        message = host.getString(R.string.birthdays_none);
                    } else {
                        String joined =
                                names.size() == 1
                                        ? names.get(0)
                                        : String.join(", ", names.subList(0, names.size() - 1))
                                                + host.getString(R.string.birthdays_and)
                                                + names.get(names.size() - 1);
                        if (soonest == 0) {
                            message =
                                    host.getResources()
                                            .getQuantityString(
                                                    R.plurals.birthdays_message_today,
                                                    names.size(),
                                                    joined);
                        } else {
                            String when =
                                    host.getResources()
                                            .getQuantityString(
                                                    R.plurals.birthday_in_days,
                                                    (int) soonest,
                                                    (int) soonest);
                            String day =
                                    new SimpleDateFormat("d MMMM", Locale.getDefault())
                                            .format(date.getTime());
                            message =
                                    host.getResources()
                                            .getQuantityString(
                                                    R.plurals.birthdays_message,
                                                    names.size(),
                                                    joined,
                                                    day,
                                                    when);
                        }
                    }
                    host.main.post(
                            () ->
                                    new AlertDialog.Builder(host)
                                            .setTitle(R.string.birthdays_title)
                                            .setMessage(message)
                                            .setPositiveButton(android.R.string.ok, null)
                                            .show());
                });
    }
}
