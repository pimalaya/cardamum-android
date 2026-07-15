package org.pimalaya.cardamum;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.EditText;

/**
 * Theme and density helpers every hand-built view needs, shared by the
 * screens (MainActivity, ContactForm, the extracted flows) so the
 * resolution logic exists once.
 */
final class Ui {
    private final Context context;

    Ui(Context context) {
        this.context = context;
    }

    /** Resolves a theme attribute to its referenced resource id. */
    int resolveAttr(int attr) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attr, value, true);
        return value.resourceId;
    }

    /** Resolves a theme colour attribute to an ARGB int. */
    int resolveColor(int attr) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attr, value, true);
        if (value.resourceId != 0) {
            return context.getResources().getColor(value.resourceId, context.getTheme());
        }
        return value.data;
    }

    /** Density-independent pixels to raw pixels. */
    int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }

    /** A plain no-suggestions text field, prefilled (dialog forms). */
    EditText field(int hint, String value) {
        EditText field = new EditText(context);
        field.setHint(hint);
        field.setText(value);
        field.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return field;
    }
}
