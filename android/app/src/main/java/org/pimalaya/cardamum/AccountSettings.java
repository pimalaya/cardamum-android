package org.pimalaya.cardamum;

import android.app.AlertDialog;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The full-screen account settings controller behind the drawer: it
 * opens one account's settings screen over the drawer, stages the
 * per-addressbook activation, cadence and spoke switches (a master pair
 * fanning onto every book, an advanced fold exposing the per-book
 * sections), commits them to the base and background sync on save, and
 * confirms then removes the account and everything under it. It reaches
 * the base, store and io executor through the host, which keeps the
 * overlay navigation and calls in through {@link #open}, {@link #save},
 * {@link #leave} and {@link #confirmDeleteCurrent}.
 */
final class AccountSettings {
    private final MainActivity host;

    /** The account whose settings screen is open (null outside it). */
    private String settingsEmail;

    /** The open settings screen's addressbooks, in store order. */
    private List<BookEntry> settingsBooks = new ArrayList<>();

    /** Staged switches per addressbook URL; the FAB commits them. */
    private final Map<String, BookSettings> bookSettings = new java.util.LinkedHashMap<>();

    /** Whether the settings screen's advanced sections are unfolded. */
    private boolean settingsAdvancedOpen;

    AccountSettings(MainActivity host) {
        this.host = host;
    }

    /** One addressbook's staged switches on the account settings screen. */
    private static final class BookSettings {
        boolean enabled;
        boolean remote;
        boolean local;
        int interval;
    }

    /**
     * Opens one account's settings screen: the staged switches load
     * from the store, the screen fades in over the drawer it came
     * from, which stays open underneath for the return. The simple
     * pair (activate, cadence) fans out onto every addressbook;
     * Advanced unfolds the per-book sections.
     */
    void open(String email) {
        settingsEmail = email;
        settingsBooks = new ArrayList<>();
        bookSettings.clear();
        for (BookEntry entry : host.base.loadAllAddressbooks()) {
            if (entry.accountEmail.equals(email)) {
                settingsBooks.add(entry);
                BookSettings staged = new BookSettings();
                staged.enabled = entry.subscribed;
                staged.remote = entry.remoteSynced;
                staged.local = entry.phoneSynced;
                staged.interval = BackgroundSync.intervalIndex(host, entry.book.url);
                bookSettings.put(entry.book.url, staged);
            }
        }
        settingsAdvancedOpen = false;

        ((TextView) host.findViewById(R.id.account_title)).setText(email);
        renderAccountSettings();
        host.openOverlay(MainActivity.PANEL_ACCOUNT);
    }

    /**
     * Rebuilds the settings screen from the staged switches; every bulk
     * change re-renders, so the simple pair, the advanced sections and
     * the dimming always agree.
     */
    private void renderAccountSettings() {
        LinearLayout content = host.findViewById(R.id.account_content);
        content.removeAllViews();

        LinearLayout.LayoutParams rowParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);

        boolean anyEnabled = false;
        for (BookSettings staged : bookSettings.values()) {
            anyEnabled |= staged.enabled;
        }

        // The master switch fans onto every addressbook: on puts both
        // spokes on, off shuts everything down, cadence included.
        CheckBox activate = new CheckBox(host);
        activate.setChecked(anyEnabled);
        content.addView(optionRow(R.string.account_enable, activate), rowParams);
        activate.setOnCheckedChangeListener(
                (view, checked) -> {
                    for (BookSettings staged : bookSettings.values()) {
                        staged.enabled = checked;
                        staged.remote = checked;
                        staged.local = checked;
                        if (!checked) {
                            staged.interval = 0;
                        }
                    }
                    renderAccountSettings();
                });

        // The account-wide cadence shows the first enabled book's pick
        // and fans a change onto every book.
        int shown = 0;
        for (BookSettings staged : bookSettings.values()) {
            if (staged.enabled) {
                shown = staged.interval;
                break;
            }
        }
        Spinner interval = host.intervalSpinner();
        interval.setMinimumHeight(host.dp(48));
        interval.setSelection(shown);
        interval.setEnabled(anyEnabled);
        interval.setAlpha(anyEnabled ? 1f : 0.5f);
        LinearLayout.LayoutParams intervalParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        // NOTE: the framework caret renders further in than the checkbox
        // glyph, so the spinner stops 8dp short of the rows' end padding
        // to line the caret up with the boxes (as the onboarding cadence).
        intervalParams.setMarginStart(host.dp(16));
        intervalParams.setMarginEnd(host.dp(4));
        content.addView(interval, intervalParams);

        // A 1dp separator between the account pair and the advanced fold.
        View line = new View(host);
        line.setBackgroundColor(host.getColor(R.color.surface));
        LinearLayout.LayoutParams lineParams =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, host.dp(1));
        lineParams.setMargins(0, host.dp(12), 0, host.dp(12));
        content.addView(line, lineParams);
        onIntervalPicked(
                interval,
                position -> {
                    boolean changed = false;
                    for (BookSettings staged : bookSettings.values()) {
                        changed |= staged.interval != position;
                        staged.interval = position;
                    }
                    if (changed && settingsAdvancedOpen) {
                        renderAccountSettings();
                    }
                });

        // The per-addressbook sections below only matter on multi-book
        // accounts or for spoke-level tuning, so they hide behind a fold.
        CheckBox advanced = new CheckBox(host);
        advanced.setChecked(settingsAdvancedOpen);
        content.addView(optionRow(R.string.account_advanced, advanced), rowParams);
        advanced.setOnCheckedChangeListener(
                (view, checked) -> {
                    settingsAdvancedOpen = checked;
                    renderAccountSettings();
                });

        if (!settingsAdvancedOpen) {
            return;
        }

        for (BookEntry entry : settingsBooks) {
            BookSettings staged = bookSettings.get(entry.book.url);

            TextView header = new TextView(host);
            header.setText(entry.book.name);
            header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            header.setTextColor(host.resolveColor(android.R.attr.textColorPrimary));
            header.setSingleLine(true);
            header.setEllipsize(android.text.TextUtils.TruncateAt.END);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setMinimumHeight(host.dp(48));
            header.setPadding(host.dp(16), 0, host.dp(16), 0);
            content.addView(header, rowParams);

            LinearLayout rows = new LinearLayout(host);
            rows.setOrientation(LinearLayout.VERTICAL);
            rows.setPadding(host.dp(12), 0, 0, 0);
            content.addView(rows, rowParams);

            CheckBox enable = new CheckBox(host);
            enable.setChecked(staged.enabled);
            rows.addView(optionRow(R.string.book_enable, enable), rowParams);
            enable.setOnCheckedChangeListener(
                    (view, checked) -> {
                        staged.enabled = checked;
                        staged.remote = checked;
                        staged.local = checked;
                        if (!checked) {
                            staged.interval = 0;
                        }
                        renderAccountSettings();
                    });

            // The spoke switches and cadence need the book on, so their
            // rows dim with it.
            CheckBox remote = new CheckBox(host);
            remote.setChecked(staged.remote);
            remote.setEnabled(staged.enabled);
            View remoteRow = optionRow(R.string.book_remote_sync, remote);
            remoteRow.setAlpha(staged.enabled ? 1f : 0.5f);
            rows.addView(remoteRow, rowParams);
            remote.setOnCheckedChangeListener((view, checked) -> staged.remote = checked);

            CheckBox local = new CheckBox(host);
            local.setChecked(staged.local);
            local.setEnabled(staged.enabled);
            View localRow = optionRow(R.string.book_local_sync, local);
            localRow.setAlpha(staged.enabled ? 1f : 0.5f);
            rows.addView(localRow, rowParams);
            local.setOnCheckedChangeListener((view, checked) -> staged.local = checked);

            Spinner cadence = host.intervalSpinner();
            cadence.setMinimumHeight(host.dp(48));
            cadence.setSelection(staged.interval);
            cadence.setEnabled(staged.enabled);
            cadence.setAlpha(staged.enabled ? 1f : 0.5f);
            LinearLayout.LayoutParams cadenceParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            cadenceParams.setMarginStart(host.dp(16));
            cadenceParams.setMarginEnd(host.dp(4));
            rows.addView(cadence, cadenceParams);
            onIntervalPicked(cadence, position -> staged.interval = position);
        }
    }

    /**
     * Wires a cadence spinner's user picks to the staged state,
     * swallowing the selection callback Android fires on layout for
     * the initial value (it would clobber differing per-book picks).
     */
    private void onIntervalPicked(Spinner spinner, java.util.function.IntConsumer picked) {
        spinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    private boolean initial = true;

                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {
                        if (initial) {
                            initial = false;
                            return;
                        }
                        picked.accept(position);
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });
    }

    /** Commits the staged switches and returns to the drawer. */
    void save() {
        boolean cadence = false;
        for (Map.Entry<String, BookSettings> staged : bookSettings.entrySet()) {
            BookSettings state = staged.getValue();
            host.base.setBookState(staged.getKey(), state.enabled, state.remote, state.local);
            long minutes =
                    state.enabled ? BackgroundSync.INTERVAL_MINUTES[state.interval] : 0;
            BackgroundSync.setInterval(host, staged.getKey(), minutes);
            cadence |= minutes > 0;
        }
        BackgroundSync.reconcile(host, host.base.loadAllAddressbooks());
        if (cadence) {
            host.ensureNotificationsPermission();
        }

        // The subscription switches move what the contacts root shows.
        host.reloadContacts();
        leave();
    }

    /** Leaves the settings screen, landing on the still-open drawer. */
    void leave() {
        settingsEmail = null;
        host.closeOverlay(MainActivity.PANEL_ACCOUNT);
        host.screen = MainActivity.PANEL_CONTACTS;
        host.applyChrome(MainActivity.PANEL_CONTACTS);
        // NOTE: the drawer never closed under the overlay, so its rows
        // refresh in place.
        host.reloadHome();
    }

    /**
     * One settings row: the label on the left, the checkbox at the end,
     * vertically centred on a shared height; tapping anywhere on the row
     * toggles the box (while it is enabled). Pads itself like the edit
     * form's rows (the container stays unpadded for the separators);
     * the 12dp end centres the checkbox glyph under the bar's 48dp
     * buttons (4dp bar padding + 24 = 28dp centreline, the glyph
     * sitting 16dp in from the widget's end).
     */
    private View optionRow(int label, CheckBox box) {
        TextView text = new TextView(host);
        text.setText(label);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        text.setTextColor(host.resolveColor(android.R.attr.textColorPrimary));
        text.setLayoutParams(
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(host.dp(48));
        row.setPadding(host.dp(16), 0, host.dp(12), 0);
        row.addView(text);
        row.addView(box);
        row.setOnClickListener(
                view -> {
                    if (box.isEnabled()) {
                        box.toggle();
                    }
                });
        return row;
    }

    /** Confirms deletion of the account whose settings screen is open. */
    void confirmDeleteCurrent() {
        confirmDeleteAccount(settingsEmail);
    }

    /**
     * Confirms, then removes the account and everything under it,
     * leaving its settings screen for the drawer underneath.
     */
    private void confirmDeleteAccount(String email) {
        new AlertDialog.Builder(host)
                .setTitle(R.string.delete_account)
                .setMessage(R.string.delete_account_confirm)
                .setPositiveButton(
                        android.R.string.ok,
                        (dialog, which) -> {
                            deleteAccount(email);
                            if (host.screen == MainActivity.PANEL_ACCOUNT) {
                                leave();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteAccount(String email) {
        // NOTE: zero the books' sync intervals and reconcile while they
        // are still listed, so their periodic work cancels; remember
        // their URLs to remove the phone accounts that mirrored them.
        List<String> urls = new ArrayList<>();
        for (BookEntry entry : host.base.loadAllAddressbooks()) {
            if (entry.accountEmail.equals(email)) {
                urls.add(entry.book.url);
                BackgroundSync.setInterval(host, entry.book.url, 0);
            }
        }
        BackgroundSync.reconcile(host, host.base.loadAllAddressbooks());

        host.store.remove(email);
        host.base.detachAccountToLocal(email);
        host.accounts.removeIf(entry -> entry.email.equals(email));
        host.reloadHome();

        // NOTE: the deleted books' phone accounts go explicitly (their
        // rows are already gone, so a reconcile could not name them);
        // reconciling the remaining phone-synced set sweeps any straggler.
        List<BookEntry> phoneBooks = host.phoneSyncedBooks();
        host.io.execute(() -> {
            try {
                for (String url : urls) {
                    Accounts.remove(host, url);
                }
                Accounts.reconcile(host, phoneBooks);
            } catch (Exception error) {
                Log.w("cardamum", "account purge failed for " + email + ": " + error);
            }
        });
    }
}
