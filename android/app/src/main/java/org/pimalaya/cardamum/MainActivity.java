package org.pimalaya.cardamum;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.cardamum.billing.BillingFactory;
import org.pimalaya.cardamum.client.Cards;
import org.pimalaya.cardamum.client.Account;
import org.pimalaya.cardamum.client.Addressbook;
import org.pimalaya.cardamum.client.Card;
import org.pimalaya.cardamum.client.CardamumClient;
import org.pimalaya.cardamum.client.CardamumException;

/**
 * Single-activity host walking the app's screens through a ViewFlipper.
 *
 * <p>First launch is the connection flow: email, provider detection and
 * discovery, proposed configurations, the credentials and a connection
 * check, then the addressbook selection. Every subsequent launch lands
 * on the app's root: the merged contacts list across every subscribed
 * addressbook of every account, contact-first per docs/merged-view.md
 * (replicas sharing a vCard UID collapse into one row; storage and sync
 * stay strictly per-replica). A merged row opens one edit form for the
 * whole contact and saving fans the form out onto every replica.
 * Addressbooks live behind a management screen; syncs are manual, from
 * the drawer or the pull-down.
 */
public class MainActivity extends Activity {
    // NOTE: contacts, contact, advanced and source are the content
    // flipper's child indexes; home and auth are whole-frame overlays
    // sliding over it, bar included (only the FAB stays above them).
    private static final int PANEL_CONTACTS = 0;
    private static final int PANEL_CONTACT = 1;
    private static final int PANEL_ADVANCED = 2;
    private static final int PANEL_SOURCE = 3;
    private static final int PANEL_AUTH = 5;
    private static final int PANEL_ACCOUNT = 6;

    /** The auth flow's steps, inside its own flipper under one bar. */
    static final int STEP_EMAIL = 0;

    static final int STEP_CONFIG = 1;
    static final int STEP_BOOKS = 2;

    static final int REQUEST_CONTACTS = 1;
    private static final int REQUEST_IMPORT = 2;
    private static final int REQUEST_EXPORT = 3;
    private static final int REQUEST_NOTIFICATIONS = 4;

    /**
     * One replica in the contacts pool: a card, the addressbook it
     * lives in, the account owning it and its write-time index
     * (docs/merged-view.md), so rendering never parses a vCard: see
     * {@link Entry}, {@link Group} and {@link ContactPool}.
     */
    final CardamumClient client = new CardamumClient();
    final ExecutorService io = Executors.newSingleThreadExecutor();
    final Handler main = new Handler(Looper.getMainLooper());
    final Ui ui = new Ui(this);

    SecureStore store;
    CardStore base;
    private SyncRunner runner;
    private ViewFlipper flipper;
    private ViewFlipper authFlipper;
    private ContactForm form;

    /** Every connected account (multi-account). */
    final List<AccountEntry> accounts = new ArrayList<>();

    /** The connection wizard and its OAuth grants (see onCreate wiring). */
    private OnboardingFlow onboarding;

    private OauthFlow oauth;

    /** The accounts drawer, opened by the contacts burger. */
    private androidx.drawerlayout.widget.DrawerLayout drawer;

    /** The account whose settings screen is open (null outside it). */
    private String settingsEmail;

    /** The open settings screen's addressbooks, in store order. */
    private List<BookEntry> settingsBooks = new ArrayList<>();

    /** Staged switches per addressbook URL; the FAB commits them. */
    private final Map<String, BookSettings> bookSettings = new java.util.LinkedHashMap<>();

    /** Whether the settings screen's advanced sections are unfolded. */
    private boolean settingsAdvancedOpen;

    /** One addressbook's staged switches on the account settings screen. */
    private static final class BookSettings {
        boolean enabled;
        boolean remote;
        boolean local;
        int interval;
    }

    /**
     * The shared sync state: true while a sync runs. Every UI element
     * that reflects it (the modal loader, the drawer's sync row)
     * subscribes through {@link #observeSync}, and {@link #setSyncing}
     * pushes the flag to all of them at once.
     */
    private boolean syncing;

    private final List<java.util.function.Consumer<Boolean>> syncObservers = new ArrayList<>();

    /** The modal sync dialog, up while the syncing flag is (showSyncDialog). */
    private AlertDialog syncDialog;

    /** The pool's loader and bridge grouping (built in onCreate). */
    ContactPool pool;

    /** The contacts screen: list, adapter, search and selection. */
    private ContactsList contactsList;

    /** Sync to re-run once the contacts permission is granted, if any. */
    private Runnable afterContactsPermission;

    /** The editor's working state, replaced blank when it leaves. */
    private EditSession edit = new EditSession();

    /** The screen currently shown: a flipper panel, or an overlay. */
    private int screen = PANEL_CONTACTS;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        drawer = findViewById(R.id.drawer);
        applyEdgeToEdge();

        // Support prompt (docs/monetization.md): a no-op on the FOSS
        // build; on the Google build, a dismissable ask shown at most
        // once per rolling 3 days until any tier was paid. Only on a
        // genuine user-initiated open: a fresh start (a rotation does
        // not re-fire) that is neither an OAuth redirect nor a headless
        // adb sync hook.
        boolean headless =
                getIntent().getBooleanExtra("syncRemote", false)
                        || getIntent().getBooleanExtra("syncLocal", false);
        if (savedInstanceState == null && getIntent().getData() == null && !headless) {
            BillingFactory.create(this).prompt(this);
        }

        store = new SecureStore(this);
        base = new CardStore(this);
        pool = new ContactPool(base, accounts);
        contactsList = new ContactsList(this);
        runner = new SyncRunner(this, base, store, client, syncObserver());
        // The two flows reference each other (the grants land back in
        // the wizard), so one side binds late.
        oauth = new OauthFlow(this);
        onboarding = new OnboardingFlow(this, oauth);
        oauth.onConnected = onboarding::connect;
        oauth.onAborted = onboarding::resetConfigContinue;
        flipper = findViewById(R.id.flipper);
        authFlipper = findViewById(R.id.auth_flipper);
        form = new ContactForm(this);
        // Re-gate the editor's validate FAB whenever the form re-renders
        // (a merge enables it only once every conflict is resolved).
        form.setOnRender(this::updateSaveEnabled);

        setUpScreens();
        setUpEmailPanel();
        contactsList.setUp();
        setUpContactPanel();
        setUpHomePanel();

        setUpFab(R.id.fab);
        findViewById(R.id.fab).setOnClickListener(view -> onFabClick());
        findViewById(R.id.bar_back).setOnClickListener(view -> onBarBack());

        // Every sync entry point (drawer, pull-down, Sync local,
        // post-onboarding) drives the one syncing flag, so the modal
        // dialog binds here once and covers them all.
        observeSync(this::showSyncDialog);

        // The built-in local book is always present and subscribed, so
        // the app opens usable with no account: onboarding is never
        // forced; accounts are added from the addressbooks screen.
        base.ensureLocalAddressbook(
                LocalBook.URL, LocalBook.ACCOUNT, LocalBook.ID, getString(R.string.local_book));
        accounts.add(LocalBook.account());
        accounts.addAll(store.loadAll());

        // Self-heal the background sync schedules: an app update or a
        // wiped WorkManager database silently drops periodic work, and
        // reconciling on launch converges it back onto the stored books.
        BackgroundSync.reconcile(this, base.loadAllAddressbooks());

        // Offline first: the merged contacts root renders instantly from
        // the store, syncs are manual (the drawer or the pull-down).
        goHome();

        // NOTE: adb-only hooks, so syncs can be driven headlessly:
        // am start ... --ez syncRemote true / --ez syncLocal true
        if (getIntent().getBooleanExtra("syncRemote", false)) {
            syncRemote(true);
        } else if (getIntent().getBooleanExtra("syncLocal", false)) {
            syncLocal();
        }

        // An OAuth redirect can land here rather than in onNewIntent:
        // when the process died while the user was in the browser, the
        // redirect recreates the activity from scratch.
        oauth.handleRedirect(getIntent());

