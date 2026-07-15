package org.pimalaya.cardamum;

import android.app.AlertDialog;
import android.net.Uri;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.pimalaya.cardamum.client.Card;

/**
 * The vCard file import and export behind the contacts list: it reads a
 * chosen file off the main thread and stores each card it holds in the
 * picked addressbook, and writes the active contacts view out as a
 * single vCard file, both behind the shared FAB loader. It reaches the
 * base, accounts, the io executor and the contacts list through the
 * host, which keeps the SAF pickers and the {@code onActivityResult}
 * router and calls in through {@link #importFile} and
 * {@link #exportFile}.
 */
final class VcfTransfer {
    private final MainActivity host;

    VcfTransfer(MainActivity host) {
        this.host = host;
    }

    /**
     * Picks the addressbook the file imports into: the hidden local book
     * when no real account is configured, the sole book when there is one,
     * otherwise a chooser (the local book is never offered explicitly).
     */
    void importFile(Uri uri) {
        List<BookEntry> books = new ArrayList<>();
        BookEntry local = null;
        for (BookEntry entry : host.base.loadSubscribedAddressbooks()) {
            if (LocalBook.is(entry.accountEmail)) {
                local = entry;
            } else {
                books.add(entry);
            }
        }

        if (books.isEmpty()) {
            if (local != null) {
                importInto(uri, local);
            }
            return;
        }
        if (books.size() == 1) {
            importInto(uri, books.get(0));
            return;
        }

        List<BookEntry> targets = books;
        new AlertDialog.Builder(host)
                .setTitle(R.string.import_target_title)
                .setItems(
                        host.bookLabels(targets),
                        (dialog, which) -> importInto(uri, targets.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Reads a vCard file off the main thread and stores each card it holds
     * in the chosen addressbook (a card in the local book stays unattached
     * and shows muted until placed).
     */
    private void importInto(Uri uri, BookEntry target) {
        // The FAB disables and spins while the file reads, as in the auth flow.
        host.setAuthLoading(R.id.fab, R.id.fab_progress, true);
        host.io.execute(
                () -> {
                    int count = 0;
                    Exception failure = null;
                    try {
                        count = importCards(readText(uri), target);
                    } catch (Exception error) {
                        failure = error;
                    }
                    int imported = count;
                    Exception error = failure;
                    host.main.post(
                            () -> {
                                host.setAuthLoading(R.id.fab, R.id.fab_progress, false);
                                if (error != null) {
                                    host.showError(error, R.string.import_failed);
                                } else {
                                    host.reloadContacts();
                                    host.toast(host.getString(R.string.import_done, imported));
                                }
                            });
                });
    }

    /** Splits the text into vCards and stores each in the target book. */
    private int importCards(String text, BookEntry target) {
        AccountEntry account = host.accountFor(target.accountEmail);
        int count = 0;
        for (String vcard : Vcf.split(text)) {
            String uid = host.cardIndex(vcard).optString("uid");
            String id = uid.isEmpty() ? UUID.randomUUID().toString() : uid;
            String key =
                    account != null
                            ? ContactPool.cardKey(account.account, target.book.url, id)
                            : CardStore.key(target.book.url, id);
            host.base.saveLocal(
                    target.accountEmail, key, target.book.url, new Card(id, null, null, vcard));
            count++;
        }
        return count;
    }

    /** Reads a content Uri fully as UTF-8 text. */
    private String readText(Uri uri) throws java.io.IOException {
        try (java.io.InputStream in = host.getContentResolver().openInputStream(uri);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }

    /**
     * Writes the active contacts view (the currently listed, search-filtered
     * contacts) to the chosen file as one vCard file, each contact's raw
     * document appended verbatim, off the main thread behind the FAB loader.
     */
    void exportFile(Uri uri) {
        List<Group> snapshot = new ArrayList<>(host.visibleGroups());
        host.setAuthLoading(R.id.fab, R.id.fab_progress, true);
        host.io.execute(
                () -> {
                    Exception failure = null;
                    try {
                        writeText(uri, buildVcf(snapshot));
                    } catch (Exception error) {
                        failure = error;
                    }
                    Exception error = failure;
                    host.main.post(
                            () -> {
                                host.setAuthLoading(R.id.fab, R.id.fab_progress, false);
                                if (error != null) {
                                    host.showError(error, R.string.export_failed);
                                } else {
                                    host.toast(
                                            host.getString(R.string.export_done, snapshot.size()));
                                }
                            });
                });
    }

    /** One vCard per contact (its primary replica's document), appended
     *  verbatim so the file round-trips through the importer. */
    private String buildVcf(List<Group> groups) {
        List<String> vcards = new ArrayList<>(groups.size());
        for (Group group : groups) {
            vcards.add(group.primary().card.vcard);
        }
        return Vcf.join(vcards);
    }

    /** Writes text to a content Uri as UTF-8. */
    private void writeText(Uri uri, String text) throws java.io.IOException {
        try (java.io.OutputStream out = host.getContentResolver().openOutputStream(uri, "w")) {
            if (out == null) {
                throw new java.io.IOException("Could not open the destination file");
            }
            out.write(text.getBytes("UTF-8"));
        }
    }
}
