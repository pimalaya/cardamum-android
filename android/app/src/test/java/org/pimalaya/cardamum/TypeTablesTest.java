package org.pimalaya.cardamum;

import static org.junit.Assert.assertEquals;

import android.content.res.Resources;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.cardamum.client.Cards;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Pins the edit form's type spinners to the Rust-owned ordering: each
 * Android string-array (the localized labels the user sees) must line
 * up position for position with the vCard TYPE sets project.rs maps
 * those positions to. Reordering or resizing either side without the
 * other used to mismap every type silently (a phone saved as Home when
 * the label said Work); here it fails loudly instead.
 *
 * <p>The expected label-to-type pairings below ARE the cross-language
 * contract: the base-locale label at each position and the TYPE set it
 * must mean. The test checks that the Rust ordering agrees with them
 * (a reorder or rename on the Rust side breaks it) and that the
 * {@code arrays.xml} array carries exactly those labels in that order
 * (a reorder or resize on the Android side breaks it).
 */
@RunWith(RobolectricTestRunner.class)
public class TypeTablesTest {
    @Test
    public void phoneSpinnerIsAligned() {
        assertAligned(
                R.array.phone_types,
                "phone",
                pair("Mobile", "cell"),
                pair("Home", "home"),
                pair("Work", "work"),
                pair("Work mobile", "cell", "work"),
                pair("Fax (home)", "fax", "home"),
                pair("Fax (work)", "fax", "work"),
                pair("Pager", "pager"),
                pair("Other"));
    }

    @Test
    public void emailSpinnerIsAligned() {
        assertAligned(
                R.array.email_types, "email", pair("Home", "home"), pair("Work", "work"), pair("Other"));
    }

    @Test
    public void addressSpinnerIsAligned() {
        assertAligned(
                R.array.address_types,
                "address",
                pair("Home", "home"),
                pair("Work", "work"),
                pair("Other"));
    }

    @Test
    public void relationSpinnerIsAligned() {
        assertAligned(
                R.array.relation_types,
                "relation",
                pair("Spouse", "spouse"),
                pair("Child", "child"),
                pair("Parent", "parent"),
                pair("Sibling", "sibling"),
                pair("Friend", "friend"),
                pair("Colleague", "colleague"),
                pair("Emergency", "emergency"),
                pair("Other"));
    }

    @Test
    public void genderSpinnerIsAligned() {
        // The gender spinner carries a sex code per position, not a TYPE
        // list; an empty code is the unset first row.
        assertAligned(
                R.array.gender_types,
                "gender",
                pair("Not set", ""),
                pair("Male", "M"),
                pair("Female", "F"),
                pair("Other", "O"),
                pair("None", "N"),
                pair("Unknown", "U"));
    }

    /**
     * Checks the array's labels and the Rust ordering both match the
     * expected pairings, position for position and in count.
     */
    private static void assertAligned(int arrayId, String kind, Pair... expected) {
        Resources resources = RuntimeEnvironment.getApplication().getResources();
        String[] labels = resources.getStringArray(arrayId);
        JSONArray order = Cards.cardTypeOrder(kind);

        assertEquals(kind + " label count", expected.length, labels.length);
        assertEquals(kind + " Rust order length", expected.length, order.length());

        for (int position = 0; position < expected.length; position++) {
            assertEquals(
                    kind + " label " + position, expected[position].label, labels[position]);
            assertEquals(
                    kind + " types " + position,
                    expected[position].types,
                    types(order.optJSONArray(position)));
        }
    }

    private static Pair pair(String label, String... types) {
        // The list kinds' "Other" row is the empty TYPE set (no types
        // passed); the gender rows carry their sex code, the unset row
        // an explicit empty code.
        List<String> list = new ArrayList<>();
        for (String type : types) {
            list.add(type);
        }
        return new Pair(label, list);
    }

    private static List<String> types(JSONArray array) {
        List<String> values = new ArrayList<>();
        for (int index = 0; array != null && index < array.length(); index++) {
            values.add(array.optString(index));
        }
        return values;
    }

    /** One expected spinner row: its base-locale label and TYPE set. */
    private static final class Pair {
        final String label;
        final List<String> types;

        Pair(String label, List<String> types) {
            this.label = label;
            this.types = types;
        }
    }
}
