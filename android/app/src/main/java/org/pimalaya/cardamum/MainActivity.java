package org.pimalaya.cardamum;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
    static final int PANEL_CONTACTS = 0;
    static final int PANEL_CONTACT = 1;
    static final int PANEL_ADVANCED = 2;
    static final int PANEL_SOURCE = 3;
    private static final int PANEL_AUTH = 5;
    static final int PANEL_ACCOUNT = 6;

    /** The auth flow's steps, inside its own flipper under one bar. */
    static final int STEP_EMAIL = 0;

    static final int STEP_CONFIG = 1;
    static final int STEP_BOOKS = 2;

    static final int REQUEST_CONTACTS = 1;
    private static final int REQUEST_IMPORT = 2;
    private static final int REQUEST_EXPORT = 3;
    private static final int REQUEST_NOTIFICATIONS = 4;

    /** The shared backend client, the single-thread io executor, the
     *  main-thread handler and the theme helper. */
    final CardamumClient client = new CardamumClient();
    final ExecutorService io = Executors.newSingleThreadExecutor();
    final Handler main = new Handler(Looper.getMainLooper());
    final Ui ui = new Ui(this);

    SecureStore store;
    CardStore base;
    private SyncRunner runner;
    private ViewFlipper flipper;
    private ViewFlipper authFlipper;
    ContactForm form;

    /** Every connected account (multi-account). */
    final List<AccountEntry> accounts = new ArrayList<>();

    /** The connection wizard and its OAuth grants (see onCreate wiring). */
    private OnboardingFlow onboarding;

    private OauthFlow oauth;

    /** The advanced raw-vCard sub-editor over the working document. */
    private final AdvancedEditor advancedEditor = new AdvancedEditor(this);

    /** The contact save fan-out over the working edit session. */
    private final ContactWriter writer = new ContactWriter(this);

    /** The vCard file import and export over the contacts list. */
    private final VcfTransfer vcfTransfer = new VcfTransfer(this);

    /** The full-screen account settings controller over the drawer. */
    private final AccountSettings accountSettings = new AccountSettings(this);

    /** The accounts drawer, opened by the contacts burger. */
    private androidx.drawerlayout.widget.DrawerLayout drawer;

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
    EditSession edit = new EditSession();

    /** The screen currently shown: a flipper panel, or an overlay. */
    int screen = PANEL_CONTACTS;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        drawer = findViewById(R.id.drawer);
        applyEdgeToEdge();

        // NOTE: support prompt (docs/monetization.md) only on a genuine
        // user-initiated open: a fresh start (not a rotation) that is
        // neither an OAuth redirect nor a headless adb sync hook.
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
        // NOTE: the two flows reference each other (grants land back in
        // the wizard), so one side binds late.
        oauth = new OauthFlow(this);
        onboarding = new OnboardingFlow(this, oauth);
        oauth.onConnected = onboarding::connect;
        oauth.onAborted = onboarding::resetConfigContinue;
        flipper = findViewById(R.id.flipper);
        authFlipper = findViewById(R.id.auth_flipper);
        form = new ContactForm(this);
        form.setOnRender(this::updateSaveEnabled);

        setUpScreens();
        setUpEmailPanel();
        contactsList.setUp();
        setUpContactPanel();
        setUpHomePanel();

        setUpFab(R.id.fab);
        findViewById(R.id.fab).setOnClickListener(view -> onFabClick());
        findViewById(R.id.bar_back).setOnClickListener(view -> onBarBack());

        // The modal dialog binds once here and covers every sync entry
        // point through the shared syncing flag.
        observeSync(this::showSyncDialog);

        // NOTE: the built-in local book is always present and
        // subscribed, so the app opens usable with no account:
        // onboarding is never forced.
        base.ensureLocalAddressbook(
                LocalBook.URL, LocalBook.ACCOUNT, LocalBook.ID, getString(R.string.local_book));
        accounts.add(LocalBook.account());
        accounts.addAll(store.loadAll());

        // NOTE: an app update or wiped WorkManager database silently
        // drops periodic work; reconciling on launch converges it back
        // onto the stored books.
        BackgroundSync.reconcile(this, base.loadAllAddressbooks());

        goHome();

        // NOTE: adb-only hooks, so syncs can be driven headlessly:
        // am start ... --ez syncRemote true / --ez syncLocal true
        if (getIntent().getBooleanExtra("syncRemote", false)) {
            syncRemote(true);
        } else if (getIntent().getBooleanExtra("syncLocal", false)) {
            syncLocal();
        }

        // NOTE: an OAuth redirect lands here rather than in onNewIntent
        // when the process died while the user was in the browser (the
        // redirect recreates the activity from scratch).
        oauth.handleRedirect(getIntent());

        // A fresh start with no real account opens the connection flow
        // right away, skipped while an OAuth redirect is processing.
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
            // NOTE: nothing persists before the selection confirms, so
            // the books step steps back like any other.
            showAuthBack(STEP_CONFIG);
        } else if (step == STEP_CONFIG) {
            showAuthBack(STEP_EMAIL);
        } else {
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

        // Diverging rows await review: the disc turns the error tone at
        // full strength, so the state reads as a signal not a dimmed save.
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
        // NOTE: shutdownNow interrupts, but a blocking accept() only
        // wakes on close; without this the OAuth loopback listener would
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
        // The sync loader is modal: back waits with the rest.
        if (syncing) {
            return;
        }
        // The account settings overlay sits above the open drawer, so
        // back peels it off first.
        if (screen == PANEL_ACCOUNT) {
            accountSettings.leave();
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
        // back out onto the root left underneath.
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

    private void setUpEmailPanel() {
        EditText email = findViewById(R.id.email_input);
        findViewById(R.id.auth_back).setOnClickListener(view -> authBack());
        findViewById(R.id.auth_cancel).setOnClickListener(view -> cancelAuth());

        // The continue FAB stays disabled until the field holds
        // something plausible (an email, a server, or a connection URI).
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
        // NOTE: only the start goes flush; the caret-zone end padding
        // stays, so a long entry ellipsizes before running under it.
        spinner.setPadding(0, 0, spinner.getPaddingRight(), 0);
        return spinner;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from the browser without a redirect (a cancelled
        // grant) must not leave the config Continue stuck on its loader;
        // a real redirect re-enters loading right after.
        if (flipper != null
                && screen == PANEL_AUTH
                && authFlipper.getDisplayedChild() == STEP_CONFIG) {
            onboarding.resetConfigContinue();
        }
    }

    private void setUpHomePanel() {
        // Sync slides the drawer shut on its way in, so the modal dialog
        // and its outcome toasts land over the refreshed contacts list.
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

        findViewById(R.id.account_back).setOnClickListener(view -> accountSettings.leave());
        findViewById(R.id.account_delete)
                .setOnClickListener(view -> accountSettings.confirmDeleteCurrent());

        // NOTE: the settings overlay carries its own save FAB, the
        // shared one drawing under the drawer the overlay covers.
        android.widget.ImageButton accountFab = findViewById(R.id.account_fab);
        accountFab.setImageTintList(
                android.content.res.ColorStateList.valueOf(accentContrast()));
        accountFab.setOnClickListener(view -> accountSettings.save());

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
    void reloadHome() {
        // With no real account the drawer shows an empty state, and its
        // sync row is hidden.
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
     * Opens one account's settings screen (delegated to
     * {@link AccountSettings}): the staged switches load from the store,
     * the screen fades in over the drawer it came from, which stays open
     * underneath for the return. The simple pair (activate, cadence) fans
     * out onto every addressbook; Advanced unfolds the per-book sections.
     */
    private void openAccountSettings(String email) {
        accountSettings.open(email);
    }

    /** Runs the pending contacts-sync retry once the permission lands. */
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

    /** Pushes the sync flag to every subscribed element (the modal
     *  loader, the drawer's inert sync row). */
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
            // NOTE: the last sync's store keeps failing addressbooks usable.
            showError(outcome.failure, R.string.sync_failed);
            return;
        }

        // NOTE: toasts queue, so the local report shows first and the
        // remote one takes its place.
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
        // NOTE: only the phone passes need the contacts permission;
        // reconciling the Android accounts runs regardless.
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

    /** The subscribed addressbooks set to mirror into the phone. */
    List<BookEntry> phoneSyncedBooks() {
        return runner.phoneSyncedBooks();
    }

    /**
     * Asks for the notifications permission (Android 13+) when
     * background sync gets enabled, so the sync-report notification
     * (and its pending-conflicts warning) can show. Denying keeps
     * background sync working, just silent.
     */
    void ensureNotificationsPermission() {
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
                // NOTE: flagged but its remote is not captured yet (the
                // capturing sync has not run); edit it plainly.
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

        if (resolution.optBoolean("resolved")) {
            // Nothing needs the user: stage the clean merge and clear the
            // conflict. The toast is the tap's only visible outcome, so
            // without it the vanishing flag reads as a bug.
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

        JSONObject alternatives = resolution.optJSONObject("alternatives");
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
            vcfTransfer.importFile(data.getData());
        } else if (request == REQUEST_EXPORT) {
            vcfTransfer.exportFile(data.getData());
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

    /** The active contacts view, for the vCard export snapshot. */
    List<Group> visibleGroups() {
        return contactsList.visibleGroups();
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

        // With no real account the contact is born unattached, in the
        // hidden local book.
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
    CharSequence[] bookLabels(List<BookEntry> books) {
        CharSequence[] labels = new CharSequence[books.size()];
        for (int index = 0; index < books.size(); index++) {
            BookEntry entry = books.get(index);
            // NOTE: the local book has no account email, so it shows on
            // one line.
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
    void createCopy(BookEntry target, AccountEntry account, String id, String vcard) {
        String key = ContactPool.cardKey(account.account, target.book.url, id);

        // NOTE: Google creates land in myContacts; the group membership
        // pushes right after the create (confirmPush's key rename carries
        // it over).
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

        // The first replica of each addressbook is its candidate survivor.
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

        // The distinct documents behind the group, one per normalized
        // hash: one means a plain edit, several go through the union merge.
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
                // NOTE: a non-null changed set turns on the form's
                // conflict mode: only the disagreeing fields show.
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
        // NOTE: raw lines cannot fan out to several documents, so the
        // advanced editor only opens on single-card contacts.
        edit.advancedAvailable = pool.distinctRefs(replicas).size() == 1;

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

    /**
     * Opens the raw-property editor on the working document (delegated to
     * {@link AdvancedEditor}). Only offered on single-card contacts (and
     * new ones): raw lines cannot fan out to several physical documents.
     */
    private void openAdvanced() {
        advancedEditor.open();
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

        // NOTE: the hidden local book is not listed; it is the fallback
        // home when nothing is checked.
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
                            // Nothing checked leaves the contact unattached,
                            // kept only in the hidden local book.
                            edit.pendingBookState.put(LocalBook.URL, !anyChecked);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Stages the edited contact in the base and returns to the list
     * (delegated to {@link ContactWriter}). The form model applies to
     * every replica's own document (docs/merged-view.md), fanning out
     * onto every replica, conflict and merge branches included.
     */
    private void saveContact() {
        writer.save();
    }

    /** Rebuilds the contacts list from the base (staged edits included). */
    void reloadContacts() {
        contactsList.reload();
    }

    private static String newVcard() {
        return "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:" + UUID.randomUUID() + "\r\nEND:VCARD\r\n";
    }

    /**
     * The bridge's index of a vCard for one-off lookups (name, uid);
     * the hot paths read the same index from the store's columns
     * instead. Empty on a parse failure.
     */
    JSONObject cardIndex(String vcard) {
        try {
            return Cards.indexCard(vcard);
        } catch (Exception error) {
            Log.w("cardamum", "card index failed", error);
            return new JSONObject();
        }
    }

    /** Navigates forward: the panels slide in from the right. */
    void show(int panel) {
        show(panel, false);
    }

    /** Navigates back: the panels slide in from the left. */
    void showBack(int panel) {
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
    void openOverlay(int panel) {
        hideKeyboard();
        View overlay = overlayOf(panel);
        overlay.setVisibility(View.VISIBLE);
        overlay.startAnimation(
                android.view.animation.AnimationUtils.loadAnimation(this, R.anim.fade_zoom_in));
        screen = panel;
        applyChrome(panel);
    }

    /** Fades the overlay back out (zooming slightly away), then hides it. */
    void closeOverlay(int panel) {
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
                        // NOTE: invisible not gone, so the view stays
                        // measured; the animation is cleared because an
                        // animated invisible view still catches touches.
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
                        MainActivity.super.onBackPressed();
                    }
                };
        screens.put(PANEL_CONTACTS, contacts);

        Screen contact = new Screen();
        contact.chrome =
                () -> {
                    ((TextView) findViewById(R.id.bar_title)).setText(edit.title);
                    findViewById(R.id.bar_back).setVisibility(View.VISIBLE);
                    // Placing needs a real account to target, so with only
                    // the local book or while resolving a conflict it hides.
                    findViewById(R.id.contact_books)
                            .setVisibility(
                                    edit.card != null
                                                    && hasRealAccount()
                                                    && !edit.resolvingConflict
                                            ? View.VISIBLE
                                            : View.GONE);
                    findViewById(R.id.contact_add_field).setVisibility(View.VISIBLE);
                    // The FAB validates (check); updateSaveEnabled turns it
                    // into the error disc while diverging rows await review.
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
        advanced.barBack = advancedEditor::close;
        advanced.systemBack = advancedEditor::close;
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
        source.fab = advancedEditor::applySource;
        source.barBack = () -> showBack(PANEL_ADVANCED);
        source.systemBack = () -> showBack(PANEL_ADVANCED);
        screens.put(PANEL_SOURCE, source);

        Screen auth = new Screen();
        auth.ownBar = true;
        auth.chrome =
                () -> {
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
        // NOTE: the settings overlay draws above the drawer where the
        // shared FAB cannot follow, so it carries its own save FAB.
        account.chrome = () -> findViewById(R.id.fab).setVisibility(View.GONE);
        account.fab = accountSettings::save;
        screens.put(PANEL_ACCOUNT, account);
    }

    /**
     * Configures the persistent app bar and FAB for the screen: every
     * chrome element goes off, then the screen's own set comes back
     * (the contacts screen delegates to its selection/search state).
     */
    void applyChrome(int panel) {
        android.widget.ImageButton fab = findViewById(R.id.fab);

        // Every screen change resets the shared FAB to its plain enabled
        // disc, clearing any lingering loader, dim or conflict error tone;
        // the screen's own chrome re-applies its state after.
        fab.setImageAlpha(255);
        fab.setBackgroundTintList(null);
        fab.setImageTintList(android.content.res.ColorStateList.valueOf(accentContrast()));
        findViewById(R.id.fab_progress).setVisibility(View.GONE);
        setFabEnabled(R.id.fab, true);

        Screen entry = screens.get(panel);
        if (entry == null) {
            return;
        }

        // The overlays carry their own bars, so only the FAB is shared.
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
    int resolveColor(int attr) {
        return ui.resolveColor(attr);
    }

    /** Dismisses the soft keyboard, e.g. when leaving the edit form. */
    void hideKeyboard() {
        // NOTE: fall back to the flipper's window token when nothing
        // holds the focus (the same window either way).
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

    int dp(int value) {
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
                    // NOTE: edge-to-edge stops adjustResize, so the
                    // keyboard inset is folded into the bottom by hand;
                    // it overlaps the navigation bar, hence max not sum.
                    Insets ime = insets.getInsets(WindowInsets.Type.ime());
                    int bottom = Math.max(bars.bottom, ime.bottom);
                    applyBarInsets(root, bars, bottom);
                    return insets;
                });
        root.requestApplyInsets();
    }

    /** Places the system-bar and keyboard insets on the chrome. */
    private void applyBarInsets(View root, Insets bars, int bottom) {
        root.setPadding(bars.left, root.getPaddingTop(), bars.right, root.getPaddingBottom());

        // NOTE: all bars carry the top inset (the drawer header too,
        // since the drawer runs under the status bar).
        padTop(R.id.app_bar, bars.top);
        padTop(R.id.drawer_header, bars.top);
        padTop(R.id.auth_bar, bars.top);
        padTop(R.id.account_bar, bars.top);

        // NOTE: the base is each view's designed FAB clearance, so
        // re-applying stays idempotent as the listener fires again; the
        // bottom folds in the keyboard so the FAB rides above it.
        padBottom(R.id.fab_frame, 0, bottom);
        padBottom(R.id.account_fab_frame, 0, bottom);
        padBottom(R.id.contacts_list, 88, bottom);
        // The drawer's fixed bottom band takes the inset; the list above
        // it needs none.
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
