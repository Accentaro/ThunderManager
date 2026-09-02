package dev.thunder.bootstrap;

import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class ChatBubblesRenderer {
    interface FailureReporter {
        void report(String operation, Throwable error);
    }

    private final int paddingSmall;
    private final int paddingMedium;
    private final int paddingLarge;
    private final float avatarTranslation;
    private final FailureReporter failureReporter;
    private final Map<View, MutationState> mutations = new WeakHashMap<>();
    private final Map<ViewGroup, ReceiverState> receivers = new WeakHashMap<>();

    ChatBubblesRenderer(float density, FailureReporter failureReporter) {
        paddingSmall = Math.round(6 * density);
        paddingMedium = Math.round(8 * density);
        paddingLarge = Math.round(12 * density);
        avatarTranslation = Math.round(4 * density);
        this.failureReporter = failureReporter;
    }

    void remember(ViewGroup receiver) {
        receivers.computeIfAbsent(receiver, ignored -> new ReceiverState());
    }

    void adjustAccessoryMargins(ViewGroup receiver) {
        remember(receiver);
        attempt("accessories margin", () -> {
            ViewGroup accessories = accessoriesView(receiver);
            if (accessories == null) return;
            if (!(accessories.getLayoutParams() instanceof ViewGroup.MarginLayoutParams layout)) return;
            int topMargin = layout.topMargin;
            MutationState state = mutation(receiver, accessories);
            state.topMargin.apply(accessories, layout, 0);
            state.padding.apply(
                accessories,
                accessories.getPaddingLeft(),
                accessories.getPaddingTop() + topMargin,
                accessories.getPaddingRight(),
                accessories.getPaddingBottom()
            );
        });
    }

    void styleAuthor(ViewGroup receiver, ChatBubblesConfiguration configuration) {
        remember(receiver);
        ImageView avatar = firstDirectChild(receiver, ImageView.class);
        if (avatar != null) {
            attempt("avatar", () -> styleAvatar(receiver, avatar, configuration.avatarRadius));
        }

        LinearLayout content = firstMessageContent(receiver);
        if (content == null) return;
        View header = directChildNamed(content, "ConstraintLayout");
        boolean headerVisible = header instanceof ViewGroup group && hasVisibleChild(group);
        View accessories = directChildNamed(receiver, "MessageAccessoriesView");
        boolean hasAccessories = accessories instanceof ViewGroup;
        attempt("message bubble", () -> {
            if (headerVisible) {
                styleBubble(
                    receiver,
                    content,
                    0,
                    true,
                    !hasAccessories,
                    configuration,
                    paddingLarge,
                    paddingMedium,
                    0,
                    hasAccessories ? 0 : paddingMedium
                );
            } else {
                clearBubble(receiver, content);
            }
        });
        if (accessories instanceof ViewGroup group) {
            attempt(
                "accessories bubble",
                () -> styleAccessories(receiver, group, !headerVisible, configuration)
            );
        }
    }

    void restoreReceiver(ViewGroup receiver) {
        ReceiverState tracked = receivers.get(receiver);
        if (tracked == null) return;
        for (WeakReference<View> reference : new ArrayList<>(tracked.mutated)) {
            View view = reference.get();
            if (view == null) {
                tracked.mutated.remove(reference);
                continue;
            }
            MutationState state = mutations.get(view);
            if (state == null || state.owner.get() != receiver) continue;
            state.restore(view, failureReporter);
            if (!state.hasOwnedValues()) mutations.remove(view);
        }
    }

    void restoreAll() {
        for (Map.Entry<View, MutationState> entry : new ArrayList<>(mutations.entrySet())) {
            View view = entry.getKey();
            MutationState state = entry.getValue();
            if (view == null || state == null) continue;
            state.restore(view, failureReporter);
            if (!state.hasOwnedValues()) mutations.remove(view);
        }
    }

    void restyleKnown(ChatBubblesConfiguration configuration) {
        if (!configuration.enabled) return;
        for (ViewGroup receiver : new ArrayList<>(receivers.keySet())) {
            if (receiver == null) continue;
            restoreReceiver(receiver);
            adjustAccessoryMargins(receiver);
            styleAuthor(receiver, configuration);
        }
    }

    private void styleAvatar(ViewGroup receiver, ImageView avatar, int radius) {
        MutationState state = mutation(receiver, avatar);
        state.avatarProvider.radius = radius;
        state.clipToOutline.apply(
            avatar.getClipToOutline(),
            true,
            avatar::setClipToOutline
        );
        state.outlineProvider.apply(
            avatar.getOutlineProvider(),
            state.avatarProvider,
            avatar::setOutlineProvider
        );
        state.translationY.apply(
            avatar.getTranslationY(),
            avatarTranslation,
            avatar::setTranslationY
        );
        avatar.invalidateOutline();
    }

    private void styleAccessories(
        ViewGroup receiver,
        ViewGroup accessories,
        boolean start,
        ChatBubblesConfiguration configuration
    ) throws ReflectiveOperationException {
        Integer leftMargin = accessoryLeftMargin(accessories);
        if (leftMargin == null) return;
        styleBubble(
            receiver,
            accessories,
            leftMargin,
            start,
            true,
            configuration,
            paddingLarge,
            start ? paddingMedium : 0,
            paddingSmall,
            paddingMedium
        );
    }

    private void styleBubble(
        ViewGroup receiver,
        ViewGroup view,
        int leftMargin,
        boolean start,
        boolean end,
        ChatBubblesConfiguration configuration,
        int leftPadding,
        int topPadding,
        int rightPadding,
        int bottomPadding
    ) {
        GradientDrawable bubble = new GradientDrawable();
        bubble.setShape(GradientDrawable.RECTANGLE);
        bubble.setColor(configuration.bubbleColor);
        float[] radii = new float[8];
        for (int index = 0; index < radii.length; index++) {
            if ((start && end) || (start && index < 4) || (!start && index >= 4)) {
                radii[index] = configuration.bubbleRadius;
            }
        }
        bubble.setCornerRadii(radii);

        MutationState state = mutation(receiver, view);
        state.background.apply(view, new InsetDrawable(bubble, leftMargin, 0, paddingSmall, 0));
        state.padding.apply(view, leftPadding, topPadding, rightPadding, bottomPadding);
        state.translationX.apply(view.getTranslationX(), -paddingSmall, view::setTranslationX);
    }

    private void clearBubble(ViewGroup receiver, ViewGroup view) {
        MutationState state = mutation(receiver, view);
        state.background.apply(view, null);
        state.padding.apply(view, 0, 0, 0, 0);
        state.translationX.apply(view.getTranslationX(), 0, view::setTranslationX);
    }

    private MutationState mutation(ViewGroup receiver, View view) {
        MutationState state = mutations.get(view);
        if (state != null && state.owner.get() != receiver) {
            state.restore(view, failureReporter);
            if (!state.hasOwnedValues()) {
                mutations.remove(view);
                state = null;
            }
        }
        if (state == null) {
            state = new MutationState(receiver);
            mutations.put(view, state);
        }
        ReceiverState tracked = receivers.computeIfAbsent(receiver, ignored -> new ReceiverState());
        tracked.add(view);
        return state;
    }

    private void attempt(String operation, ThrowingRunnable operationBody) {
        try {
            operationBody.run();
        } catch (Throwable error) {
            try {
                failureReporter.report(operation, error);
            } catch (Throwable ignored) {
            }
        }
    }

    private static ViewGroup accessoriesView(ViewGroup receiver) {
        try {
            Object binding = field(receiver, "binding");
            Object accessories = field(binding, "accessoriesView");
            if (accessories instanceof ViewGroup group) return group;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        View direct = directChildNamed(receiver, "MessageAccessoriesView");
        return direct instanceof ViewGroup group ? group : null;
    }

    private static Integer accessoryLeftMargin(ViewGroup accessories) throws ReflectiveOperationException {
        Object decoration = field(accessories, "messageAccessoriesDecoration");
        for (String name : new String[] { "leftMarginPx", "leftMargin", "startMargin" }) {
            try {
                Object value = field(decoration, name);
                if (value instanceof Number number) return number.intValue();
            } catch (NoSuchFieldException ignored) {
            }
        }
        Object margins = field(decoration, "margins");
        Object value = field(margins, "leftMarginPx");
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Object field(Object receiver, String name) throws ReflectiveOperationException {
        if (receiver == null) throw new NoSuchFieldException(name);
        Class<?> type = receiver.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(receiver);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static LinearLayout firstMessageContent(ViewGroup receiver) {
        for (int index = 0; index < receiver.getChildCount(); index++) {
            View child = receiver.getChildAt(index);
            if (!(child instanceof LinearLayout layout)) continue;
            if (directChildNamed(layout, "ConstraintLayout") != null) return layout;
        }
        return null;
    }

    private static boolean hasVisibleChild(ViewGroup group) {
        for (int index = 0; index < group.getChildCount(); index++) {
            if (group.getChildAt(index).getVisibility() == View.VISIBLE) return true;
        }
        return false;
    }

    private static View directChildNamed(ViewGroup group, String simpleName) {
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (simpleName.equals(child.getClass().getSimpleName())) return child;
        }
        return null;
    }

    private static <ViewType extends View> ViewType firstDirectChild(ViewGroup group, Class<ViewType> type) {
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (type.isInstance(child)) return type.cast(child);
        }
        return null;
    }

    private static final class ReceiverState {
        final List<WeakReference<View>> mutated = new ArrayList<>();

        void add(View view) {
            for (int index = mutated.size() - 1; index >= 0; index--) {
                View tracked = mutated.get(index).get();
                if (tracked == null) {
                    mutated.remove(index);
                } else if (tracked == view) {
                    return;
                }
            }
            mutated.add(new WeakReference<>(view));
        }
    }

    private static final class MutationState {
        final WeakReference<ViewGroup> owner;
        final BackgroundState background = new BackgroundState();
        final PaddingState padding = new PaddingState();
        final FloatValueState translationX = new FloatValueState();
        final FloatValueState translationY = new FloatValueState();
        final BooleanValueState clipToOutline = new BooleanValueState();
        final ReferenceValueState<ViewOutlineProvider> outlineProvider = new ReferenceValueState<>();
        final TopMarginState topMargin = new TopMarginState();
        final BubbleOutlineProvider avatarProvider = new BubbleOutlineProvider();

        MutationState(ViewGroup owner) {
            this.owner = new WeakReference<>(owner);
        }

        void restore(View view, FailureReporter reporter) {
            restorePart("background", () -> background.restore(view), reporter);
            restorePart("padding", () -> padding.restore(view), reporter);
            restorePart(
                "translation x",
                () -> translationX.restore(view.getTranslationX(), view::setTranslationX),
                reporter
            );
            restorePart(
                "translation y",
                () -> translationY.restore(view.getTranslationY(), view::setTranslationY),
                reporter
            );
            restorePart(
                "outline clipping",
                () -> clipToOutline.restore(view.getClipToOutline(), view::setClipToOutline),
                reporter
            );
            restorePart(
                "outline provider",
                () -> outlineProvider.restore(view.getOutlineProvider(), view::setOutlineProvider),
                reporter
            );
            restorePart("top margin", () -> topMargin.restore(view), reporter);
            restorePart("outline invalidation", view::invalidateOutline, reporter);
        }

        boolean hasOwnedValues() {
            return background.owned
                || padding.hasOwnedValues()
                || translationX.owned
                || translationY.owned
                || clipToOutline.owned
                || outlineProvider.owned
                || topMargin.owned;
        }

        private static void restorePart(String operation, Runnable body, FailureReporter reporter) {
            try {
                body.run();
            } catch (Throwable error) {
                try {
                    reporter.report("restore " + operation, error);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static final class BackgroundState {
        boolean owned;
        Drawable original;
        Drawable applied;

        void apply(View view, Drawable desired) {
            Drawable current = view.getBackground();
            if (owned && current != applied) clear();
            if (owned) {
                if (desired == original) {
                    if (current != desired) view.setBackground(desired);
                    clear();
                    return;
                }
                if (current != desired) view.setBackground(desired);
                applied = desired;
                return;
            }
            if (current == desired) return;
            view.setBackground(desired);
            original = current;
            applied = desired;
            owned = true;
        }

        void restore(View view) {
            if (!owned) return;
            Drawable current = view.getBackground();
            if (current != applied) {
                clear();
                return;
            }
            if (current != original) view.setBackground(original);
            clear();
        }

        private void clear() {
            owned = false;
            original = null;
            applied = null;
        }
    }

    private static final class PaddingState {
        final boolean[] owned = new boolean[4];
        final int[] original = new int[4];
        final int[] applied = new int[4];

        void apply(View view, int left, int top, int right, int bottom) {
            int[] current = padding(view);
            int[] desired = { left, top, right, bottom };
            boolean[] nextOwned = owned.clone();
            int[] nextOriginal = original.clone();
            int[] nextApplied = applied.clone();
            for (int index = 0; index < desired.length; index++) {
                if (nextOwned[index] && current[index] != nextApplied[index]) nextOwned[index] = false;
                if (nextOwned[index]) {
                    if (desired[index] == nextOriginal[index]) {
                        nextOwned[index] = false;
                    } else {
                        nextApplied[index] = desired[index];
                    }
                } else if (current[index] != desired[index]) {
                    nextOwned[index] = true;
                    nextOriginal[index] = current[index];
                    nextApplied[index] = desired[index];
                }
            }
            if (!same(current, desired)) view.setPadding(left, top, right, bottom);
            System.arraycopy(nextOwned, 0, owned, 0, owned.length);
            System.arraycopy(nextOriginal, 0, original, 0, original.length);
            System.arraycopy(nextApplied, 0, applied, 0, applied.length);
        }

        void restore(View view) {
            int[] current = padding(view);
            int[] restored = current.clone();
            for (int index = 0; index < restored.length; index++) {
                if (owned[index] && current[index] == applied[index]) restored[index] = original[index];
            }
            if (!same(current, restored)) {
                view.setPadding(restored[0], restored[1], restored[2], restored[3]);
            }
            for (int index = 0; index < owned.length; index++) owned[index] = false;
        }

        boolean hasOwnedValues() {
            for (boolean value : owned) if (value) return true;
            return false;
        }

        private static int[] padding(View view) {
            return new int[] {
                view.getPaddingLeft(),
                view.getPaddingTop(),
                view.getPaddingRight(),
                view.getPaddingBottom()
            };
        }

        private static boolean same(int[] left, int[] right) {
            for (int index = 0; index < left.length; index++) {
                if (left[index] != right[index]) return false;
            }
            return true;
        }
    }

    static final class FloatValueState {
        boolean owned;
        float original;
        float applied;

        void apply(float current, float desired, FloatSetter setter) {
            if (owned && Float.compare(current, applied) != 0) owned = false;
            if (owned) {
                if (Float.compare(desired, original) == 0) {
                    if (Float.compare(current, desired) != 0) setter.set(desired);
                    owned = false;
                    return;
                }
                if (Float.compare(current, desired) != 0) setter.set(desired);
                applied = desired;
                return;
            }
            if (Float.compare(current, desired) == 0) return;
            setter.set(desired);
            original = current;
            applied = desired;
            owned = true;
        }

        void restore(float current, FloatSetter setter) {
            if (!owned) return;
            if (Float.compare(current, applied) != 0) {
                owned = false;
                return;
            }
            if (Float.compare(current, original) != 0) setter.set(original);
            owned = false;
        }
    }

    private static final class BooleanValueState {
        boolean owned;
        boolean original;
        boolean applied;

        void apply(boolean current, boolean desired, BooleanSetter setter) {
            if (owned && current != applied) owned = false;
            if (owned) {
                if (desired == original) {
                    if (current != desired) setter.set(desired);
                    owned = false;
                    return;
                }
                if (current != desired) setter.set(desired);
                applied = desired;
                return;
            }
            if (current == desired) return;
            setter.set(desired);
            original = current;
            applied = desired;
            owned = true;
        }

        void restore(boolean current, BooleanSetter setter) {
            if (!owned) return;
            if (current != applied) {
                owned = false;
                return;
            }
            if (current != original) setter.set(original);
            owned = false;
        }
    }

    static final class ReferenceValueState<Value> {
        boolean owned;
        Value original;
        Value applied;

        void apply(Value current, Value desired, ReferenceSetter<Value> setter) {
            if (owned && current != applied) clear();
            if (owned) {
                if (desired == original) {
                    if (current != desired) setter.set(desired);
                    clear();
                    return;
                }
                if (current != desired) setter.set(desired);
                applied = desired;
                return;
            }
            if (current == desired) return;
            setter.set(desired);
            original = current;
            applied = desired;
            owned = true;
        }

        void restore(Value current, ReferenceSetter<Value> setter) {
            if (!owned) return;
            if (current != applied) {
                clear();
                return;
            }
            if (current != original) setter.set(original);
            clear();
        }

        private void clear() {
            owned = false;
            original = null;
            applied = null;
        }
    }

    private static final class TopMarginState {
        boolean owned;
        int original;
        int applied;
        ViewGroup.MarginLayoutParams appliedLayout;

        void apply(View view, ViewGroup.MarginLayoutParams layout, int desired) {
            int current = layout.topMargin;
            if (owned && (layout != appliedLayout || current != applied)) clear();
            if (owned) {
                if (desired == original) {
                    if (current != desired) set(view, layout, desired);
                    clear();
                    return;
                }
                if (current != desired) set(view, layout, desired);
                applied = desired;
                return;
            }
            if (current == desired) return;
            set(view, layout, desired);
            original = current;
            applied = desired;
            appliedLayout = layout;
            owned = true;
        }

        void restore(View view) {
            if (!owned) return;
            if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams layout)
                || layout != appliedLayout
                || layout.topMargin != applied) {
                clear();
                return;
            }
            if (layout.topMargin != original) set(view, layout, original);
            clear();
        }

        private static void set(View view, ViewGroup.MarginLayoutParams layout, int top) {
            layout.setMargins(layout.leftMargin, top, layout.rightMargin, layout.bottomMargin);
            view.setLayoutParams(layout);
        }

        private void clear() {
            owned = false;
            appliedLayout = null;
        }
    }

    private static final class BubbleOutlineProvider extends ViewOutlineProvider {
        float radius;

        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    interface FloatSetter {
        void set(float value);
    }

    private interface BooleanSetter {
        void set(boolean value);
    }

    interface ReferenceSetter<Value> {
        void set(Value value);
    }
}