        // A fresh start with no real account opens the connection flow
        // right away: with nothing to show underneath, the flow is the
        // first screen; back dismisses it onto the empty list. Skipped
        // while an OAuth redirect is being processed.
        if (savedInstanceState == null && getIntent().getData() == null && !hasRealAccount()) {
            startAuth();
        }
    }

    /**
     * Enters the connection flow: the auth sheet fades in over the
     * current screen while the drawer it came from slides shut.
     * Cancelling lands on the contacts root, finishing too.
     */
    private void startAuth() {
        openAuthFlow();
        if (drawer.isDrawerOpen(android.view.Gravity.START)) {
            drawer.closeDrawer(android.view.Gravity.START);
        }
    }

    /** Resets the connection flow to its first step and shows it. */
    private void openAuthFlow() {
        onboarding.open();
    }

    /** Navigates the auth flow forward: the step slides in from the right. */
    void showAuth(int step) {
        showAuth(step, false);
    }

    /** Navigates the auth flow back: the step slides in from the left. */
    private void showAuthBack(int step) {
        showAuth(step, true);
    }

    /**
     * Shows an auth step under the persistent bar: only the inner step
     * view transitions. Entering the flow from outside jumps the inner
     * flipper without animation (the outer panel fade is the
     * transition).
     */
    private void showAuth(int step, boolean back) {
        boolean inside = screen == PANEL_AUTH;
        if (inside) {
            hideKeyboard();
            authFlipper.setInAnimation(this, back ? R.anim.slide_in_left : R.anim.slide_in_right);
            authFlipper.setOutAnimation(
                    this, back ? R.anim.slide_out_right : R.anim.slide_out_left);
        } else {
            authFlipper.setInAnimation(null);
            authFlipper.setOutAnimation(null);
        }

        authFlipper.setDisplayedChild(step);
        if (inside) {
            applyChrome(PANEL_AUTH);
        } else {
            // The flow fades in over the current screen.
            openOverlay(PANEL_AUTH);
        }
    }

    /**
     * The auth bar per step: the step's title (the first step greets
     * with Welcome on a fresh install and reads Add account once an
     * account already exists), no back arrow on the welcome step when
     * no account is stored (nothing to go back to), and the cross only
     * when one is (the flow is escape-free otherwise).
     */
    private void applyAuthChrome() {
        int step = authFlipper.getDisplayedChild();
        int title =
                step == STEP_EMAIL
                        ? (hasRealAccount() ? R.string.add_account : R.string.auth_step_email)
                        : step == STEP_CONFIG
                                ? R.string.auth_step_config
                                : R.string.auth_step_books;
        ((TextView) findViewById(R.id.auth_title)).setText(title);
    }

    /** The auth bar's back arrow, per step. */
    private void authBack() {
        int step = authFlipper.getDisplayedChild();
        if (step == STEP_BOOKS) {
            // Nothing persists before the selection confirms, so the
            // books step steps back into the flow like any other.
            showAuthBack(STEP_CONFIG);
        } else if (step == STEP_CONFIG) {
            showAuthBack(STEP_EMAIL);
        } else {
            // The email step is only reachable when adding an account.
            cancelAuth();
        }
    }

    /**
     * Leaves the auth flow without finishing: the sheet slides out onto
     * the contacts root (the drawer stays closed).
     */
    private void cancelAuth() {
        closeOverlay(PANEL_AUTH);
        screen = PANEL_CONTACTS;
        applyChrome(PANEL_CONTACTS);
    }

    /** The account entry matching an email, or null. */
    AccountEntry accountFor(String email) {
        for (AccountEntry entry : accounts) {
            if (entry.email.equals(email)) {
                return entry;
            }
        }
        return null;
    }

    /** Whether any real (non-local) account is configured. */
    private boolean hasRealAccount() {
        for (AccountEntry entry : accounts) {
            if (!LocalBook.is(entry.email)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gates the editor's validate FAB: always live in a plain edit, live
     * in a merge only once every diverging field has been reviewed.
     */
    private void updateSaveEnabled() {
        if (screen != PANEL_CONTACT) {
            return;
        }

        android.widget.ImageButton fab = findViewById(R.id.fab);
        if (form.conflictResolved()) {
            fab.setBackgroundTintList(null);
            fab.setImageResource(R.drawable.ic_check);
            fab.setImageTintList(android.content.res.ColorStateList.valueOf(accentContrast()));
            fab.setContentDescription(getString(R.string.contact_save));
            setFabEnabled(R.id.fab, true);
            return;
        }

        // Diverging rows await review: the disc turns the error tone
        // and wears the same warning glyph the rows carry, full
        // strength so the state reads as a signal, not a dimmed save.
        fab.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        resolveColor(android.R.attr.colorError)));
        fab.setImageResource(R.drawable.ic_diverged);
        fab.setImageTintList(
                android.content.res.ColorStateList.valueOf(
                        resolveColor(android.R.attr.textColorPrimary)));
        fab.setContentDescription(getString(R.string.contact_diverged_pending));
        fab.setEnabled(false);
        fab.setAlpha(1f);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        // shutdownNow interrupts, but a blocking accept() only wakes
        // on close: without this the OAuth loopback listener would
        // outlive the activity by up to its 300s timeout.
        oauth.closeLoopback();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        oauth.handleRedirect(intent);
    }

    @Override
    public void onBackPressed() {
        // The sync loader is modal: the sync cannot be interrupted
        // mid-pass, so back waits with the rest of the screen.
        if (syncing) {
            return;
        }
        // The account settings overlay sits above the open drawer: back
        // peels it off first, landing on the drawer it came from.
        if (screen == PANEL_ACCOUNT) {
            leaveAccountSettings();
            return;
        }
        if (drawer.isDrawerOpen(android.view.Gravity.START)) {
            drawer.closeDrawer(android.view.Gravity.START);
            return;
        }
        if (screen == PANEL_CONTACTS && contactsList.isSelectionMode()) {
            contactsList.exitSelection();
            return;
        }

        Screen entry = screens.get(screen);
        if (entry != null && entry.systemBack != null) {
            entry.systemBack.run();
        } else {
            super.onBackPressed();
        }
    }

    /** The app's root: the merged contacts list across every account. */
    private void goHome() {
        contactsList.closeSearch();
        contactsList.exitSelection();

        reloadContacts();

        // Landing on the root is always a return: the auth sheet slides
        // back out, the drawer closes, the root stays put underneath.
        if (screen == PANEL_AUTH) {
            closeOverlay(PANEL_AUTH);
            screen = PANEL_CONTACTS;
            applyChrome(PANEL_CONTACTS);
        } else {
            showBack(PANEL_CONTACTS);
        }

        if (drawer.isDrawerOpen(android.view.Gravity.START)) {
            drawer.closeDrawer(android.view.Gravity.START);
        }
        // A new or removed account shows next time the drawer opens.
        reloadHome();
    }

    /**
     * Refreshes the addressbooks management screen and slides it over
     * the contacts list like a drawer (the list stays put).
     */
    void openBooksManager() {
        reloadHome();
        drawer.openDrawer(android.view.Gravity.START);
    }

    // ---- Connection screen --------------------------------------------------

    private void setUpEmailPanel() {
        EditText email = findViewById(R.id.email_input);
        findViewById(R.id.auth_back).setOnClickListener(view -> authBack());
        findViewById(R.id.auth_cancel).setOnClickListener(view -> cancelAuth());

        // The shared FAB is the step's continue action; it stays
        // disabled until the field holds something plausible (an email,
        // a server, or a connection URI), so an empty or malformed
        // input is simply not submittable.
        email.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        if (screen == PANEL_AUTH
                                && authFlipper.getDisplayedChild() == STEP_EMAIL) {
                            setFabEnabled(R.id.fab, onboarding.emailSubmittable());
                        }
                    }
                });
    }

    /**
     * Toggles an auth continue FAB between its arrow and an on-disc
     * loader, the button disabled while loading.
     */
    void setAuthLoading(int buttonId, int progressId, boolean loading) {
        android.widget.ImageButton button = findViewById(buttonId);
        setFabEnabled(buttonId, !loading);
        button.setImageAlpha(loading ? 0 : 255);
        findViewById(progressId).setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    /** Enables or disables an auth continue FAB, dimming its disc. */
    void setFabEnabled(int id, boolean enabled) {
        View fab = findViewById(id);
        fab.setEnabled(enabled);
        fab.setAlpha(enabled ? 1f : 0.4f);
    }

    /**
     * Styles a floating action button: its icon tinted to contrast the
     * accent disc's luminance.
     */
    private void setUpFab(int id) {
        android.widget.ImageButton fab = findViewById(id);
        fab.setImageTintList(android.content.res.ColorStateList.valueOf(accentContrast()));
    }

    /** Black or white, whichever reads on the accent colour. */
    private int accentContrast() {
        int accent = resolveColor(android.R.attr.colorAccent);
        return android.graphics.Color.luminance(accent) > 0.5f
                ? android.graphics.Color.BLACK
                : android.graphics.Color.WHITE;
    }

    /**
     * A background-sync interval spinner over the shared labels, flush
     * with the surrounding text like the contact form's type spinners.
     */
    Spinner intervalSpinner() {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<CharSequence> intervals =
                ArrayAdapter.createFromResource(
                        this, R.array.background_sync_intervals, R.layout.spinner_form_item);
        intervals.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(intervals);
        // Only the start goes flush; the background's own end padding
        // (the caret zone) stays, so a long entry ellipsizes before
        // running under the caret.
        spinner.setPadding(0, 0, spinner.getPaddingRight(), 0);
        return spinner;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from the browser without a redirect (the user
        // cancelled the grant) must not leave the config Continue stuck
        // on its loader; a real redirect re-enters loading right after.
        if (flipper != null
                && screen == PANEL_AUTH
                && authFlipper.getDisplayedChild() == STEP_CONFIG) {
            onboarding.resetConfigContinue();
        }
    }

    // ---- Addressbooks management screen -----------------------------------------

    private void setUpHomePanel() {
        // The drawer: the title bar's closing cross, then the bottom
        // band: sync, add an account, or open About (its dialog carries
        // the app name and version). Sync slides the drawer shut on its
        // way in, so the modal dialog runs over the contacts list and
        // the outcome toasts land on the refreshed list.
        findViewById(R.id.drawer_close)
                .setOnClickListener(view -> drawer.closeDrawer(Gravity.START));
        findViewById(R.id.drawer_sync)
                .setOnClickListener(
                        view -> {
                            drawer.closeDrawer(Gravity.START);
                            syncAll();
                        });
        findViewById(R.id.drawer_add).setOnClickListener(view -> startAuth());
        findViewById(R.id.drawer_about).setOnClickListener(view -> showAbout());

        // The account settings screen's own bar: back returns to the
        // contacts root, the trash deletes the account after
        // confirmation.
        findViewById(R.id.account_back).setOnClickListener(view -> leaveAccountSettings());
        findViewById(R.id.account_delete)
                .setOnClickListener(view -> confirmDeleteAccount(settingsEmail));

        // The settings overlay's own save FAB (the shared one draws
        // under the drawer the overlay covers).
        android.widget.ImageButton accountFab = findViewById(R.id.account_fab);
        accountFab.setImageTintList(
                android.content.res.ColorStateList.valueOf(accentContrast()));
        accountFab.setOnClickListener(view -> saveAccountSettings());

        // While a sync runs the row goes inert; the modal dialog carries
        // the only spinner.
        observeSync(
                active -> {
                    View row = findViewById(R.id.drawer_sync);
                    row.setEnabled(!active);
                    row.setAlpha(active ? 0.5f : 1f);
                });
    }

    /** The app's version name, or empty when the package is unreadable. */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            return "";
        }
    }

    /** The About dialog: version and pitch, a Website link and a bug report. */
    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(getString(R.string.about_body, appVersion()))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(
                        R.string.about_website, (dialog, which) -> openUrl("https://pimalaya.org"))
                .setNegativeButton(
                        R.string.report_bug,
                        (dialog, which) ->
                                openUrl("https://github.com/pimalaya/cardamum-android/issues"))
                .show();
    }

    /** Opens a URL in the browser. */
    private void openUrl(String url) {
        startActivity(
                new android.content.Intent(
                        android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
    }

    /**
     * Rebuilds the drawer's accounts listing: one row per connected
     * account (the local book stays hidden), the email with a trailing
     * settings cog. Tapping the row opens the account's settings
     * screen: activation, cadence, the per-addressbook advanced
     * switches and deletion, so the drawer itself stays a plain list.
     */
    private void reloadHome() {
        // With no real account the drawer shows an empty state, and its
        // sync row (which would sync nothing) is hidden.
        boolean hasAccount = hasRealAccount();
        findViewById(R.id.drawer_sync).setVisibility(hasAccount ? View.VISIBLE : View.GONE);
        findViewById(R.id.drawer_empty).setVisibility(hasAccount ? View.GONE : View.VISIBLE);

        LinearLayout container = findViewById(R.id.home_container);
        container.removeAllViews();

        java.util.Set<String> emails = new java.util.LinkedHashSet<>();
        for (BookEntry entry : base.loadAllAddressbooks()) {
            if (!LocalBook.is(entry.accountEmail)) {
                emails.add(entry.accountEmail);
            }
        }

        for (String email : emails) {
            // Styled like the footer rows: a leading account glyph then
            // the email, same paddings, gap and icon box.
            android.widget.ImageView glyph = new android.widget.ImageView(this);
            glyph.setImageResource(R.drawable.ic_account);
            glyph.setImageTintList(
                    android.content.res.ColorStateList.valueOf(
                            resolveColor(android.R.attr.textColorPrimary)));
            LinearLayout.LayoutParams glyphParams =
                    new LinearLayout.LayoutParams(
                            dimen(R.dimen.item_icon), dimen(R.dimen.item_icon));
            glyphParams.setMarginEnd(dimen(R.dimen.item_gap));

            TextView label = new TextView(this);
            label.setText(email);
            itemText(label);
            label.setTextColor(resolveColor(android.R.attr.textColorPrimary));
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setLayoutParams(
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            android.widget.ImageView chevron = new android.widget.ImageView(this);
            chevron.setImageResource(R.drawable.ic_chevron_right);
            chevron.setImageTintList(
                    android.content.res.ColorStateList.valueOf(
                            resolveColor(android.R.attr.textColorSecondary)));
            LinearLayout.LayoutParams chevronParams =
                    new LinearLayout.LayoutParams(
                            dimen(R.dimen.item_icon), dimen(R.dimen.item_icon));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dimen(R.dimen.item_height));
            row.setPadding(dimen(R.dimen.item_padding), 0, dimen(R.dimen.item_padding), 0);
            row.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackground));
            row.addView(glyph, glyphParams);
            row.addView(label);
            row.addView(chevron, chevronParams);
            row.setOnClickListener(view -> openAccountSettings(email));
            container.addView(row);
        }
    }

    /**
     * Opens one account's settings screen: the staged switches load
     * from the store, the screen fades in over the drawer it came
     * from, which stays open underneath for the return. The simple
     * pair (activate, cadence) fans out onto every addressbook;
     * Advanced unfolds the per-book sections.
     */
    private void openAccountSettings(String email) {
        settingsEmail = email;
        settingsBooks = new ArrayList<>();
        bookSettings.clear();
        for (BookEntry entry : base.loadAllAddressbooks()) {
            if (entry.accountEmail.equals(email)) {
                settingsBooks.add(entry);
                BookSettings staged = new BookSettings();
                staged.enabled = entry.subscribed;
                staged.remote = entry.remoteSynced;
                staged.local = entry.phoneSynced;
                staged.interval = BackgroundSync.intervalIndex(this, entry.book.url);
                bookSettings.put(entry.book.url, staged);
            }
        }
        settingsAdvancedOpen = false;

        ((TextView) findViewById(R.id.account_title)).setText(email);
        renderAccountSettings();
        openOverlay(PANEL_ACCOUNT);
    }

    /**
     * Rebuilds the settings screen from the staged switches; every bulk
     * change re-renders, so the simple pair, the advanced sections and
     * the dimming always agree.
     */
    private void renderAccountSettings() {
        LinearLayout content = findViewById(R.id.account_content);
        content.removeAllViews();

        LinearLayout.LayoutParams rowParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);

        boolean anyEnabled = false;
        for (BookSettings staged : bookSettings.values()) {
            anyEnabled |= staged.enabled;
        }

        // The master switch: on puts every addressbook fully on (both
        // spokes), off shuts everything down, cadence included.
        CheckBox activate = new CheckBox(this);
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

        // The account-wide cadence, showing the first enabled book's
        // pick and fanning a change out onto every book.
        int shown = 0;
        for (BookSettings staged : bookSettings.values()) {
            if (staged.enabled) {
                shown = staged.interval;
                break;
            }
        }
        Spinner interval = intervalSpinner();
        interval.setMinimumHeight(dp(48));
        interval.setSelection(shown);
        interval.setEnabled(anyEnabled);
        interval.setAlpha(anyEnabled ? 1f : 0.5f);
        LinearLayout.LayoutParams intervalParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        // The framework caret renders further in from the spinner's end
        // than the checkbox glyph sits from its own edge, so the spinner
        // stops 8dp short of the rows' end padding to line the caret up
        // with the boxes (same tuning as the onboarding cadence).
        intervalParams.setMarginStart(dp(16));
        intervalParams.setMarginEnd(dp(4));
        content.addView(interval, intervalParams);

        // The edit form's section separator between the account pair
        // and the advanced fold: a 1dp line in the app bar tone,
        // inset to the rows' own paddings.
        View line = new View(this);
        line.setBackgroundColor(getColor(R.color.surface));
        LinearLayout.LayoutParams lineParams =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lineParams.setMargins(0, dp(12), 0, dp(12));
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

        // The Advanced fold, a checkbox row like every other: the
        // per-addressbook sections below only matter on multi-book
        // accounts or for spoke-level tuning.
        CheckBox advanced = new CheckBox(this);
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

            // The addressbook header, a plain row at the shared type
            // scale; its rows indent under it.
            TextView header = new TextView(this);
            header.setText(entry.book.name);
            header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            header.setTextColor(resolveColor(android.R.attr.textColorPrimary));
            header.setSingleLine(true);
            header.setEllipsize(android.text.TextUtils.TruncateAt.END);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setMinimumHeight(dp(48));
            header.setPadding(dp(16), 0, dp(16), 0);
            content.addView(header, rowParams);

            LinearLayout rows = new LinearLayout(this);
            rows.setOrientation(LinearLayout.VERTICAL);
            rows.setPadding(dp(12), 0, 0, 0);
            content.addView(rows, rowParams);

            CheckBox enable = new CheckBox(this);
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

            // The spoke switches and the cadence need the book on;
            // their rows dim with it.
            CheckBox remote = new CheckBox(this);
            remote.setChecked(staged.remote);
            remote.setEnabled(staged.enabled);
            View remoteRow = optionRow(R.string.book_remote_sync, remote);
            remoteRow.setAlpha(staged.enabled ? 1f : 0.5f);
            rows.addView(remoteRow, rowParams);
            remote.setOnCheckedChangeListener((view, checked) -> staged.remote = checked);

            CheckBox local = new CheckBox(this);
            local.setChecked(staged.local);
            local.setEnabled(staged.enabled);
            View localRow = optionRow(R.string.book_local_sync, local);
            localRow.setAlpha(staged.enabled ? 1f : 0.5f);
            rows.addView(localRow, rowParams);
            local.setOnCheckedChangeListener((view, checked) -> staged.local = checked);

            Spinner cadence = intervalSpinner();
            cadence.setMinimumHeight(dp(48));
            cadence.setSelection(staged.interval);
            cadence.setEnabled(staged.enabled);
            cadence.setAlpha(staged.enabled ? 1f : 0.5f);
            LinearLayout.LayoutParams cadenceParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            cadenceParams.setMarginStart(dp(16));
            cadenceParams.setMarginEnd(dp(4));
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
    private void saveAccountSettings() {
        boolean cadence = false;
        for (Map.Entry<String, BookSettings> staged : bookSettings.entrySet()) {
            BookSettings state = staged.getValue();
            base.setBookState(staged.getKey(), state.enabled, state.remote, state.local);
            long minutes =
                    state.enabled ? BackgroundSync.INTERVAL_MINUTES[state.interval] : 0;
            BackgroundSync.setInterval(this, staged.getKey(), minutes);
            cadence |= minutes > 0;
        }
        BackgroundSync.reconcile(this, base.loadAllAddressbooks());
        if (cadence) {
            ensureNotificationsPermission();
        }

        // The subscription switches move what the contacts root shows.
        reloadContacts();
        leaveAccountSettings();
    }

    /** Leaves the settings screen, landing on the still-open drawer. */
    private void leaveAccountSettings() {
        settingsEmail = null;
        closeOverlay(PANEL_ACCOUNT);
        screen = PANEL_CONTACTS;
        applyChrome(PANEL_CONTACTS);
        // The drawer never closed under the overlay; its rows refresh
        // in place (switches saved, or the account deleted).
        reloadHome();
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
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        text.setTextColor(resolveColor(android.R.attr.textColorPrimary));
        text.setLayoutParams(
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(16), 0, dp(12), 0);
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

    /**
     * Confirms, then removes the account and everything under it,
     * leaving its settings screen for the drawer underneath.
     */
    private void confirmDeleteAccount(String email) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_account)
                .setMessage(R.string.delete_account_confirm)
                .setPositiveButton(
                        android.R.string.ok,
                        (dialog, which) -> {
                            deleteAccount(email);
                            if (screen == PANEL_ACCOUNT) {
                                leaveAccountSettings();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteAccount(String email) {
        // The account's books leave with it: zero their background sync
        // intervals and reconcile while the books are still listed, so
        // their periodic work cancels, and remember their URLs so the
        // phone accounts that mirrored them can be removed below.
        List<String> urls = new ArrayList<>();
        for (BookEntry entry : base.loadAllAddressbooks()) {
            if (entry.accountEmail.equals(email)) {
                urls.add(entry.book.url);
                BackgroundSync.setInterval(this, entry.book.url, 0);
            }
        }
        BackgroundSync.reconcile(this, base.loadAllAddressbooks());

        store.remove(email);
        base.detachAccountToLocal(email);
        accounts.removeIf(entry -> entry.email.equals(email));
        reloadHome();

        // The deleted books' phone accounts go explicitly (their rows
        // are already gone, so a reconcile could not name them), each
        // taking its raw contacts along; reconciling the remaining
        // phone-synced set (the one the sync path maintains) then
        // sweeps any straggler.
        List<BookEntry> phoneBooks = phoneSyncedBooks();
        io.execute(() -> {
            try {
                for (String url : urls) {
                    Accounts.remove(this, url);
                }
                Accounts.reconcile(this, phoneBooks);
            } catch (Exception error) {
                Log.w("cardamum", "account purge failed for " + email + ": " + error);
            }
        });
    }

    /**
     * The replica pool from the store: every subscribed addressbook's
     * cards, tagged with their book and account.
     */
    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request == REQUEST_CONTACTS && hasContactsPermission()) {
            Runnable retry = afterContactsPermission;
            afterContactsPermission = null;
            if (retry != null) {
                retry.run();
            }
        }
    }

    boolean hasContactsPermission() {
        return checkSelfPermission(Manifest.permission.WRITE_CONTACTS)
                        == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_CONTACTS)
                        == PackageManager.PERMISSION_GRANTED;
    }

    /** True while the merged contacts list is the shown screen (the
     *  shared bar is the contacts list's only when this holds). */
    boolean onContactsScreen() {
        return screen == PANEL_CONTACTS;
    }

    // ---- Sync -----------------------------------------------------------------

    /**
     * The runner's hooks into this activity's presentation: the loader
     * dialog's title and detail lines, and the in-memory account cache
     * a token refresh must keep current (mutated on the main thread the
     * drawer reads it from).
     */
    private SyncRunner.Observer syncObserver() {
        return new SyncRunner.Observer() {
            @Override
            public void bookStarted(BookEntry book) {
                syncTitle(book.book.name);
            }

            @Override
            public void step(int stage, int count) {
                syncStep(stage, count);
            }

            @Override
            public void accountRefreshed(AccountEntry updated) {
                main.post(
                        () -> {
                            accounts.removeIf(entry -> entry.email.equals(updated.email));
                            accounts.add(updated);
                        });
            }
        };
    }

    /**
     * Toggles the contacts app-bar sync icon between the glyph and an
     * in-place spinner while a sync runs.
     */
    /** Pushes the sync flag to every subscribed element. */
    private void setSyncing(boolean value) {
        syncing = value;
        for (java.util.function.Consumer<Boolean> observer : syncObservers) {
            observer.accept(value);
        }
    }

    /**
     * Shows or hides the modal sync dialog: non-cancelable (no outside
     * tap, no back), so the wait is explicit instead of an ambiguous
     * spinner, while the screen behind stays fully visible. The title
     * carries the addressbook, the detail the current engine step, and
     * the screen stays on for the duration.
     */
    private void showSyncDialog(boolean active) {
        if (!active) {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (syncDialog != null) {
                syncDialog.dismiss();
                syncDialog = null;
            }
            return;
        }

        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View content = getLayoutInflater().inflate(R.layout.dialog_sync, null);
        ((TextView) content.findViewById(R.id.sync_dialog_title))
                .setText(R.string.sync_overlay_preparing);
        syncDialog =
                new AlertDialog.Builder(this)
                        .setView(content)
                        .setCancelable(false)
                        .show();
    }

    /** Sets the dialog's addressbook line (callable off the main thread). */
    private void syncTitle(String book) {
        main.post(
                () -> {
                    if (syncDialog != null) {
                        ((TextView) syncDialog.findViewById(R.id.sync_dialog_title))
                                .setText(book);
                    }
                });
    }

    /** Sets the loader's step line from an engine stage (any thread). */
    private void syncStep(int stage, int count) {
        String text;
        switch (stage) {
            case OfflineEngine.Progress.STAGE_SERVER:
                text = getString(R.string.sync_step_server);
                break;
            case OfflineEngine.Progress.STAGE_DOWNLOAD:
                text = getString(R.string.sync_step_download, count);
                break;
            case OfflineEngine.Progress.STAGE_UPLOAD:
                text = getString(R.string.sync_step_upload, count);
                break;
            case OfflineEngine.Progress.STAGE_PHONE:
                text = getString(R.string.sync_step_phone);
                break;
            case OfflineEngine.Progress.STAGE_PROJECT:
                text = getString(R.string.sync_step_project, count);
                break;
            case OfflineEngine.Progress.STAGE_RESOLVE:
                text = getString(R.string.sync_step_resolve, count);
                break;
            default:
                return;
        }
        main.post(
                () -> {
                    if (syncDialog != null) {
                        ((TextView) syncDialog.findViewById(R.id.sync_dialog_detail))
                                .setText(text);
                    }
                });
    }

    /**
     * Subscribes an element to the sync state and hands it the current
     * value straight away, so it starts consistent.
     */
    private void observeSync(java.util.function.Consumer<Boolean> observer) {
        syncObservers.add(observer);
        observer.accept(syncing);
    }

    /**
     * Store-to-remote spoke: per addressbook, fetches the remote into
     * the store, pushes the staged local changes, and re-fetches the
     * pushed state. The phone is not touched; that is the local sync.
     * When `toHome`, this is the onboarding's first sync, landing on
     * the contacts list; either way the shared syncing state drives
     * the modal loader over whatever is on screen (the auth sheet
     * included, the loader sits above it).
     */
    void syncRemote(boolean toHome) {
        setSyncing(true);

        io.execute(
                () -> {
                    SyncRunner.Outcome outcome = runner.syncRemote();
                    postAlive(
                            () -> {
                                setSyncing(false);
                                finishSync(toHome);
                                reportSync(outcome);
                            });
                });
    }

    /**
     * Surfaces a sync outcome: an error dialog, or one report toast per
     * axis: a Local toast (cards in, out and changed against the phone's
     * Contacts app) first when any synced book mirrors there, then the
     * Remote toast, carrying the pending-conflicts line when contacts
     * wait for manual resolution. The background notification keeps both
     * axes on one card (SyncWorker.notifyReport).
     */
    private void reportSync(SyncRunner.Outcome outcome) {
        if (outcome.failure != null) {
            // Offline fallback: the store of the last sync keeps failing
            // addressbooks usable.
            showError(outcome.failure, R.string.sync_failed);
            return;
        }

        // Toasts queue, so the local report shows first and the remote
        // one takes its place.
        if (outcome.local) {
            toast(
                    getString(
                            R.string.sync_line_local,
                            outcome.localIn.size(),
                            outcome.localOut.size(),
                            outcome.localChanged.size()));
        }

        StringBuilder message =
                new StringBuilder(
                        getString(
                                R.string.sync_line_remote,
                                outcome.remoteIn.size(),
                                outcome.remoteOut.size(),
                                outcome.remoteChanged.size()));
        if (outcome.conflicts > 0) {
            message.append('\n')
                    .append(getString(R.string.sync_conflicts_pending, outcome.conflicts));
        }
        toast(message.toString());
    }

    /**
     * The one sync behind the sync button: the remote exchange for every
     * subscribed book, then the phone projection for every book set to
     * mirror, under a single spinner. The contacts permission is asked
     * only when something actually projects to the phone.
     */
    void syncAll() {
        // The phone passes touch ContactsContract, so they need the
        // permission; reconciling the Android accounts (which also purges
        // a book just switched off) does not, so it always runs.
        if (!phoneSyncedBooks().isEmpty() && !ensureContactsPermission(this::syncAll)) {
            return;
        }

        setSyncing(true);
        io.execute(
                () -> {
                    SyncRunner.Outcome outcome = runner.syncRemote();
                    OfflineEngine.Report report = new OfflineEngine.Report();
                    Exception failure = runner.syncLocal(report);
                    outcome.absorb(report);
                    outcome.local |= !runner.phoneSyncedBooks().isEmpty();
                    if (outcome.failure == null) {
                        outcome.failure = failure;
                    }
                    postAlive(
                            () -> {
                                setSyncing(false);
                                finishSync(false);
                                reportSync(outcome);
                            });
                });
    }

    /** After a sync, lands on the merged root or refreshes the open list. */
    private void finishSync(boolean toHome) {
        if (toHome) {
            goHome();
        } else {
            reloadContacts();
        }
    }

    /**
     * The phone spoke alone, in-process: reconciles the per-addressbook
     * Android accounts, then runs the two-way phone engine pass per
     * subscribed book right here, behind the same spinner as the remote
     * sync (SyncService keeps serving the syncs the OS schedules on its
     * own, SyncWorker the scheduled background ones). Needs the contacts
     * permission, requested on first use; the full sync's own phone
     * passes stay silently off until this ran once.
     */
    private void syncLocal() {
        if (!ensureContactsPermission(this::syncLocal)) {
            return;
        }

        setSyncing(true);
        io.execute(
                () -> {
                    OfflineEngine.Report report = new OfflineEngine.Report();
                    Exception failure = runner.syncLocal(report);
                    postAlive(
                            () -> {
                                setSyncing(false);
                                if (failure != null) {
                                    showError(failure, R.string.accounts_failed);
                                } else {
                                    toast(
                                            getString(
                                                    R.string.sync_line_local,
                                                    report.localIn.size(),
                                                    report.localOut.size(),
                                                    report.localChanged.size()));
                                    reloadContacts();
                                }
                            });
                });
    }

    /** The subscribed addressbooks the user set to mirror into the phone's Contacts app. */
    private List<BookEntry> phoneSyncedBooks() {
        return runner.phoneSyncedBooks();
    }

    /**
     * Asks for the notifications permission (Android 13+) when
     * background sync gets enabled, so the sync-report notification
     * (and its pending-conflicts warning) can show. Denying keeps
     * background sync working, just silent.
     */
    private void ensureNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[] {Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }

    /**
     * Ensures the contacts permission before a phone-touching sync;
     * when it must be requested, remembers `retry` to run once granted
     * and returns false.
     */
    private boolean ensureContactsPermission(Runnable retry) {
        if (hasContactsPermission()) {
            return true;
        }
        afterContactsPermission = retry;
        requestPermissions(
                new String[] {
                    Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
                },
                REQUEST_CONTACTS);
        return false;
    }

    // ---- Contacts screen ----------------------------------------------------

    /**
     * Opens a merged row in the editor: one form for the whole contact
     * (docs/merged-view.md), the union of the replicas' documents with
     * per-field alternative chips when they diverge. Saving fans the
     * form out onto every replica.
     */
    void openGroup(Group group) {
        if (group.conflicted()) {
            openConflict(group);
        } else {
            openMerged(group.replicas);
        }
    }

    /**
     * Opens the resolution form on a both-sides-edited conflict: the
     * bridge three-way merges the stored base, the local edit and the
     * captured remote (the newer side by REV pre-filled, both offered as
     * chips), and the editor loads it in conflict mode. A conflict with
     * nothing genuinely colliding (only list edits, which merge cleanly)
     * resolves straight away without a prompt.
     */
    private void openConflict(Group group) {
        Entry replica = null;
        for (Entry entry : group.replicas) {
            if (entry.conflicted) {
                replica = entry;
                break;
            }
        }
        if (replica == null) {
            openMerged(group.replicas);
            return;
        }

        String handle =
                CardStore.rowHandle(replica.book.url, replica.card.uri, replica.card.id);
        JSONObject bodies;
        JSONObject resolution;
        try {
            bodies = new OfflineStore(base).loadConflict(replica.book.url, handle);
            if (bodies == null) {
                // The conflict was flagged but its remote is not captured
                // yet (the capturing sync has not run); edit it plainly.
                openMerged(group.replicas);
                return;
            }
            resolution =
                    Cards.mergeConflictForm(
                            bodies.optString("base"),
                            bodies.optString("local"),
                            bodies.optString("remote"));
        } catch (Exception error) {
            showError(error, R.string.contact_open_failed);
            return;
        }

        JSONObject alternatives = resolution.optJSONObject("alternatives");
        if (alternatives == null || alternatives.length() == 0) {
            // Nothing needs the user: stage the clean merge and clear the
            // conflict. The toast is the tap's only visible outcome (no
            // form opens), so without it the vanishing flag reads as a
            // bug.
            try {
                new OfflineEngine(base, client, null, null)
                        .mutateEdit(replica.book.url, handle, resolution.optString("vcard"));
            } catch (Exception error) {
                showError(error, R.string.save_failed);
                return;
            }
            toast(getString(R.string.conflict_auto_resolved));
            reloadContacts();
            return;
        }

        edit = new EditSession();
        edit.book = replica.book;
        edit.accountEmail = replica.accountEmail;
        edit.card = replica.card;
        edit.replicas = new ArrayList<>(java.util.Collections.singletonList(replica));
        edit.resolvingConflict = true;
        edit.vcard = resolution.optString("vcard");
        edit.title = replica.displayName();

        org.json.JSONArray changed = resolution.optJSONArray("changed");
        if (changed == null) {
            changed = new org.json.JSONArray();
        }
        form.load(resolution.optJSONObject("model"), alternatives, changed);
        show(PANEL_CONTACT);
    }

    /** Prompts for a vCard file to import. */
    void importContacts() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (request == REQUEST_IMPORT) {
            chooseImportTarget(data.getData());
        } else if (request == REQUEST_EXPORT) {
            exportFile(data.getData());
        }
    }

    /**
     * Picks the addressbook the file imports into: the hidden local book
     * when no real account is configured, the sole book when there is one,
     * otherwise a chooser (the local book is never offered explicitly).
     */
    private void chooseImportTarget(Uri uri) {
        List<BookEntry> books = new ArrayList<>();
        BookEntry local = null;
        for (BookEntry entry : base.loadSubscribedAddressbooks()) {
            if (LocalBook.is(entry.accountEmail)) {
                local = entry;
            } else {
                books.add(entry);
            }
        }

        if (books.isEmpty()) {
            if (local != null) {
                importFile(uri, local);
            }
            return;
        }
        if (books.size() == 1) {
            importFile(uri, books.get(0));
            return;
        }

        List<BookEntry> targets = books;
        new AlertDialog.Builder(this)
                .setTitle(R.string.import_target_title)
                .setItems(
                        bookLabels(targets),
                        (dialog, which) -> importFile(uri, targets.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Reads a vCard file off the main thread and stores each card it holds
     * in the chosen addressbook (a card in the local book stays unattached
     * and shows muted until placed).
     */
    private void importFile(Uri uri, BookEntry target) {
        // The FAB disables and spins while the file is read, exactly as it
        // does through the auth flow.
        setAuthLoading(R.id.fab, R.id.fab_progress, true);
        io.execute(
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
                    main.post(
                            () -> {
                                setAuthLoading(R.id.fab, R.id.fab_progress, false);
                                if (error != null) {
                                    showError(error, R.string.import_failed);
                                } else {
                                    reloadContacts();
                                    toast(getString(R.string.import_done, imported));
                                }
                            });
                });
    }

    /** Splits the text into vCards and stores each in the target book. */
    private int importCards(String text, BookEntry target) {
        AccountEntry account = accountFor(target.accountEmail);
        int count = 0;
        for (String vcard : Vcf.split(text)) {
            String uid = cardIndex(vcard).optString("uid");
            String id = uid.isEmpty() ? UUID.randomUUID().toString() : uid;
            String key =
                    account != null
                            ? ContactPool.cardKey(account.account, target.book.url, id)
                            : CardStore.key(target.book.url, id);
            base.saveLocal(
                    target.accountEmail, key, target.book.url, new Card(id, null, null, vcard));
            count++;
        }
        return count;
    }

    /** Reads a content Uri fully as UTF-8 text. */
    private String readText(Uri uri) throws java.io.IOException {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }

    /** Prompts for a destination file, then exports the active view as a
     *  single vCard file. */
    void exportContacts() {
        if (contactsList.visibleGroups().isEmpty()) {
            toast(getString(R.string.export_empty));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/vcard");
        intent.putExtra(Intent.EXTRA_TITLE, "contacts.vcf");
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    /**
     * Writes the active contacts view (the currently listed, search-filtered
     * contacts) to the chosen file as one vCard file, each contact's raw
     * document appended verbatim, off the main thread behind the FAB loader.
     */
    private void exportFile(Uri uri) {
        List<Group> snapshot = new ArrayList<>(contactsList.visibleGroups());
        setAuthLoading(R.id.fab, R.id.fab_progress, true);
        io.execute(
                () -> {
                    Exception failure = null;
                    try {
                        writeText(uri, buildVcf(snapshot));
                    } catch (Exception error) {
                        failure = error;
                    }
                    Exception error = failure;
                    main.post(
                            () -> {
                                setAuthLoading(R.id.fab, R.id.fab_progress, false);
                                if (error != null) {
                                    showError(error, R.string.export_failed);
                                } else {
                                    toast(getString(R.string.export_done, snapshot.size()));
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
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
            if (out == null) {
                throw new java.io.IOException("Could not open the destination file");
            }
            out.write(text.getBytes("UTF-8"));
        }
    }

    /**
     * Starts a new contact, asking for the target addressbook whenever
     * more than one real one is subscribed. With no account the contact
     * is created unattached (the hidden local book); the local book is
     * never offered as an explicit target.
     */
    private void addContact() {
        List<BookEntry> books = new ArrayList<>();
        BookEntry local = null;
        for (BookEntry entry : base.loadSubscribedAddressbooks()) {
            if (LocalBook.is(entry.accountEmail)) {
                local = entry;
            } else {
                books.add(entry);
            }
        }

        // No real account: born unattached, in the hidden local book.
        if (books.isEmpty()) {
            if (local != null) {
                openNewContact(local.book, local.accountEmail);
            }
            return;
        }
        if (books.size() == 1) {
            openNewContact(books.get(0).book, books.get(0).accountEmail);
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.save_target_title)
                .setItems(
                        bookLabels(books),
                        (dialog, which) ->
                                openNewContact(
                                        books.get(which).book,
                                        books.get(which).accountEmail))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The subscribed addressbooks the group does not already live in. */
    private List<BookEntry> booksOutside(Group group) {
        java.util.Set<String> current = new java.util.HashSet<>();
        for (Entry entry : group.replicas) {
            current.add(entry.book.url);
        }

        List<BookEntry> books = new ArrayList<>();
        for (BookEntry entry : base.loadSubscribedAddressbooks()) {
            if (!current.contains(entry.book.url)) {
                books.add(entry);
            }
        }
        return books;
    }

    /** One two-line label per addressbook of a picker dialog. */
    private CharSequence[] bookLabels(List<BookEntry> books) {
        CharSequence[] labels = new CharSequence[books.size()];
        for (int index = 0; index < books.size(); index++) {
            BookEntry entry = books.get(index);
            // The local book has no account email, so it shows on one line.
            labels[index] =
                    LocalBook.is(entry.accountEmail)
                            ? getString(R.string.local_book)
                            : bookLabel(entry.book.name, entry.accountEmail);
        }
        return labels;
    }

    /**
     * A two-line addressbook label: the name at full size over the
     * account email, diminished and secondary (same styling as the
     * auth config options), so long names and emails never wrap into
     * one unreadable line.
     */
    private CharSequence bookLabel(String name, String email) {
        android.text.SpannableString label = new android.text.SpannableString(name + "\n" + email);
        int start = name.length() + 1;
        label.setSpan(
                new android.text.style.RelativeSizeSpan(0.8f),
                start,
                label.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(
                new android.text.style.ForegroundColorSpan(
                        resolveColor(android.R.attr.textColorSecondary)),
                start,
                label.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return label;
    }

    /** Stages a copied card into the target addressbook. */
    private void createCopy(BookEntry target, AccountEntry account, String id, String vcard) {
        String key = ContactPool.cardKey(account.account, target.book.url, id);

        // Google creates land in myContacts; the group membership
        // pushes right after the create (the key rename of confirmPush
        // carries it over).
        if (CardamumClient.isGoogle(account.account) && !"myContacts".equals(target.book.id)) {
            base.stageMembership(target.accountEmail, key, target.book.url, true);
        }
        base.saveLocal(target.accountEmail, key, target.book.url, new Card(id, null, null, vcard));
    }

    /** The survivor chooser over any replica list, then the merge. */
    void mergeReplicas(List<Entry> replicas) {
        if (pool.distinctRefs(replicas).size() < 2) {
            return;
        }

        // The first replica of each addressbook is its candidate
        // survivor; one book means there is nothing to choose.
        Map<String, Entry> byBook = new java.util.LinkedHashMap<>();
        for (Entry entry : replicas) {
            byBook.putIfAbsent(entry.book.url, entry);
        }

        List<Entry> candidates = new ArrayList<>(byBook.values());
        if (candidates.size() == 1) {
            performMerge(replicas, candidates.get(0));
            return;
        }

        CharSequence[] labels = new CharSequence[candidates.size()];
        for (int index = 0; index < candidates.size(); index++) {
            Entry entry = candidates.get(index);
            labels[index] = bookLabel(entry.book.name, entry.accountEmail);
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.merge_target_title)
                .setItems(
                        labels,
                        (dialog, which) -> performMerge(replicas, candidates.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Merges into the survivor: a straight absorb when every card
     * already carries the same content (the cards of one merged
     * contact, nothing to reconcile), the merge form otherwise.
     */
    private void performMerge(List<Entry> replicas, Entry survivor) {
        java.util.Set<String> hashes = new java.util.HashSet<>();
        for (Entry entry : replicas) {
            hashes.add(entry.hash);
        }

        if (hashes.size() == 1) {
            mergeDirect(replicas, survivor);
        } else {
            openMergeForm(replicas, survivor);
        }
    }

    /** Absorbs identical cards into the survivor, no form needed. */
    private void mergeDirect(List<Entry> replicas, Entry survivor) {
        contactsList.exitSelection();

        String survivorRef = pool.replicaRef(survivor);
        java.util.Set<String> removed = new java.util.HashSet<>();
        for (Entry entry : replicas) {
            String ref = pool.replicaRef(entry);
            if (ref.equals(survivorRef) || !removed.add(ref)) {
                continue;
            }
            AccountEntry owner = accountFor(entry.accountEmail);
            if (owner == null) {
                continue;
            }
            base.markDeleted(
                    entry.accountEmail,
                    ContactPool.cardKey(owner.account, entry.book.url, entry.card.id),
                    entry.card);
        }

        toast(getString(R.string.merge_done));
        reloadContacts();
    }

    /** Opens the merge form with a surviving replica armed. */
    private void openMergeForm(List<Entry> replicas, Entry survivor) {
        contactsList.exitSelection();
        if (openMerged(replicas)) {
            edit.mergeSurvivor = survivor;
        }
    }

    // ---- Contact screen -----------------------------------------------------

    /** Opens the edit form on a fresh card in the target addressbook. */
    private void openNewContact(Addressbook book, String accountEmail) {
        edit = new EditSession();
        edit.book = book;
        edit.accountEmail = accountEmail;
        edit.vcard = newVcard();
        edit.title = getString(R.string.contact_new);
        edit.advancedAvailable = true;

        form.load(null, null, null);
        show(PANEL_CONTACT);
    }

    /**
     * Opens the edit form on a merged contact: the single document when
     * the replicas agree, their union with per-field alternative chips
     * when they diverge. Saving stages the form onto every replica.
     * Returns false when the documents could not be read.
     */
    boolean openMerged(List<Entry> replicas) {
        Entry primary = replicas.get(0);
        edit = new EditSession();
        edit.book = primary.book;
        edit.accountEmail = primary.accountEmail;
        edit.card = primary.card;
        edit.replicas = new ArrayList<>(replicas);

        // The distinct documents behind the group: one per normalized
        // content hash. One document means a plain edit; several go
        // through the bridge's union merge.
        List<String> docs = new ArrayList<>();
        java.util.Set<String> hashes = new java.util.HashSet<>();
        for (Entry entry : replicas) {
            if (hashes.add(entry.hash)) {
                docs.add(entry.card.vcard);
            }
        }

        JSONObject model;
        JSONObject alternatives = null;
        org.json.JSONArray changed = null;
        try {
            if (docs.size() == 1) {
                edit.vcard = primary.card.vcard;
                model = Cards.projectCard(primary.card);
            } else {
                JSONObject merged = Cards.mergeCards(docs);
                edit.vcard = merged.optString("vcard");
                model = merged.optJSONObject("model");
                alternatives = merged.optJSONObject("alternatives");
                // A non-null changed set turns on the form's conflict
                // mode: only the disagreeing fields show.
                changed = merged.optJSONArray("changed");
                if (changed == null) {
                    changed = new org.json.JSONArray();
                }
            }
        } catch (Exception error) {
            showError(error, R.string.contact_open_failed);
            return false;
        }

        edit.title = primary.displayName();
        // Raw lines cannot fan out to several physical documents, so
        // the advanced editor only opens on single-card contacts.
        edit.advancedAvailable = pool.distinctRefs(replicas).size() == 1;

        // A non-null changed set means the union merge diverged: the
        // form opens in conflict mode, the same full editor with the
        // diverged glyph on the disagreeing rows.
        form.load(model, alternatives, changed);
        show(PANEL_CONTACT);
        return true;
    }

    private void setUpContactPanel() {
        findViewById(R.id.contact_books).setOnClickListener(view -> manageBooks());
        findViewById(R.id.contact_add_field).setOnClickListener(view -> form.addField());
    }

    /**
     * Leaves the editor without saving. The list still reloads: the
     * addressbooks dialog stages copies and removals it must reflect.
     * An unsaved Merge is abandoned.
     */
    private void closeContact() {
        edit = new EditSession();
        reloadContacts();
        showBack(PANEL_CONTACTS);
    }

    // ---- Advanced editor ----------------------------------------------------

    /**
     * Opens the raw-property editor on the working document, the form's
     * current state baked in first so both views agree. Only offered on
     * single-card contacts (and new ones): raw lines cannot fan out to
     * several physical documents.
     */
    private void openAdvanced() {
        try {
            String working = edit.advancedVcard != null ? edit.advancedVcard : edit.vcard;
            edit.advancedVcard = Cards.applyCard(working, form.collect());
            renderAdvanced();
        } catch (Exception error) {
            showError(error, R.string.contact_open_failed);
            return;
        }
        show(PANEL_ADVANCED);
    }

    /** Back to the form, rebuilt from the edited document. */
    private void closeAdvanced() {
        try {
            form.load(Cards.projectCard(edit.advancedVcard), null, null);
        } catch (Exception error) {
            showError(error, R.string.contact_open_failed);
        }
        showBack(PANEL_CONTACT);
    }

    /** Rebuilds the property rows and the closing source block. */
    private void renderAdvanced() {
        LinearLayout container = findViewById(R.id.advanced_container);
        container.removeAllViews();

        org.json.JSONArray props = Cards.cardProps(edit.advancedVcard);
        for (int index = 0; props != null && index < props.length(); index++) {
            int at = index;
            JSONObject prop = props.optJSONObject(index);
            container.addView(
                    propertyRow(
                            prop.optString("name"),
                            prop.optString("line"),
                            () -> advancedDialog(at, prop)));
        }

        // Same style as the edit form's closing "Add <field>" rows.
        android.widget.ImageView addIcon = new android.widget.ImageView(this);
        addIcon.setImageResource(R.drawable.ic_add);
        addIcon.setImageTintList(
                android.content.res.ColorStateList.valueOf(
                        resolveColor(android.R.attr.textColorPrimary)));

        TextView add = new TextView(this);
        add.setText(R.string.advanced_add);
        add.setTextColor(resolveColor(android.R.attr.textColorPrimary));
        add.setTextSize(14);
        LinearLayout.LayoutParams addParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        addParams.setMarginStart(dp(8));

        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        addRow.setPadding(dp(16), dp(12), dp(16), dp(12));
        addRow.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackground));
        addRow.setOnClickListener(view -> advancedDialog(-1, new JSONObject()));
        addRow.addView(addIcon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        addRow.addView(add, addParams);
        container.addView(addRow);

        TextView header = new TextView(this);
        header.setText(R.string.advanced_source);
        header.setTextColor(resolveColor(android.R.attr.colorAccent));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        header.setPadding(dp(16), dp(24), dp(16), dp(4));
        container.addView(header);

        // Tapping the source block opens the free-hand source editor.
        TextView source = new TextView(this);
        source.setText(edit.advancedVcard);
        source.setTextColor(resolveColor(android.R.attr.textColorSecondary));
        source.setTypeface(android.graphics.Typeface.MONOSPACE);
        source.setTextSize(12);
        source.setPadding(dp(16), 0, dp(16), dp(12));
        source.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackground));
        source.setOnClickListener(view -> openSource());
        container.addView(source);
    }

    /** A tappable property row: the name over the whole raw line. */
    private View propertyRow(String title, String line, Runnable onClick) {
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(resolveColor(android.R.attr.textColorPrimary));
        titleView.setTextSize(15);

        TextView lineView = new TextView(this);
        lineView.setText(line);
        lineView.setTextColor(resolveColor(android.R.attr.textColorSecondary));
        lineView.setTypeface(android.graphics.Typeface.MONOSPACE);
        lineView.setTextSize(12);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackground));
        row.setOnClickListener(view -> onClick.run());
        row.addView(titleView);
        row.addView(lineView);
        return row;
    }

    /**
     * Edits one property as a structured form: its name, one row per
     * parameter (name and comma-separated values, an emptied name
     * drops the parameter), a closing accent row to add one, and the
     * raw value. OK recomposes through the bridge, Remove drops the
     * whole property.
     */
    private void advancedDialog(int index, JSONObject prop) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), 0);

        android.widget.AutoCompleteTextView name =
                registryField(R.string.advanced_prop_name, prop.optString("name"), KNOWN_PROPS);
        name.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        content.addView(name);

        LinearLayout params = new LinearLayout(this);
        params.setOrientation(LinearLayout.VERTICAL);
        org.json.JSONArray existing = prop.optJSONArray("params");
        for (int at = 0; existing != null && at < existing.length(); at++) {
            JSONObject param = existing.optJSONObject(at);
            org.json.JSONArray values = param.optJSONArray("values");
            List<String> parts = new ArrayList<>();
            for (int v = 0; values != null && v < values.length(); v++) {
                parts.add(values.optString(v));
            }
            params.addView(paramRow(param.optString("name"), String.join(",", parts)));
        }
        content.addView(params);

        TextView addParam = new TextView(this);
        addParam.setText(R.string.advanced_add_param);
        addParam.setTextColor(resolveColor(android.R.attr.colorAccent));
        addParam.setTextSize(14);
        addParam.setPadding(0, dp(8), 0, dp(8));
        addParam.setOnClickListener(view -> params.addView(paramRow("", "")));
        content.addView(addParam);

        // The value area shapes itself from the property name: one
        // labeled field per component of a structured value, or one
        // raw field.
        LinearLayout valueArea = new LinearLayout(this);
        valueArea.setOrientation(LinearLayout.VERTICAL);
        buildValueArea(valueArea, prop.optString("name"), prop);
        name.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        buildValueArea(valueArea, s.toString(), prop);
                    }
                });
        content.addView(valueArea);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        AlertDialog.Builder builder =
                new AlertDialog.Builder(this)
                        .setTitle(
                                index < 0
                                        ? getString(R.string.advanced_add)
                                        : prop.optString("name"))
                        .setView(scroll)
                        .setPositiveButton(
                                android.R.string.ok,
                                (dialog, which) ->
                                        applyAdvancedParts(
                                                index, collectProp(name, params, valueArea)))
                        .setNegativeButton(android.R.string.cancel, null);
        if (index >= 0) {
            builder.setNeutralButton(
                    R.string.remove_row,
                    (dialog, which) -> applyAdvancedRaw(index, ""));
        }
        builder.show();
    }

    /** One parameter row: its name next to its comma-joined values. */
    /** RFC 6350 property names, proposed by the advanced editor. */
    private static final String[] KNOWN_PROPS = {
        "ADR", "ANNIVERSARY", "BDAY", "CALADRURI", "CALURI", "CATEGORIES",
        "CLIENTPIDMAP", "EMAIL", "FBURL", "FN", "GENDER", "GEO", "IMPP",
        "KEY", "KIND", "LANG", "LOGO", "MEMBER", "N", "NICKNAME", "NOTE",
        "ORG", "PHOTO", "PRODID", "RELATED", "REV", "ROLE", "SOUND",
        "SOURCE", "TEL", "TITLE", "TZ", "UID", "URL", "VERSION", "XML",
    };

    /** RFC 6350 parameter names, proposed by the advanced editor. */
    private static final String[] KNOWN_PARAMS = {
        "ALTID", "CALSCALE", "GEO", "LABEL", "LANGUAGE", "MEDIATYPE",
        "PID", "PREF", "SORT-AS", "TYPE", "TZ", "VALUE",
    };

    /** RFC 6350 TYPE values (general, TEL and RELATED registries). */
    private static final String[] TYPE_VALUES = {
        "home", "work", "cell", "fax", "voice", "video", "pager", "text",
        "textphone", "contact", "acquaintance", "friend", "met",
        "co-worker", "colleague", "co-resident", "neighbor", "child",
        "parent", "sibling", "spouse", "kin", "muse", "crush", "date",
        "sweetheart", "me", "agent", "emergency",
    };

    /** RFC 6350 VALUE kinds. */
    private static final String[] VALUE_KINDS = {
        "text", "uri", "date", "time", "date-time", "date-and-or-time",
        "timestamp", "boolean", "integer", "float", "utc-offset",
        "language-tag",
    };

    /** The known values of a parameter, empty when free-form. */
    private static String[] paramValues(String param) {
        switch (param.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "TYPE":
                return TYPE_VALUES;
            case "VALUE":
                return VALUE_KINDS;
            case "CALSCALE":
                return new String[] {"gregorian"};
            default:
                return new String[0];
        }
    }

    /** A free-text field proposing the given registry as a dropdown. */
    private android.widget.AutoCompleteTextView registryField(
            int hint, String text, String[] registry) {
        android.widget.AutoCompleteTextView field =
                new android.widget.AutoCompleteTextView(this);
        field.setHint(hint);
        field.setText(text);
        field.setThreshold(1);
        field.setAdapter(
                new android.widget.ArrayAdapter<>(
                        this, android.R.layout.simple_dropdown_item_1line, registry));
        // The dropdown opens on the very first tap: focus gain covers
        // it (a first tap only focuses, the click fires on the second).
        field.setOnClickListener(view -> field.showDropDown());
        field.setOnFocusChangeListener(
                (view, focused) -> {
                    if (focused) {
                        field.post(field::showDropDown);
                    }
                });
        return field;
    }

    private LinearLayout paramRow(String name, String values) {
        android.widget.AutoCompleteTextView nameField =
                registryField(R.string.advanced_param_name, name, KNOWN_PARAMS);
        nameField.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);

        // The values complete per comma-separated token, against the
        // named parameter's own registry (TYPE, VALUE, CALSCALE).
        android.widget.MultiAutoCompleteTextView valuesField =
                new android.widget.MultiAutoCompleteTextView(this);
        valuesField.setHint(R.string.advanced_param_values);
        valuesField.setText(values);
        valuesField.setThreshold(1);
        valuesField.setTokenizer(
                new android.widget.MultiAutoCompleteTextView.CommaTokenizer());
        valuesField.setOnClickListener(view -> valuesField.showDropDown());
        valuesField.setOnFocusChangeListener(
                (view, focused) -> {
                    if (focused) {
                        valuesField.post(valuesField::showDropDown);
                    }
                });
        Runnable retune =
                () ->
                        valuesField.setAdapter(
                                new android.widget.ArrayAdapter<>(
                                        this,
                                        android.R.layout.simple_dropdown_item_1line,
                                        paramValues(nameField.getText().toString())));
        retune.run();
        nameField.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        retune.run();
                    }
                });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        row.addView(
                nameField,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(
                valuesField,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2));
        return row;
    }

    /**
     * Shapes the dialog's value area from the property name: one
     * labeled field per component of a structured value (the labels
     * from the bridge, mirroring the vcard-rs value types), or one raw
     * monospace field. Only rebuilt when the shape actually changes,
     * so typing in the fields survives name keystrokes.
     */
    private void buildValueArea(LinearLayout area, String propName, JSONObject prop) {
        List<String> labels = new ArrayList<>();
        try {
            org.json.JSONArray found = Cards.cardPropLabels(propName.trim());
            for (int index = 0; found != null && index < found.length(); index++) {
                labels.add(found.optString(index));
            }
        } catch (Exception ignored) {
            // An unreadable registry just leaves the raw field.
        }

        String shape = String.join("\u001F", labels);
        if (shape.equals(area.getTag())) {
            return;
        }
        area.setTag(shape);
        area.removeAllViews();

        if (labels.isEmpty()) {
            EditText value = new EditText(this);
            value.setHint(R.string.advanced_value);
            value.setText(prop.optString("value"));
            value.setTypeface(android.graphics.Typeface.MONOSPACE);
            value.setInputType(
                    android.text.InputType.TYPE_CLASS_TEXT
                            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            area.addView(value);
            return;
        }

        org.json.JSONArray existing = prop.optJSONArray("components");
        for (int index = 0; index < labels.size(); index++) {
            EditText component = new EditText(this);
            component.setHint(labels.get(index));
            if (existing != null && index < existing.length()) {
                component.setText(existing.optJSONObject(index).optString("value"));
            }
            area.addView(component);
        }
    }

    /** The structured property the dialog's fields describe. */
    private static JSONObject collectProp(
            EditText name, LinearLayout params, LinearLayout valueArea) {
        try {
            org.json.JSONArray collected = new org.json.JSONArray();
            for (int index = 0; index < params.getChildCount(); index++) {
                LinearLayout row = (LinearLayout) params.getChildAt(index);
                String paramName =
                        ((EditText) row.getChildAt(0)).getText().toString().trim();
                if (paramName.isEmpty()) {
                    continue;
                }
                org.json.JSONArray values = new org.json.JSONArray();
                for (String part :
                        ((EditText) row.getChildAt(1)).getText().toString().split(",")) {
                    if (!part.trim().isEmpty()) {
                        values.put(part.trim());
                    }
                }
                collected.put(
                        new JSONObject().put("name", paramName).put("values", values));
            }

            JSONObject prop =
                    new JSONObject()
                            .put("name", name.getText().toString().trim())
                            .put("params", collected);

            String shape = (String) valueArea.getTag();
            if (shape == null || shape.isEmpty()) {
                prop.put("value", ((EditText) valueArea.getChildAt(0)).getText().toString());
            } else {
                String[] labels = shape.split("\u001F", -1);
                org.json.JSONArray components = new org.json.JSONArray();
                for (int index = 0; index < valueArea.getChildCount(); index++) {
                    components.put(
                            new JSONObject()
                                    .put("name", labels[index])
                                    .put(
                                            "value",
                                            ((EditText) valueArea.getChildAt(index))
                                                    .getText()
                                                    .toString()));
                }
                prop.put("components", components);
            }
            return prop;
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    private void applyAdvancedParts(int index, JSONObject prop) {
        try {
            edit.advancedVcard = Cards.cardSetPropParts(edit.advancedVcard, index, prop);
            edit.advancedDirty = true;
            renderAdvanced();
        } catch (Exception error) {
            showError(error, R.string.advanced_invalid);
        }
    }

    /** A raw rewrite: only removal uses it (a blank line drops). */
    private void applyAdvancedRaw(int index, String line) {
        try {
            edit.advancedVcard = Cards.cardSetProp(edit.advancedVcard, index, line);
            edit.advancedDirty = true;
            renderAdvanced();
        } catch (Exception error) {
            showError(error, R.string.advanced_invalid);
        }
    }

    /** The advanced editor's second level: the free-hand source. */
    private void openSource() {
        ((EditText) findViewById(R.id.source_input)).setText(edit.advancedVcard);
        show(PANEL_SOURCE);
    }

    /** Applies the hand-edited source (it must reparse) and returns. */
    private void applySource() {
        try {
            edit.advancedVcard =
                    Cards.cardSource(
                            ((EditText) findViewById(R.id.source_input)).getText().toString());
            edit.advancedDirty = true;
            renderAdvanced();
        } catch (Exception error) {
            showError(error, R.string.advanced_invalid);
            return;
        }
        showBack(PANEL_ADVANCED);
    }

    /**
     * The addressbooks dialog of the open contact, the one placement
     * gesture: checked means the contact exists there, through any of
     * its cards. Only real addressbooks are listed; unchecking every one
     * is allowed and leaves the contact unattached, living in the hidden
     * local book (it shows muted in the list, Delete is still the way to
     * remove it). The dialog only records the desired state (reopening
     * shows it); everything applies on SAVE: a staged membership when the
     * contact already has a card on that account-level backend, a copied
     * card sharing the vCard UID anywhere else, a membership removal or
     * the card's staged delete on uncheck.
     */
    private void manageBooks() {
        if (edit.replicas.isEmpty()) {
            return;
        }

        Map<String, Entry> replicaByBook = new HashMap<>();
        for (Entry entry : edit.replicas) {
            replicaByBook.put(entry.book.url, entry);
        }

        // The hidden local book is not listed; it is the fallback home
        // when nothing is checked.
        List<BookEntry> books = new ArrayList<>();
        for (BookEntry entry : base.loadSubscribedAddressbooks()) {
            if (!LocalBook.is(entry.accountEmail)) {
                books.add(entry);
            }
        }
        boolean[] checked = new boolean[books.size()];
        for (int index = 0; index < books.size(); index++) {
            String url = books.get(index).book.url;
            Boolean pending = edit.pendingBookState == null ? null : edit.pendingBookState.get(url);
            checked[index] = pending != null ? pending : replicaByBook.containsKey(url);
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.manage_books_title)
                .setMultiChoiceItems(
                        bookLabels(books),
                        checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(
                        android.R.string.ok,
                        (dialog, which) -> {
                            edit.pendingBookState = new HashMap<>();
                            boolean anyChecked = false;
                            for (int index = 0; index < books.size(); index++) {
                                edit.pendingBookState.put(books.get(index).book.url, checked[index]);
                                anyChecked |= checked[index];
                            }
                            // No addressbook checked: the contact is
                            // unattached, kept only in the hidden local book.
                            edit.pendingBookState.put(LocalBook.URL, !anyChecked);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Applies the addressbooks dialog's desired state on save: staged
     * memberships, copies (carrying the just-saved content), removals.
     */
    private void applyBookState(String vcard) {
        Map<String, Boolean> desired = edit.pendingBookState;
        edit.pendingBookState = null;
        if (desired == null) {
            return;
        }

        Map<String, Entry> replicaByBook = new HashMap<>();
        for (Entry entry : edit.replicas) {
            replicaByBook.put(entry.book.url, entry);
        }

        for (BookEntry target : base.loadSubscribedAddressbooks()) {
            Boolean wanted = desired.get(target.book.url);
            if (wanted == null || wanted == replicaByBook.containsKey(target.book.url)) {
                continue;
            }
            AccountEntry account = accountFor(target.accountEmail);
            if (account == null) {
                continue;
            }

            if (wanted) {
                addToBook(target, account, vcard);
            } else {
                removeFromBook(target, replicaByBook);
            }
        }
    }

    /**
     * Adds the contact to an addressbook: a staged membership when the
     * contact already has a card on that account-level backend, a
     * copied card sharing the vCard UID anywhere else.
     */
    private void addToBook(BookEntry target, AccountEntry account, String vcard) {
        if (CardamumClient.isAccountLevel(account.account)) {
            for (Entry entry : edit.replicas) {
                if (entry.accountEmail.equals(target.accountEmail)) {
                    base.stageMembership(
                            entry.accountEmail,
                            ContactPool.cardKey(account.account, entry.book.url, entry.card.id),
                            target.book.url,
                            true);
                    return;
                }
            }
        }

        String uid = cardIndex(vcard).optString("uid");
        String id = uid.isEmpty() ? UUID.randomUUID().toString() : uid;
        createCopy(target, account, id, vcard);
    }

    /**
     * Removes the contact from an addressbook: a staged membership
     * removal when its card keeps other books, a staged delete of the
     * card otherwise.
     */
    private void removeFromBook(BookEntry target, Map<String, Entry> replicaByBook) {
        Entry replica = replicaByBook.get(target.book.url);
        if (replica == null) {
            return;
        }
        AccountEntry owner = accountFor(replica.accountEmail);
        if (owner == null) {
            return;
        }
        String replicaKey = ContactPool.cardKey(owner.account, replica.book.url, replica.card.id);

        if (CardamumClient.isAccountLevel(owner.account)
                && base.loadMemberships(replica.accountEmail, replicaKey).size() > 1) {
            // The card stays, only this membership goes.
            base.stageMembership(replica.accountEmail, replicaKey, target.book.url, false);
        } else {
            // Last place of this card: the card goes.
            base.markDeleted(replica.accountEmail, replicaKey, replica.card);
        }
    }

    /**
     * Stages the edited contact in the base (offline first: nothing
     * touches the network until the next sync pushes it) and returns to
     * the list. The form model applies to every replica's own document
     * (docs/merged-view.md): each keeps its UID and unmanaged
     * properties while the managed content converges; replicas already
     * carrying the resulting content are skipped.
     */
    private void saveContact() {
        String birthday = form.birthday();
        if (!birthday.isEmpty() && !birthday.matches("\\d{4}-\\d{2}-\\d{2}")) {
            toast(getString(R.string.invalid_birthday));
            return;
        }
        String anniversary = form.anniversary();
        if (!anniversary.isEmpty() && !anniversary.matches("\\d{4}-\\d{2}-\\d{2}")) {
            toast(getString(R.string.invalid_anniversary));
            return;
        }

        try {
            JSONObject model = form.collect();

            if (edit.card == null) {
                AccountEntry account = accountFor(edit.accountEmail);
                if (account == null) {
                    // The account was deleted while the editor was open.
                    toast(getString(R.string.save_failed));
                    return;
                }
                String source = edit.advancedVcard != null ? edit.advancedVcard : edit.vcard;
                String vcard = Cards.applyCard(source, model);
                String uid = cardIndex(vcard).optString("uid");
                String id = uid.isEmpty() ? UUID.randomUUID().toString() : uid;
                base.saveLocal(
                        edit.accountEmail,
                        ContactPool.cardKey(account.account, edit.book.url, id),
                        edit.book.url,
                        new Card(id, null, null, vcard));
            } else if (edit.resolvingConflict) {
                saveConflictResolution(model);
            } else if (edit.mergeSurvivor != null) {
                saveMerge(model);
            } else {
                saveFanOut(model);
            }

            // The addressbooks dialog's choices apply here too, the
            // copies carrying the just-saved content.
            if (edit.card != null && edit.pendingBookState != null) {
                applyBookState(Cards.applyCard(edit.card.vcard, model));
            }
        } catch (Exception error) {
            showError(error, R.string.save_failed);
            return;
        }

        reloadContacts();
        showBack(PANEL_CONTACTS);
    }

    /**
     * The fan-out save: the form model applies to every card's own
     * document, each staged through the engine (editing a conflicted
     * replica resolves it).
     */
    private void saveFanOut(JSONObject model) throws JSONException {
        OfflineEngine engine = new OfflineEngine(base, client, null, null);
        java.util.Set<String> staged = new java.util.HashSet<>();

        for (Entry entry : edit.replicas) {
            // An m:n replica appears once per book; stage it once.
            if (!staged.add(pool.replicaRef(entry))) {
                continue;
            }

            // The advanced editor only opens on single-card contacts,
            // so its document stands in for the card's; a raw edit must
            // stage even when the managed-content hash is unchanged.
            String source = edit.advancedVcard != null ? edit.advancedVcard : entry.card.vcard;
            String vcard = Cards.applyCard(source, model);
            if (!edit.advancedDirty && cardIndex(vcard).optString("hash").equals(entry.hash)) {
                continue;
            }
            engine.mutateEdit(
                    entry.book.url,
                    CardStore.rowHandle(entry.book.url, entry.card.uri, entry.card.id),
                    vcard);
        }
    }

    /**
     * The conflict-resolution save: the form's picks apply onto the
     * captured three-way merge (so remote's non-conflicting changes and
     * unmanaged data survive), staged onto the one conflicted replica.
     * Editing the conflicted placement resolves it in the engine, and the
     * next sync pushes it guarded on the observed remote revision.
     */
    private void saveConflictResolution(JSONObject model) throws JSONException {
        Entry replica = edit.replicas.get(0);
        String resolved = Cards.applyCard(edit.vcard, model);
        new OfflineEngine(base, client, null, null)
                .mutateEdit(
                        replica.book.url,
                        CardStore.rowHandle(replica.book.url, replica.card.uri, replica.card.id),
                        resolved);
    }

    /**
     * The merge save: the form model applies to the surviving card and
     * every other card behind the form stages a delete.
     */
    private void saveMerge(JSONObject model) throws JSONException {
        Entry survivor = edit.mergeSurvivor;
        edit.mergeSurvivor = null;

        String vcard = Cards.applyCard(survivor.card.vcard, model);
        if (!cardIndex(vcard).optString("hash").equals(survivor.hash)) {
            new OfflineEngine(base, client, null, null)
                    .mutateEdit(
                            survivor.book.url,
                            CardStore.rowHandle(
                                    survivor.book.url, survivor.card.uri, survivor.card.id),
                            vcard);
        }

        String survivorRef = pool.replicaRef(survivor);
        java.util.Set<String> removed = new java.util.HashSet<>();
        for (Entry entry : edit.replicas) {
            String ref = pool.replicaRef(entry);
            if (ref.equals(survivorRef) || !removed.add(ref)) {
                continue;
            }
            AccountEntry owner = accountFor(entry.accountEmail);
            if (owner == null) {
                continue;
            }
            base.markDeleted(
                    entry.accountEmail,
                    ContactPool.cardKey(owner.account, entry.book.url, entry.card.id),
                    entry.card);
        }

        toast(getString(R.string.merge_done));
    }

    /** Rebuilds the contacts list from the base (staged edits included). */
    void reloadContacts() {
        contactsList.reload();
    }

    // ---- vCard helpers ------------------------------------------------------

    private static String newVcard() {
        return "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:" + UUID.randomUUID() + "\r\nEND:VCARD\r\n";
    }

    /**
     * The bridge's index of a vCard for one-off lookups (name, uid);
     * the hot paths read the same index from the store's columns
     * instead. Empty on a parse failure.
     */
    private JSONObject cardIndex(String vcard) {
        try {
            return Cards.indexCard(vcard);
        } catch (Exception error) {
            Log.w("cardamum", "card index failed", error);
            return new JSONObject();
        }
    }

    // ---- Utils --------------------------------------------------------------

    /** Navigates forward: the panels slide in from the right. */
    private void show(int panel) {
        show(panel, false);
    }

    /** Navigates back: the panels slide in from the left. */
    private void showBack(int panel) {
        show(panel, true);
    }

    private void show(int panel, boolean back) {
        show(
                panel,
                back ? R.anim.slide_in_left : R.anim.slide_in_right,
                back ? R.anim.slide_out_right : R.anim.slide_out_left);
    }

    private void show(int panel, int inAnim, int outAnim) {
        hideKeyboard();
        flipper.setInAnimation(this, inAnim);
        flipper.setOutAnimation(this, outAnim);
        flipper.setDisplayedChild(panel);
        screen = panel;
        applyChrome(panel);
    }

    /**
     * Raises a whole-frame overlay (its own bar included) over the
     * current screen with a fade and a slight zoom. What is underneath
     * stays put.
     */
    private void openOverlay(int panel) {
        hideKeyboard();
        View overlay = overlayOf(panel);
        overlay.setVisibility(View.VISIBLE);
        overlay.startAnimation(
                android.view.animation.AnimationUtils.loadAnimation(this, R.anim.fade_zoom_in));
        screen = panel;
        applyChrome(panel);
    }

    /** Fades the overlay back out (zooming slightly away), then hides it. */
    private void closeOverlay(int panel) {
        hideKeyboard();
        View overlay = overlayOf(panel);
        android.view.animation.Animation exit =
                android.view.animation.AnimationUtils.loadAnimation(this, R.anim.fade_zoom_out);
        exit.setAnimationListener(
                new android.view.animation.Animation.AnimationListener() {
                    @Override
                    public void onAnimationStart(android.view.animation.Animation animation) {}

                    @Override
                    public void onAnimationRepeat(android.view.animation.Animation animation) {}

                    @Override
                    public void onAnimationEnd(android.view.animation.Animation animation) {
                        // Invisible, not gone, so the view stays
                        // measured (see the layout); the finished
                        // animation clears with it, since an animated
                        // invisible view still catches touches.
                        overlay.clearAnimation();
                        overlay.setVisibility(View.INVISIBLE);
                    }
                });
        overlay.startAnimation(exit);
    }

    /** The whole-frame overlay view behind an overlay panel id. */
    private View overlayOf(int panel) {
        return findViewById(panel == PANEL_ACCOUNT ? R.id.overlay_account : R.id.overlay_auth);
    }

    /**
     * One screen's share of the persistent chrome: whether it carries
     * its own bar (the overlays do), how the shared bar and FAB
     * configure, what the FAB does, and what the two backs do. One
     * table entry per screen ({@link #setUpScreens}) replaces the four
     * parallel switches that each used to carry every screen.
     */
    private static final class Screen {
        /** True when the screen brings its own bar (the overlays). */
        boolean ownBar;

        /** Configures the shared chrome, after the common reset. */
        Runnable chrome = () -> {};

        /** The shared FAB's action. */
        Runnable fab = () -> {};

        /** The main bar's back arrow (shown by the screen's chrome). */
        Runnable barBack = () -> {};

        /** The system back; null falls through to the framework. */
        Runnable systemBack;
    }

    /** The screens by panel id (flipper children 0-3, overlays 5-6,
     *  matching the include order in activity_main.xml). */
    private final Map<Integer, Screen> screens = new HashMap<>();

    /** Fills the screen table; every entry reads like one screen's card. */
    private void setUpScreens() {
        Screen contacts = new Screen();
        contacts.chrome =
                () -> {
                    android.widget.ImageButton fab = findViewById(R.id.fab);
                    fab.setImageResource(R.drawable.ic_person_add);
                    fab.setContentDescription(getString(R.string.contacts_add));
                    fab.setVisibility(View.VISIBLE);
                    contactsList.updateSelectionUi();
                };
        contacts.fab = this::addContact;
        contacts.systemBack =
                () -> {
                    if (contactsList.isSearchOpen()) {
                        contactsList.closeSearch();
                    } else {
                        // The merged list is the app's root itself.
                        MainActivity.super.onBackPressed();
                    }
                };
        screens.put(PANEL_CONTACTS, contacts);

        Screen contact = new Screen();
        contact.chrome =
                () -> {
                    ((TextView) findViewById(R.id.bar_title)).setText(edit.title);
                    findViewById(R.id.bar_back).setVisibility(View.VISIBLE);
                    // Placing the contact in addressbooks needs a real
                    // account to target; with only the local book, or while
                    // resolving a conflict, the button hides.
                    findViewById(R.id.contact_books)
                            .setVisibility(
                                    edit.card != null
                                                    && hasRealAccount()
                                                    && !edit.resolvingConflict
                                            ? View.VISIBLE
                                            : View.GONE);
                    // Add-field lives in the bar, in conflict mode like in a
                    // plain edit: the conflict form is the full editor.
                    findViewById(R.id.contact_add_field).setVisibility(View.VISIBLE);
                    // The FAB validates (check); while diverging rows await
                    // review it turns into the error disc instead
                    // (updateSaveEnabled).
                    android.widget.ImageButton fab = findViewById(R.id.fab);
                    fab.setImageResource(R.drawable.ic_check);
                    fab.setContentDescription(getString(R.string.contact_save));
                    fab.setVisibility(View.VISIBLE);
                    updateSaveEnabled();
                };
        contact.fab = this::saveContact;
        contact.barBack = this::closeContact;
        contact.systemBack = this::closeContact;
        screens.put(PANEL_CONTACT, contact);

        Screen advanced = new Screen();
        advanced.chrome =
                () -> {
                    ((TextView) findViewById(R.id.bar_title)).setText(R.string.advanced_title);
                    findViewById(R.id.bar_back).setVisibility(View.VISIBLE);
                    findViewById(R.id.fab).setVisibility(View.GONE);
                };
        advanced.barBack = this::closeAdvanced;
        advanced.systemBack = this::closeAdvanced;
        screens.put(PANEL_ADVANCED, advanced);

        Screen source = new Screen();
        source.chrome =
                () -> {
                    ((TextView) findViewById(R.id.bar_title)).setText(R.string.advanced_source);
                    findViewById(R.id.bar_back).setVisibility(View.VISIBLE);
                    android.widget.ImageButton fab = findViewById(R.id.fab);
                    fab.setImageResource(R.drawable.ic_check);
                    fab.setContentDescription(getString(R.string.contact_save));
                    fab.setVisibility(View.VISIBLE);
                };
        source.fab = this::applySource;
        source.barBack = () -> showBack(PANEL_ADVANCED);
        source.systemBack = () -> showBack(PANEL_ADVANCED);
        screens.put(PANEL_SOURCE, source);

        Screen auth = new Screen();
        auth.ownBar = true;
        auth.chrome =
                () -> {
                    // The shared FAB is the continue action through the flow.
                    android.widget.ImageButton fab = findViewById(R.id.fab);
                    fab.setImageResource(R.drawable.ic_arrow_forward);
                    fab.setContentDescription(getString(R.string.email_submit));
                    fab.setVisibility(View.VISIBLE);
                    setFabEnabled(
                            R.id.fab,
                            onboarding.stepReady(authFlipper.getDisplayedChild()));
                    applyAuthChrome();
                };
        auth.fab = () -> onboarding.continueStep(authFlipper.getDisplayedChild());
        auth.systemBack = this::authBack;
        screens.put(PANEL_AUTH, auth);

        Screen account = new Screen();
        account.ownBar = true;
        // The settings overlay draws above the drawer, where the shared
        // FAB cannot follow; it carries its own save FAB. Its system
        // back peels the overlay before anything else (onBackPressed).
        account.chrome = () -> findViewById(R.id.fab).setVisibility(View.GONE);
        account.fab = this::saveAccountSettings;
        screens.put(PANEL_ACCOUNT, account);
    }

    /**
     * Configures the persistent app bar and FAB for the screen: every
     * chrome element goes off, then the screen's own set comes back
     * (the contacts screen delegates to its selection/search state).
     */
    private void applyChrome(int panel) {
        android.widget.ImageButton fab = findViewById(R.id.fab);

        // Every screen change clears any lingering FAB loader (an auth
        // step that navigated on while its loader was up) and re-enables
        // the disc: the icon comes back, the spinner goes, the dim from
        // a disabled auth step lifts, and the error tones of a left-open
        // conflict form reset. The auth entry re-disables per step,
        // updateSaveEnabled re-applies the error state.
        fab.setImageAlpha(255);
        fab.setBackgroundTintList(null);
        fab.setImageTintList(android.content.res.ColorStateList.valueOf(accentContrast()));
        findViewById(R.id.fab_progress).setVisibility(View.GONE);
        setFabEnabled(R.id.fab, true);

        Screen entry = screens.get(panel);
        if (entry == null) {
            return;
        }

        // The overlays carry their own bars; only the FAB is shared.
        if (!entry.ownBar) {
            for (int id :
                    new int[] {
                        R.id.bar_back,
                        R.id.contacts_menu,
                        R.id.contacts_close,
                        R.id.contacts_search_pill,
                        R.id.contacts_search_close,
                        R.id.contacts_search,
                        R.id.contacts_birthdays,
                        R.id.contacts_duplicates,
                        R.id.contacts_merge,
                        R.id.contacts_delete,
                        R.id.contacts_select_all_slot,
                        R.id.contacts_more_slot,
                        R.id.contact_advanced,
                        R.id.contact_books,
                        R.id.contact_add_field,
                    }) {
                findViewById(id).setVisibility(View.GONE);
            }
            findViewById(R.id.bar_title).setVisibility(View.VISIBLE);
            findViewById(R.id.contacts_bar_spacer).setVisibility(View.VISIBLE);
        }

        entry.chrome.run();
    }

    /** The shared FAB's action, per screen. */
    private void onFabClick() {
        Screen entry = screens.get(screen);
        if (entry != null) {
            entry.fab.run();
        }
    }

    /** The main bar's back arrow, per screen (overlays have their own). */
    private void onBarBack() {
        Screen entry = screens.get(screen);
        if (entry != null) {
            entry.barBack.run();
        }
    }

    /** Resolves a theme attribute to its referenced resource id. */
    private int resolveAttr(int attr) {
        return ui.resolveAttr(attr);
    }

    /** Resolves a theme colour attribute to an ARGB int. */
    private int resolveColor(int attr) {
        return ui.resolveColor(attr);
    }

    /** Dismisses the soft keyboard, e.g. when leaving the edit form. */
    void hideKeyboard() {
        // Fall back to the flipper's window token when nothing holds
        // the focus (it is the same window either way).
        View focus = getCurrentFocus();
        View anchor = focus != null ? focus : flipper;
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(anchor.getWindowToken(), 0);
        if (focus != null) {
            focus.clearFocus();
        }
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception error) {
            return url;
        }
    }

    private String message(Exception error, int fallback) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? getString(fallback) : message;
    }

    void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /**
     * Posts a UI continuation that only runs while the activity is
     * alive: the io executor's sync outcomes land here, and one
     * arriving after a rotation or a finish would raise dialogs
     * against a dead window (BadTokenException).
     */
    void postAlive(Runnable action) {
        main.post(
                () -> {
                    if (!isDestroyed()) {
                        action.run();
                    }
                });
    }

    /**
     * Shows an error in a dismissible dialog rather than a toast: the
     * full (often long, e.g. a server body) message stays on screen and
     * scrolls until acknowledged.
     */
    void showError(Exception error, int fallback) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.error_title)
                .setMessage(message(error, fallback))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private int dp(int value) {
        return ui.dp(value);
    }

    /** A dimen resource in pixels (the shared item_* metrics). */
    private int dimen(int resId) {
        return getResources().getDimensionPixelSize(resId);
    }

    /** Applies the shared item text size (scales with the font setting). */
    private void itemText(TextView view) {
        view.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.item_text));
    }

    /**
     * Draws under the system bars (edge-to-edge is enforced for apps
     * targeting API 35) and pushes the chrome back in with the bar
     * insets: the top inset pads the surface app bars, so the status
     * bar area takes their colour as one bar; the bottom inset lifts the
     * FAB and adds to each list's FAB clearance, so nothing hides under
     * the navigation bar or the keyboard; the side insets pad the window
     * for landscape bars and cutouts. Older devices keep the platform's
     * opaque bars and automatic content inset, so this only runs on API 35
     * and up.
     */
    private void applyEdgeToEdge() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return;
        }

        View root = findViewById(android.R.id.content);
        root.setOnApplyWindowInsetsListener(
                (view, insets) -> {
                    Insets bars =
                            insets.getInsets(
                                    WindowInsets.Type.systemBars()
                                            | WindowInsets.Type.displayCutout());
                    // Edge-to-edge stops adjustResize from resizing for the
                    // keyboard, so the keyboard inset is folded into the
                    // bottom by hand; it overlaps the navigation bar, hence
                    // the max rather than a sum.
                    Insets ime = insets.getInsets(WindowInsets.Type.ime());
                    int bottom = Math.max(bars.bottom, ime.bottom);
                    applyBarInsets(root, bars, bottom);
                    return insets;
                });
        root.requestApplyInsets();
    }

    /** Places the system-bar and keyboard insets on the chrome; see applyEdgeToEdge. */
    private void applyBarInsets(View root, Insets bars, int bottom) {
        root.setPadding(bars.left, root.getPaddingTop(), bars.right, root.getPaddingBottom());

        // Only one bar shows at a time, but all carry the top inset (the
        // drawer header too, since the drawer runs under the status bar).
        padTop(R.id.app_bar, bars.top);
        padTop(R.id.drawer_header, bars.top);
        padTop(R.id.auth_bar, bars.top);
        padTop(R.id.account_bar, bars.top);

        // The base is each view's designed FAB clearance, so re-applying
        // stays idempotent as the listener fires again. The bottom folds
        // in the keyboard, so the FAB and the email field ride above it.
        padBottom(R.id.fab_frame, 0, bottom);
        padBottom(R.id.account_fab_frame, 0, bottom);
        padBottom(R.id.contacts_list, 88, bottom);
        // The drawer's fixed bottom band takes the inset; the scrollable
        // list above it needs none.
        padBottom(R.id.drawer_actions, 0, bottom);
        padBottom(R.id.config_container, 88, bottom);
        padBottom(R.id.books_container, 88, bottom);
        padBottom(R.id.advanced_container, 24, bottom);
        padBottom(R.id.source_input, 16, bottom);
        padBottom(R.id.email_row, 16, bottom);
    }

    private void padTop(int id, int top) {
        View v = findViewById(id);
        v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), v.getPaddingBottom());
    }

    private void padBottom(int id, int baseDp, int inset) {
        View v = findViewById(id);
        v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), dp(baseDp) + inset);
    }
}
