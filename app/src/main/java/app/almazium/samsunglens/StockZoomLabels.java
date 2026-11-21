package app.almazium.samsunglens;

import android.text.Editable;
import android.text.SpannedString;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.IdentityHashMap;
import java.util.Map;

import static de.robv.android.xposed.XposedHelpers.getIntField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;

final class StockZoomLabels {
    private final Map<TextView, Label> active = new IdentityHashMap<>();

    void show(Object zoomBinding, int nativeZoom, String ratio) {
        Map<TextView, String> targets = new IdentityHashMap<>();
        Object display = getObjectField(zoomBinding, "m");
        TextView readout = text(getObjectField(display, "i"), "b");
        if (readout != null) targets.put(readout, ratio + " ×");
        int maximumPreset = Math.round(nativeZoom / 1000f) * 1000;
        collect(targets, getObjectField(zoomBinding, "e"), maximumPreset, ratio);
        collect(targets, getObjectField(zoomBinding, "c"), maximumPreset, ratio);
        collect(targets, getObjectField(zoomBinding, "j"), maximumPreset, ratio);

        active.entrySet().removeIf(entry -> {
            if (targets.containsKey(entry.getKey())) return false;
            entry.getValue().restore();
            return true;
        });
        for (Map.Entry<TextView, String> target : targets.entrySet()) {
            TextView view = target.getKey();
            Label label = active.get(view);
            if (label == null) {
                label = new Label(view);
                active.put(view, label);
            }
            label.show(target.getValue(), view != readout);
        }
    }

    void restore() {
        for (Label label : active.values()) label.restore();
        active.clear();
    }

    private static TextView text(Object binding, String field) {
        if (binding == null) return null;
        Object value = getObjectField(binding, field);
        return value instanceof TextView view ? view : null;
    }

    private static void collect(Map<TextView, String> targets, Object child, int maximum, String ratio) {
        if (!(child instanceof View view)) return;
        switch (view.getClass().getSimpleName()) {
            case "ZoomShortcutButton" -> {
                if (getIntField(view, "b") == maximum) add(targets, text(getObjectField(view, "a"), "a"), ratio);
            }
            case "ZoomLensShortcutButton" -> {
                if (getIntField(view, "e") == maximum) addLensLabels(targets, getObjectField(view, "a"), ratio);
            }
            case "ZoomLensTextButton" -> {
                if (getIntField(view, "q") == maximum) addLensLabels(targets, getObjectField(view, "k"), ratio);
            }
            default -> {
                if (view instanceof ViewGroup group) {
                    for (int i = 0; i < group.getChildCount(); i++) {
                        collect(targets, group.getChildAt(i), maximum, ratio);
                    }
                }
            }
        }
    }

    private static void addLensLabels(Map<TextView, String> targets, Object binding, String ratio) {
        add(targets, text(binding, "c"), ratio);
        add(targets, text(binding, "g"), ratio);
    }

    private static void add(Map<TextView, String> targets, TextView view, String ratio) {
        if (view != null) targets.put(view, ratio);
    }

    private static final class Label implements TextWatcher {
        final TextView view;
        final float baseScale;
        CharSequence original;
        String replacement;
        boolean changing;
        boolean fit;

        Label(TextView view) {
            this.view = view;
            baseScale = view.getTextScaleX();
            original = new SpannedString(view.getText());
            view.addTextChangedListener(this);
        }

        void show(String value, boolean fit) {
            replacement = value;
            this.fit = fit;
            render();
        }

        private void render() {
            if (fit) {
                int width = view.getWidth() - view.getCompoundPaddingLeft() - view.getCompoundPaddingRight();
                float measured = view.getPaint().measureText(replacement);
                float currentScale = view.getTextScaleX();
                if (width > 0 && measured > 0 && currentScale > 0) {
                    float needed = Math.min(baseScale, currentScale * width / measured);
                    if (Math.abs(needed - currentScale) > 0.01f) view.setTextScaleX(needed);
                }
            }
            if (TextUtils.equals(view.getText(), replacement)) return;
            changing = true;
            try {
                view.setText(replacement);
            } finally {
                changing = false;
            }
        }

        @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}

        @Override public void afterTextChanged(Editable text) {
            if (changing || replacement == null) return;
            original = new SpannedString(text);
            render();
        }

        void restore() {
            view.removeTextChangedListener(this);
            if (fit) view.setTextScaleX(baseScale);
            view.setText(original);
        }
    }
}
