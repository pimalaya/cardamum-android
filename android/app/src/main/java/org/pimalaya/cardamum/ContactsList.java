package org.pimalaya.cardamum;

import android.content.Context;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The contacts screen: the merged list, its recycling adapter, the
 * in-bar search and the multi-select mode. It owns the display state
 * (the replica pool, the grouped rows, the sorted-and-filtered rows,
 * the selection and search flags) and rebuilds it off the main thread
 * through {@link ContactPool}; the host keeps the chrome bar it shares
 * with the other screens (re-running {@link #updateSelectionUi} on
 * every return) and the flows that leave the screen (opening a contact,
 * merging, importing, syncing).
 */
final class ContactsList {
    private final MainActivity host;

    /** The replica pool: every subscribed book's cards, unmerged. */
    private List<Entry> contacts = new ArrayList<>();

    /** The merged rows as the bridge grouped them, unfiltered. */
    private List<Group> groupedContacts = new ArrayList<>();

    /** The merged rows, filtered by the search query and sorted; backs
     *  the adapter. */
    private List<Group> sortedContacts = new ArrayList<>();

    private final Adapter adapter = new Adapter();

    /** Multi-select state, keyed by merged group. */
    private boolean selectionMode;

    private final Set<String> selectedKeys = new java.util.HashSet<>();

    /** Whether the in-bar search field is open. */
    private boolean searchOpen;

    /** Lower-cased raw-vCard filter; empty shows all. */
    private String searchQuery = "";

    /** Debounced search refresh, so typing does not re-render per keystroke. */
    private Runnable pendingSearch;

    ContactsList(MainActivity host) {
        this.host = host;
    }

    /** Wires the list, its bar buttons, the search field and the pull-down. */
    void setUp() {
        host.findViewById(R.id.contacts_more).setOnClickListener(this::showMoreMenu);
        host.findViewById(R.id.contacts_birthdays)
                .setOnClickListener(
                        view -> new Birthdays(host).show(new ArrayList<>(sortedContacts)));
        host.findViewById(R.id.contacts_duplicates)
                .setOnClickListener(
                        view -> new DuplicateReview(host).find(new ArrayList<>(contacts)));
        // Pull-to-refresh runs the same syncAll as the drawer; its own
        // spinner retracts right away, the modal dialog carries the wait.
        androidx.swiperefreshlayout.widget.SwipeRefreshLayout refresh =
                host.findViewById(R.id.contacts_refresh);
        refresh.setColorSchemeColors(host.ui.resolveColor(android.R.attr.colorAccent));
        refresh.setOnRefreshListener(
                () -> {
                    refresh.setRefreshing(false);
                    host.syncAll();
                });

        host.findViewById(R.id.contacts_menu)
                .setOnClickListener(view -> host.openBooksManager());
        host.findViewById(R.id.contacts_merge).setOnClickListener(view -> mergeSelected());
        host.findViewById(R.id.contacts_delete)
                .setOnClickListener(view -> confirmDeleteSelected());
        host.findViewById(R.id.contacts_close).setOnClickListener(view -> exitSelection());
        host.findViewById(R.id.contacts_select_all)
                .setOnClickListener(view -> toggleSelectAll());

        host.findViewById(R.id.contacts_search).setOnClickListener(view -> openSearch());
        host.findViewById(R.id.contacts_search_close).setOnClickListener(view -> closeSearch());
        ((EditText) host.findViewById(R.id.contacts_search_input))
                .addTextChangedListener(
                        new android.text.TextWatcher() {
                            @Override
                            public void beforeTextChanged(
                                    CharSequence s, int start, int count, int after) {}

                            @Override
                            public void onTextChanged(
                                    CharSequence s, int start, int before, int count) {}

                            @Override
                            public void afterTextChanged(android.text.Editable s) {
                                String query = s.toString().trim().toLowerCase();
                                if (pendingSearch != null) {
                                    host.main.removeCallbacks(pendingSearch);
                                }
                                pendingSearch =
                                        () -> {
                                            searchQuery = query;
                                            render();
                                        };
                                host.main.postDelayed(pendingSearch, 250);
                            }
                        });

        ListView list = host.findViewById(R.id.contacts_list);
        list.setAdapter(adapter);
        list.setOnItemClickListener(
                (parent, view, position, id) -> {
                    Group group = sortedContacts.get(position);
                    if (selectionMode) {
                        toggleSelection(group.key);
                    } else {
                        host.openGroup(group);
                    }
                });
        list.setOnItemLongClickListener(
                (parent, view, position, id) -> {
                    selectionMode = true;
                    toggleSelection(sortedContacts.get(position).key);
                    return true;
                });

        TextView sticky = host.findViewById(R.id.contacts_sticky_letter);
        // NOTE: clickable only in selection mode, else it passes touches
        // through to the list; tapping it selects or clears that letter.
        sticky.setClickable(false);
        sticky.setOnClickListener(view -> toggleLetter(sticky.getText().toString()));
        list.setOnScrollListener(
                new android.widget.AbsListView.OnScrollListener() {
                    @Override
                    public void onScrollStateChanged(android.widget.AbsListView v, int state) {}

                    @Override
                    public void onScroll(
                            android.widget.AbsListView v, int first, int count, int total) {
                        if (first < sortedContacts.size()) {
                            sticky.setText(
                                    letter(sortedContacts.get(first).primary().displayName()));
                        }
                    }
                });
    }

    /**
     * Rebuilds the contacts list from the base (staged edits included):
     * the full table scan and the bridge grouping run on the io
     * executor, and the render lands back on the main thread; the io
     * executor is single-threaded, so overlapping reloads apply in
     * order. The account snapshot keeps the grouping off the live
     * main-thread cache.
     */
    void reload() {
        List<AccountEntry> snapshot = new ArrayList<>(host.accounts);
        host.io.execute(
                () -> {
                    List<Entry> entries = host.pool.loadEntries();
                    List<Group> groups = host.pool.group(entries, snapshot);
                    host.postAlive(
                            () -> {
                                contacts = entries;
                                groupedContacts = groups;
                                render();
                            });
                });
    }

    /** The rows currently on screen (search-filtered), for export and
     *  the birthday peek. */
    List<Group> visibleGroups() {
        return sortedContacts;
    }

    boolean isSelectionMode() {
        return selectionMode;
    }

    boolean isSearchOpen() {
        return searchOpen;
    }

    /**
     * Filters the grouped rows by the search query and refreshes the
     * list; a group matches when any of its replicas does. Conflicts
     * float to the top so they cannot be missed.
     */
    private void render() {
        TextView sticky = host.findViewById(R.id.contacts_sticky_letter);
        updateSelectionUi();

        sortedContacts = new ArrayList<>();
        for (Group group : groupedContacts) {
            boolean matches = searchQuery.isEmpty();
            for (Entry entry : group.replicas) {
                if (matches || entry.card.vcard.toLowerCase().contains(searchQuery)) {
                    matches = true;
                    break;
                }
            }
            if (matches) {
                sortedContacts.add(group);
            }
        }

        // NOTE: conflicts float to the top; the stable sort keeps the
        // bridge's display-name order within each bucket.
        java.util.Collections.sort(
                sortedContacts,
                (left, right) -> Boolean.compare(right.conflicted(), left.conflicted()));
        adapter.notifyDataSetChanged();

        // One empty state for every cause: a search miss, an empty
        // addressbook, or no account.
        boolean empty = sortedContacts.isEmpty();
        host.findViewById(R.id.contacts_empty).setVisibility(empty ? View.VISIBLE : View.GONE);
        sticky.setText(empty ? "" : letter(sortedContacts.get(0).primary().displayName()));

        // NOTE: rows are uniform, so sizing the sticky letter box to one
        // row height aligns both letters' centres.
        ListView list = host.findViewById(R.id.contacts_list);
        list.post(
                () -> {
                    View first = list.getChildAt(0);
                    if (first != null && sticky.getLayoutParams().height != first.getHeight()) {
                        sticky.getLayoutParams().height = first.getHeight();
                        sticky.requestLayout();
                    }
                });
    }

    /** Recycling adapter for the contacts list. */
    private final class Adapter extends android.widget.BaseAdapter {
        @Override
        public int getCount() {
            return sortedContacts.size();
        }

        @Override
        public Object getItem(int position) {
            return sortedContacts.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            View row =
                    convertView != null
                            ? convertView
                            : host.getLayoutInflater().inflate(R.layout.item_contact, parent, false);

            Group group = sortedContacts.get(position);
            Entry entry = group.primary();
            String name = entry.displayName();

            TextView avatar = row.findViewById(R.id.contact_avatar);
            avatar.setText(letter(name));
            avatar.setBackground(avatarCircle(entry.card != null ? entry.card.vcard : name));

            ((TextView) row.findViewById(R.id.contact_name)).setText(name);

            // One supporting line: the phone, else the email, else the
            // card's fallback info.
            String subtitle = entry.phone;
            if (subtitle == null || subtitle.isEmpty()) {
                subtitle = entry.email;
            }
            if (subtitle == null || subtitle.isEmpty()) {
                subtitle = entry.info;
            }
            bindLine(row.findViewById(R.id.contact_subtitle), subtitle);

            // The link glyph and card count, only for a contact backed
            // by several physical cards.
            int cards = host.pool.distinctRefs(group.replicas).size();
            row.findViewById(R.id.contact_link_icon)
                    .setVisibility(cards > 1 ? View.VISIBLE : View.GONE);
            TextView links = row.findViewById(R.id.contact_links);
            links.setVisibility(cards > 1 ? View.VISIBLE : View.GONE);
            links.setText(String.valueOf(cards));

            // The trailing slot: the selection checkbox, or outside
            // selection the warning flag for a conflict or divergence.
            boolean isFlagged = group.conflicted() || diverged(group);
            CheckBox check = row.findViewById(R.id.contact_check);
            check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            check.setChecked(selectedKeys.contains(group.key));
            android.widget.ImageView danger = row.findViewById(R.id.contact_diverged);
            danger.setImageTintList(
                    android.content.res.ColorStateList.valueOf(
                            host.ui.resolveColor(android.R.attr.colorError)));
            danger.setVisibility(!selectionMode && isFlagged ? View.VISIBLE : View.GONE);
            row.findViewById(R.id.contact_end_slot)
                    .setVisibility(selectionMode || isFlagged ? View.VISIBLE : View.GONE);

            // A contact living only in the hidden local book (attached to
            // no addressbook) shows muted.
            row.setAlpha(attachedToNoBook(group) ? 0.5f : 1f);

            return row;
        }
    }

    /** True when every replica of the group is in the hidden local book. */
    private boolean attachedToNoBook(Group group) {
        for (Entry entry : group.replicas) {
            if (!LocalBook.is(entry.accountEmail)) {
                return false;
            }
        }
        return true;
    }

    /** The selected rows' replicas, in list order. */
    private List<Entry> selectedReplicas() {
        List<Entry> replicas = new ArrayList<>();
        for (Group group : sortedContacts) {
            if (selectedKeys.contains(group.key)) {
                replicas.addAll(group.replicas);
            }
        }
        return replicas;
    }

    /**
     * Merges the selected rows into one single card through the merge
     * form (the host owns the survivor choice and the fan-out).
     */
    private void mergeSelected() {
        host.mergeReplicas(selectedReplicas());
    }

    /** Toggles a contact's selection and refreshes the list and app bar. */
    private void toggleSelection(String key) {
        if (!selectedKeys.remove(key)) {
            selectedKeys.add(key);
        }
        if (selectedKeys.isEmpty()) {
            exitSelection();
        } else {
            updateSelectionUi();
            adapter.notifyDataSetChanged();
        }
    }

    void exitSelection() {
        selectionMode = false;
        selectedKeys.clear();
        updateSelectionUi();
        adapter.notifyDataSetChanged();
    }

    /** True when every listed contact is selected. */
    private boolean allSelected() {
        if (sortedContacts.isEmpty()) {
            return false;
        }
        for (Group group : sortedContacts) {
            if (!selectedKeys.contains(group.key)) {
                return false;
            }
        }
        return true;
    }

    /** Selects every contact, or clears them when all are already selected. */
    private void toggleSelectAll() {
        boolean all = allSelected();
        selectedKeys.clear();
        if (!all) {
            for (Group group : sortedContacts) {
                selectedKeys.add(group.key);
            }
        }
        updateSelectionUi();
        adapter.notifyDataSetChanged();
    }

    /**
     * Selects every contact under a letter, or clears them when they are
     * all already selected (the sticky letter's tap target in selection
     * mode).
     */
    private void toggleLetter(String letter) {
        List<String> keys = new ArrayList<>();
        for (Group group : sortedContacts) {
            if (letter.equals(letter(group.primary().displayName()))) {
                keys.add(group.key);
            }
        }
        boolean all = !keys.isEmpty();
        for (String key : keys) {
            all &= selectedKeys.contains(key);
        }
        for (String key : keys) {
            if (all) {
                selectedKeys.remove(key);
            } else {
                selectedKeys.add(key);
            }
        }
        updateSelectionUi();
        adapter.notifyDataSetChanged();
    }

    /** Swaps the title for the search pill and opens the keyboard. */
    private void openSearch() {
        searchOpen = true;
        host.findViewById(R.id.bar_title).setVisibility(View.GONE);
        host.findViewById(R.id.contacts_bar_spacer).setVisibility(View.GONE);
        host.findViewById(R.id.contacts_search).setVisibility(View.GONE);
        host.findViewById(R.id.contacts_search_pill).setVisibility(View.VISIBLE);
        host.findViewById(R.id.contacts_search_close).setVisibility(View.VISIBLE);

        EditText input = host.findViewById(R.id.contacts_search_input);
        input.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        host.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(input, 0);
    }

    /** Clears the query and gives the title its place back. */
    void closeSearch() {
        ((EditText) host.findViewById(R.id.contacts_search_input)).setText("");
        // NOTE: the watcher clears the query only after its debounce, so
        // reset it now too, or an immediate reload filters the stale one.
        searchQuery = "";
        if (!searchOpen) {
            return;
        }
        searchOpen = false;

        host.hideKeyboard();
        // The chrome only moves while the contacts screen shows it.
        if (!host.onContactsScreen()) {
            return;
        }
        host.findViewById(R.id.contacts_search_pill).setVisibility(View.GONE);
        host.findViewById(R.id.contacts_search_close).setVisibility(View.GONE);
        host.findViewById(R.id.bar_title).setVisibility(View.VISIBLE);
        host.findViewById(R.id.contacts_bar_spacer).setVisibility(View.VISIBLE);
        host.findViewById(R.id.contacts_search)
                .setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        host.findViewById(R.id.contacts_more_slot)
                .setVisibility(selectionMode ? View.GONE : View.VISIBLE);
    }

    /**
     * The contacts screen's chrome, per selection and search state (the
     * bar is shared across screens, so this only runs while the
     * contacts screen shows; the host's chrome re-runs it on every
     * return).
     */
    void updateSelectionUi() {
        if (!host.onContactsScreen()) {
            return;
        }

        // The selected count takes the title's spot, so an open search
        // gives way.
        if (selectionMode) {
            closeSearch();
        }

        TextView title = host.findViewById(R.id.bar_title);
        if (selectionMode) {
            title.setText(host.getString(R.string.selected_count, selectedKeys.size()));
        } else {
            title.setText(R.string.contacts_title);
        }
        title.setVisibility(searchOpen ? View.GONE : View.VISIBLE);
        host.findViewById(R.id.contacts_bar_spacer)
                .setVisibility(searchOpen ? View.GONE : View.VISIBLE);
        host.findViewById(R.id.contacts_search_pill)
                .setVisibility(searchOpen ? View.VISIBLE : View.GONE);
        host.findViewById(R.id.contacts_search_close)
                .setVisibility(searchOpen ? View.VISIBLE : View.GONE);

        host.findViewById(R.id.contacts_search)
                .setVisibility(selectionMode || searchOpen ? View.GONE : View.VISIBLE);
        // The birthday and duplicates icons stay through search, so the
        // pill shrinks to end at them.
        host.findViewById(R.id.contacts_birthdays)
                .setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        host.findViewById(R.id.contacts_duplicates)
                .setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        host.findViewById(R.id.contacts_menu)
                .setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        host.findViewById(R.id.contacts_close)
                .setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        // Merging needs at least two physical cards.
        host.findViewById(R.id.contacts_merge)
                .setVisibility(
                        selectionMode && host.pool.distinctRefs(selectedReplicas()).size() > 1
                                ? View.VISIBLE
                                : View.GONE);
        host.findViewById(R.id.contacts_delete)
                .setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        // The select-all box, checked when every contact is selected.
        host.findViewById(R.id.contacts_select_all_slot)
                .setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        ((CheckBox) host.findViewById(R.id.contacts_select_all))
                .setChecked(selectionMode && allSelected());
        host.findViewById(R.id.contacts_sticky_letter).setClickable(selectionMode);
        // NOTE: the whole overflow slot goes in selection mode, not just
        // its button, else an empty 48dp frame pushes the icons off edge.
        host.findViewById(R.id.contacts_more_slot)
                .setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        host.findViewById(R.id.fab).setVisibility(selectionMode ? View.GONE : View.VISIBLE);
    }

    /** Confirms, then stages a delete for every selected contact. */
    private void confirmDeleteSelected() {
        new android.app.AlertDialog.Builder(host)
                .setMessage(R.string.delete_selected_confirm)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> deleteSelected())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteSelected() {
        // Deleting a merged row deletes every replica behind it, each
        // against its own account.
        for (Group group : sortedContacts) {
            if (selectedKeys.contains(group.key)) {
                for (Entry entry : group.replicas) {
                    AccountEntry account = host.accountFor(entry.accountEmail);
                    if (account == null) {
                        continue;
                    }
                    host.base.markDeleted(
                            entry.accountEmail,
                            ContactPool.cardKey(account.account, entry.book.url, entry.card.id),
                            entry.card);
                }
            }
        }
        selectionMode = false;
        selectedKeys.clear();
        reload();
    }

    /**
     * A muted round avatar background, its hue mapped from the card's
     * raw vCard rather than the display name: distinct contacts (their
     * UID and address fields differ) spread across the wheel, while a
     * card lightly edited keeps almost the same colour.
     */
    private android.graphics.drawable.GradientDrawable avatarCircle(String vcard) {
        int color = android.graphics.Color.HSVToColor(new float[] {hueOf(vcard), 0.4f, 0.55f});
        android.graphics.drawable.GradientDrawable circle =
                new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(color);
        return circle;
    }

    /**
     * Maps a vCard to a hue in [0, 360) by summing its code points. The
     * sum is locality preserving, so a one-character edit shifts the hue
     * by one degree, yet the differing name, email and UID keep distinct
     * cards far apart (unlike hashing the display name, where near
     * identical names collapse onto near identical hues).
     */
    private static float hueOf(String vcard) {
        if (vcard == null) {
            return 0f;
        }
        long sum = 0;
        for (int index = 0; index < vcard.length(); index++) {
            sum += vcard.charAt(index);
        }
        return sum % 360;
    }

    /** Sets a diminished sub-line, hiding it when the value is empty. */
    private void bindLine(TextView view, String value) {
        if (value == null || value.isEmpty()) {
            view.setVisibility(View.GONE);
        } else {
            view.setText(value);
            view.setVisibility(View.VISIBLE);
        }
    }

    /** True when the linked replicas' normalized contents differ. */
    private static boolean diverged(Group group) {
        String hash = group.primary().hash;
        for (Entry entry : group.replicas) {
            if (!hash.equals(entry.hash)) {
                return true;
            }
        }
        return false;
    }

    /** The contacts overflow menu: Import, Export (sync lives in the
     *  drawer and the pull-down, duplicates and birthdays on their own
     *  bar buttons). Text-only: the framework popup renders forced
     *  icons flush against their labels on some Android releases. */
    private void showMoreMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(host, anchor);
        menu.getMenu().add(0, 1, 0, R.string.import_contacts);
        menu.getMenu().add(0, 2, 1, R.string.export_contacts);
        menu.setOnMenuItemClickListener(
                item -> {
                    switch (item.getItemId()) {
                        case 1:
                            host.importContacts();
                            return true;
                        case 2:
                            host.exportContacts();
                            return true;
                        default:
                            return false;
                    }
                });
        menu.show();
    }

    static String letter(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return "#";
        }
        char first = Character.toUpperCase(trimmed.charAt(0));
        return Character.isLetter(first) ? String.valueOf(first) : "#";
    }
}
