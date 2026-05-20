/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard.emoji;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import helium314.keyboard.event.HapticEvent;
import helium314.keyboard.keyboard.Key;
import helium314.keyboard.keyboard.Keyboard;
import helium314.keyboard.keyboard.KeyboardActionListener;
import helium314.keyboard.keyboard.KeyboardId;
import helium314.keyboard.keyboard.KeyboardLayoutSet;
import helium314.keyboard.keyboard.KeyboardSwitcher;
import helium314.keyboard.keyboard.KeyboardView;
import helium314.keyboard.keyboard.MainKeyboardView;
import helium314.keyboard.keyboard.PointerTracker;
import helium314.keyboard.keyboard.internal.KeyDrawParams;
import helium314.keyboard.keyboard.internal.KeyVisualAttributes;
import helium314.keyboard.keyboard.internal.keyboard_parser.EmojiParserKt;
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.latin.AudioAndHapticFeedbackManager;
import helium314.keyboard.latin.dictionary.Dictionary;
import helium314.keyboard.latin.dictionary.DictionaryFactory;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.RichInputMethodManager;
import helium314.keyboard.latin.RichInputMethodSubtype;
import helium314.keyboard.latin.SingleDictionaryFacilitator;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Colors;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsValues;
import helium314.keyboard.latin.utils.DictionaryInfoUtils;
import helium314.keyboard.latin.utils.ResourceUtils;

import static helium314.keyboard.latin.common.Constants.NOT_A_COORDINATE;

/**
 * View class to implement Emoji palettes.
 * The Emoji keyboard consists of group of views layout/emoji_palettes_view.
 * <ol>
 * <li> Emoji category tabs.
 * <li> Delete button.
 * <li> Emoji keyboard pages that can be scrolled by swiping horizontally or by selecting a tab.
 * <li> Back to main keyboard button and enter button.
 * </ol>
 * Because of the above reasons, this class doesn't extend {@link KeyboardView}.
 */
public final class EmojiPalettesView extends LinearLayout
        implements View.OnClickListener, EmojiViewCallback {
    private static SingleDictionaryFacilitator sDictionaryFacilitator;

    private boolean initialized = false;
    private final Colors mColors;
    private final EmojiLayoutParams mEmojiLayoutParams;
    private LinearLayout mTabStrip;
    private EmojiCategoryPageIndicatorView mEmojiCategoryPageIndicatorView;
    private KeyboardActionListener mKeyboardActionListener = KeyboardActionListener.EMPTY_LISTENER;
    private final EmojiCategory mEmojiCategory;
    private RecyclerView mPager;

    public EmojiPalettesView(final Context context, final AttributeSet attrs) {
        this(context, attrs, R.attr.emojiPalettesViewStyle);
    }

    public EmojiPalettesView(final Context context, final AttributeSet attrs, final int defStyle) {
        super(context, attrs, defStyle);
        mColors = Settings.getValues().mColors;
        final KeyboardLayoutSet.Builder builder = new KeyboardLayoutSet.Builder(context, null);
        final Resources res = context.getResources();
        mEmojiLayoutParams = new EmojiLayoutParams(res);
        builder.setSubtype(RichInputMethodSubtype.Companion.getEmojiSubtype());
        builder.setKeyboardGeometry(ResourceUtils.getKeyboardWidth(context, Settings.getValues()),
                mEmojiLayoutParams.getEmojiKeyboardHeight());
        final KeyboardLayoutSet layoutSet = builder.build();
        final TypedArray emojiPalettesViewAttr = context.obtainStyledAttributes(attrs,
                R.styleable.EmojiPalettesView, defStyle, R.style.EmojiPalettesView);
        mEmojiCategory = new EmojiCategory(context, layoutSet, emojiPalettesViewAttr);
        emojiPalettesViewAttr.recycle();
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        final Resources res = getContext().getResources();
        final helium314.keyboard.latin.settings.SettingsValues settings = Settings.getValues();
        final int abcHeight = ResourceUtils.getKeyboardHeight(res, settings);
        final boolean persistentEmojiEnabled = helium314.keyboard.latin.utils.KtxKt.prefs(getContext()).getBoolean(Settings.PREF_PERSISTENT_EMOJI_ROW, helium314.keyboard.latin.settings.Defaults.PREF_PERSISTENT_EMOJI_ROW);
        final int emojiRowHeight = persistentEmojiEnabled ? (int) (41 * res.getDisplayMetrics().density) : 0;
        final int finalHeight = abcHeight + emojiRowHeight + getPaddingTop() + getPaddingBottom();
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(finalHeight, MeasureSpec.EXACTLY));
        final int width = ResourceUtils.getKeyboardWidth(getContext(), settings) + getPaddingLeft() + getPaddingRight();
        mEmojiCategoryPageIndicatorView.mWidth = width;
        setMeasuredDimension(width, finalHeight);
    }

    private void addTab(final LinearLayout host, final int categoryId) {
        final TabImageView iconView = new TabImageView(getContext());
        mColors.setBackground(iconView, ColorType.STRIP_BACKGROUND);
        mColors.setColor(iconView, ColorType.EMOJI_CATEGORY);
        iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final int iconPadding = (int) (10 * getContext().getResources().getDisplayMetrics().density);
        iconView.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
        iconView.setImageResource(mEmojiCategory.getCategoryTabIcon(categoryId));
        iconView.setContentDescription(mEmojiCategory.getAccessibilityDescription(categoryId));
        iconView.setTag((long) categoryId); // use long for simple difference to int used for key codes
        host.addView(iconView);
        iconView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        iconView.setOnClickListener(this);
    }

    @SuppressLint("ClickableViewAccessibility")
    public void initialize() { // needs to be delayed for access to EmojiTabStrip, which is not a child of this view
        if (initialized) return;
        mEmojiCategory.initialize();
        mTabStrip = (LinearLayout) KeyboardSwitcher.getInstance().getEmojiTabStrip();
        if (Settings.getValues().mSecondaryStripVisible) {
            for (final EmojiCategory.CategoryProperties properties : mEmojiCategory.getShownCategories()) {
                addTab(mTabStrip, properties.mCategoryId);
            }
        }

        mPager = findViewById(R.id.emoji_pager);
        mPager.setHasFixedSize(true);
        mPager.setItemAnimator(null);
        final LinearLayoutManager layoutManager = new LinearLayoutManager(getContext(), LinearLayoutManager.VERTICAL, false);
        mPager.setLayoutManager(layoutManager);
        final EmojiPalettesAdapter adapter = new EmojiPalettesAdapter(mEmojiCategory, this);
        mPager.setAdapter(adapter);

        mPager.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (recyclerView.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) {
                    return;
                }
                final int firstVisible = layoutManager.findFirstVisibleItemPosition();
                if (firstVisible != RecyclerView.NO_POSITION) {
                    final int categoryId = adapter.getCategoryIdForPosition(firstVisible);
                    if (categoryId != -1 && categoryId != mEmojiCategory.getCurrentCategoryId()) {
                        setCurrentCategoryId(categoryId, true);
                    }
                }
            }
        });

        mEmojiLayoutParams.setEmojiListProperties(mPager);
        mEmojiCategoryPageIndicatorView = findViewById(R.id.emoji_category_page_id_view);
        mEmojiCategoryPageIndicatorView.setVisibility(View.GONE);
        mEmojiLayoutParams.setCategoryPageIdViewProperties(mEmojiCategoryPageIndicatorView);
        setCurrentCategoryId(mEmojiCategory.getCurrentCategoryId(), true);
        initialized = true;
    }

    /**
     * Called from {@link EmojiPageKeyboardView} through {@link android.view.View.OnClickListener}
     * interface to handle non-canceled touch-up events from View-based elements such as the space
     * bar.
     */
    @Override
    public void onClick(View v) {
        final Object tag = v.getTag();
        if (tag instanceof Long) {
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS);
            final int categoryId = ((Long) tag).intValue();
            if (categoryId != mEmojiCategory.getCurrentCategoryId()) {
                setCurrentCategoryId(categoryId, false);
                updateEmojiCategoryPageIdView();
            }
        }
    }

    /**
     * Called from {@link EmojiPageKeyboardView} through {@link EmojiViewCallback}
     * interface to handle touch events from non-View-based elements such as Emoji buttons.
     */
    @Override
    public void onPressKey(final Key key) {
        final int code = key.getCode();
        mKeyboardActionListener.onPressKey(code, 0, true, HapticEvent.KEY_PRESS);
    }

    /**
     * Called from {@link EmojiPageKeyboardView} through {@link EmojiViewCallback}
     * interface to handle touch events from non-View-based elements such as Emoji buttons.
     * This may be called without any prior call to {@link EmojiViewCallback#onPressKey(Key)}.
     */
    @Override
    public void onReleaseKey(final Key key) {
        addRecentKey(key);
        final int code = key.getCode();
        if (code == KeyCode.MULTIPLE_CODE_POINTS) {
            mKeyboardActionListener.onTextInput(key.getOutputText());
        } else {
            mKeyboardActionListener.onCodeInput(code, NOT_A_COORDINATE, NOT_A_COORDINATE, false);
        }
        mKeyboardActionListener.onReleaseKey(code, false);
        if (Settings.getValues().mAlphaAfterEmojiInEmojiView)
            mKeyboardActionListener.onCodeInput(KeyCode.ALPHA, NOT_A_COORDINATE, NOT_A_COORDINATE, false);
    }

    @Override
    public String getDescription(String emoji) {
        if (sDictionaryFacilitator == null) {
            return null;
        }

        var wordProperty = sDictionaryFacilitator.getWordProperty(EmojiParserKt.getEmojiNeutralVersion(emoji));
        if (wordProperty == null || ! wordProperty.mHasShortcuts) {
            return null;
        }

        return wordProperty.mShortcutTargets.get(0).mWord;
    }

    public void setHardwareAcceleratedDrawingEnabled(final boolean enabled) {
        if (!enabled) return;
        // TODO: Should use LAYER_TYPE_SOFTWARE when hardware acceleration is off?
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void startEmojiPalettes(final KeyVisualAttributes keyVisualAttr,
               final EditorInfo editorInfo, final KeyboardActionListener keyboardActionListener) {
        initialize();

        setupBottomRowKeyboard(editorInfo, keyboardActionListener);
        final KeyDrawParams params = new KeyDrawParams();
        params.updateParams(mEmojiLayoutParams.getBottomRowKeyboardHeight(), keyVisualAttr);
        setupSidePadding();
        initDictionaryFacilitator();
    }

    void addRecentKey(final Key key) {
        if (Settings.getValues().mIncognitoModeEnabled) {
            // We do not want to log recent keys while being in incognito
            return;
        }
        final String emojiStr = key.getOutputText() != null ? key.getOutputText() : (key.getCode() > 0 ? new String(Character.toChars(key.getCode())) : null);
        if (emojiStr != null) {
            AdaptiveEmojiEngine.recordEmojiUsage(getContext(), emojiStr);
        }
        if (getVisibility() == VISIBLE && mEmojiCategory.isInRecentTab()) {
            getRecentsKeyboard().addPendingKey(key);
            return;
        }
        getRecentsKeyboard().addKeyFirst(key);
        if (initialized && mPager.getAdapter() instanceof EmojiPalettesAdapter) {
            final int pos = ((EmojiPalettesAdapter) mPager.getAdapter()).getFirstPagePositionOfCategory(EmojiCategory.ID_RECENTS);
            if (pos != -1) {
                mPager.getAdapter().notifyItemChanged(pos);
            }
        }
    }

    public void addRecentEmoji(final String emoji) {
        if (Settings.getValues().mIncognitoModeEnabled) {
            return;
        }
        AdaptiveEmojiEngine.recordEmojiUsage(getContext(), emoji);
        if (getRecentsKeyboard() == null || getRecentsKeyboard().getSortedKeys() == null) {
            return;
        }
        for (final Key k : getRecentsKeyboard().getSortedKeys()) {
            if (emoji.equals(k.getOutputText()) || (k.getCode() > 0 && emoji.equals(new String(Character.toChars(k.getCode()))))) {
                addRecentKey(k);
                return;
            }
        }
    }

    public static class AdaptiveEmojiEngine {
        private static final String PREF_ADAPTIVE_METADATA = "pref_adaptive_emoji_metadata";

        public static void recordEmojiUsage(@NonNull final Context context, @NonNull final String emoji) {
            final android.content.SharedPreferences prefs = helium314.keyboard.latin.utils.KtxKt.prefs(context);
            final String jsonStr = prefs.getString(PREF_ADAPTIVE_METADATA, "{}");
            org.json.JSONObject root;
            try {
                root = new org.json.JSONObject(jsonStr);
            } catch (Exception e) {
                root = new org.json.JSONObject();
            }

            final long now = System.currentTimeMillis();
            org.json.JSONObject record = root.optJSONObject(emoji);
            if (record == null) {
                record = new org.json.JSONObject();
                try {
                    record.put("lastUsed", now);
                    record.put("freq", 1);
                    record.put("burst", 1);
                    root.put(emoji, record);
                } catch (Exception ignored) {}
            } else {
                long lastUsed = record.optLong("lastUsed", now);
                int freq = record.optInt("freq", 0);
                int burst = record.optInt("burst", 0);

                if (now - lastUsed < 300_000L) { // 5 minutes window for burst
                    burst++;
                } else {
                    burst = 1; // reset burst if outside window
                }
                freq++;
                try {
                    record.put("lastUsed", now);
                    record.put("freq", freq);
                    record.put("burst", burst);
                    root.put(emoji, record);
                } catch (Exception ignored) {}
            }

            prefs.edit().putString(PREF_ADAPTIVE_METADATA, root.toString()).apply();
        }

        public static java.util.List<String> getRankedEmojis(@NonNull final Context context) {
            final android.content.SharedPreferences prefs = helium314.keyboard.latin.utils.KtxKt.prefs(context);
            final String jsonStr = prefs.getString(PREF_ADAPTIVE_METADATA, "{}");
            org.json.JSONObject root;
            try {
                root = new org.json.JSONObject(jsonStr);
            } catch (Exception e) {
                root = new org.json.JSONObject();
            }

            final long now = System.currentTimeMillis();
            final java.util.ArrayList<EmojiScore> list = new java.util.ArrayList<>();
            final java.util.Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                final String emoji = keys.next();
                final org.json.JSONObject record = root.optJSONObject(emoji);
                if (record != null) {
                    final long lastUsed = record.optLong("lastUsed", now);
                    final int freq = record.optInt("freq", 1);
                    final int burst = record.optInt("burst", 1);

                    // 1. Recency factor
                    long deltaSec = (now - lastUsed) / 1000L;
                    if (deltaSec < 0) deltaSec = 0;
                    double recencyScore = 100.0 / (1.0 + deltaSec / 600.0);

                    // 2. Burst factor
                    double burstScore = (now - lastUsed < 300_000L) ? Math.min(burst, 10) * 15.0 : 0.0;

                    // 3. Frequency factor
                    double freqScore = Math.min(freq, 100) * 0.4;

                    // Total Score
                    double score = recencyScore * 1.2 + burstScore * 1.0 + freqScore * 1.0;
                    list.add(new EmojiScore(emoji, score));
                }
            }

            // Fallback / Baseline integration
            final String strRecent = prefs.getString(Settings.PREF_EMOJI_RECENT_KEYS, helium314.keyboard.latin.settings.Defaults.PREF_EMOJI_RECENT_KEYS);
            final java.util.List<Object> recentKeys = helium314.keyboard.latin.utils.JsonUtils.jsonStrToList(strRecent);
            for (final Object o : recentKeys) {
                String emoji = null;
                if (o instanceof Integer) {
                    emoji = new String(Character.toChars((Integer) o));
                } else if (o instanceof String) {
                    emoji = (String) o;
                }
                if (emoji != null) {
                    boolean found = false;
                    for (final EmojiScore es : list) {
                        if (es.emoji.equals(emoji)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        list.add(new EmojiScore(emoji, 10.0)); // baseline score for existing recents
                    }
                }
            }

            final String[] defaultEmojis = { "😂", "🙏", "😍", "👍", "😭", "🥺", "🤣", "❤️", "✨", "🔥" };
            for (final String defEmoji : defaultEmojis) {
                boolean found = false;
                for (final EmojiScore es : list) {
                    if (es.emoji.equals(defEmoji)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    list.add(new EmojiScore(defEmoji, 5.0));
                }
            }

            java.util.Collections.sort(list, (a, b) -> Double.compare(b.score, a.score));

            final java.util.ArrayList<String> result = new java.util.ArrayList<>();
            for (final EmojiScore es : list) {
                result.add(es.emoji);
                if (result.size() >= 10) {
                    break;
                }
            }
            return result;
        }

        private static class EmojiScore {
            final String emoji;
            final double score;
            EmojiScore(String emoji, double score) {
                this.emoji = emoji;
                this.score = score;
            }
        }
    }

    private void setupBottomRowKeyboard(final EditorInfo editorInfo, final KeyboardActionListener keyboardActionListener) {
        MainKeyboardView keyboardView = findViewById(R.id.bottom_row_keyboard);
        keyboardView.setKeyboardActionListener(keyboardActionListener);
        PointerTracker.switchTo(keyboardView);
        final KeyboardLayoutSet kls = KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(getContext(), editorInfo);
        final Keyboard keyboard = kls.getKeyboard(KeyboardId.ELEMENT_EMOJI_BOTTOM_ROW);
        keyboardView.setKeyboard(keyboard);
    }

    private void setupSidePadding() {
        final SettingsValues sv = Settings.getValues();
        final int keyboardWidth = ResourceUtils.getKeyboardWidth(getContext(), sv);
        final TypedArray keyboardAttr = getContext().obtainStyledAttributes(
                null, R.styleable.Keyboard, R.attr.keyboardStyle, R.style.Keyboard);
        final float leftPadding = keyboardAttr.getFraction(R.styleable.Keyboard_keyboardLeftPadding,
                keyboardWidth, keyboardWidth, 0f) * sv.mSidePaddingScale;
        final float rightPadding =  keyboardAttr.getFraction(R.styleable.Keyboard_keyboardRightPadding,
                keyboardWidth, keyboardWidth, 0f) * sv.mSidePaddingScale;
        keyboardAttr.recycle();
        mPager.setPadding(
                (int) leftPadding,
                mPager.getPaddingTop(),
                (int) rightPadding,
                mPager.getPaddingBottom()
        );
        mEmojiCategoryPageIndicatorView.setPadding(
                (int) leftPadding,
                mEmojiCategoryPageIndicatorView.getPaddingTop(),
                (int) rightPadding,
                mEmojiCategoryPageIndicatorView.getPaddingBottom()
        );
        // setting width does not do anything, so we have some workaround in EmojiCategoryPageIndicatorView
    }

    public void stopEmojiPalettes() {
        if (!initialized) return;
        getRecentsKeyboard().flushPendingRecentKeys();
    }

    private DynamicGridKeyboard getRecentsKeyboard() {
        return mEmojiCategory.getKeyboard(EmojiCategory.ID_RECENTS, 0);
    }

    public void setKeyboardActionListener(final KeyboardActionListener listener) {
        mKeyboardActionListener = listener;
    }

    private void updateEmojiCategoryPageIdView() {
    }

    private void setCurrentCategoryId(final int categoryId, final boolean fromScroll) {
        final int oldCategoryId = mEmojiCategory.getCurrentCategoryId();
        if (fromScroll || oldCategoryId != categoryId) {
            if (oldCategoryId == EmojiCategory.ID_RECENTS && !fromScroll) {
                getRecentsKeyboard().flushPendingRecentKeys();
                if (mPager.getAdapter() instanceof EmojiPalettesAdapter) {
                    final int pos = ((EmojiPalettesAdapter) mPager.getAdapter()).getFirstPagePositionOfCategory(EmojiCategory.ID_RECENTS);
                    if (pos != -1) {
                        mPager.getAdapter().notifyItemChanged(pos);
                    }
                }
            }

            mEmojiCategory.setCurrentCategoryId(categoryId);

            if (!fromScroll && mPager.getAdapter() instanceof EmojiPalettesAdapter) {
                final int headerPos = ((EmojiPalettesAdapter) mPager.getAdapter()).getHeaderPositionOfCategory(categoryId);
                if (headerPos != -1 && mPager.getLayoutManager() instanceof LinearLayoutManager) {
                    ((LinearLayoutManager) mPager.getLayoutManager()).scrollToPositionWithOffset(headerPos, 0);
                }
            }

            if (Settings.getValues().mSecondaryStripVisible) {
                final View old = mTabStrip.findViewWithTag((long) oldCategoryId);
                final View current = mTabStrip.findViewWithTag((long) categoryId);

                if (old instanceof TabImageView) {
                    ((TabImageView) old).setSelectedCategory(false, 0);
                    Settings.getValues().mColors.setColor((ImageView) old, ColorType.EMOJI_CATEGORY);
                }
                if (current instanceof TabImageView) {
                    android.util.TypedValue typedValue = new android.util.TypedValue();
                    getContext().getTheme().resolveAttribute(android.R.attr.colorAccent, typedValue, true);
                    int accentColor = typedValue.data;
                    ((TabImageView) current).setSelectedCategory(true, accentColor);
                    
                    // Set contrasting color for the icon so it's visible on the accent circle
                    double y = (299 * android.graphics.Color.red(accentColor) + 587 * android.graphics.Color.green(accentColor) + 114 * android.graphics.Color.blue(accentColor)) / 1000.0;
                    int contrastColor = y >= 128 ? android.graphics.Color.BLACK : android.graphics.Color.WHITE;
                    ((TabImageView) current).setColorFilter(contrastColor, android.graphics.PorterDuff.Mode.SRC_IN);
                }
            }
        }
    }

    private boolean isAnimationsDisabled() {
        return android.provider.Settings.Global.getFloat(getContext().getContentResolver(),
                                                         android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f) == 0.0f;
    }

    public void clearKeyboardCache() {
        if (!initialized) {
            return;
        }

        mEmojiCategory.clearKeyboardCache();
        if (mPager.getAdapter() instanceof EmojiPalettesAdapter) {
            ((EmojiPalettesAdapter) mPager.getAdapter()).updateItems();
        }
        closeDictionaryFacilitator();
    }

    private void initDictionaryFacilitator() {
        if (Settings.getValues().mShowEmojiDescriptions) {
            var locale = RichInputMethodManager.getInstance().getCurrentSubtype().getLocale();
            if (sDictionaryFacilitator == null || ! sDictionaryFacilitator.isForLocale(locale)) {
                closeDictionaryFacilitator();
                var dictFile = DictionaryInfoUtils.getCachedDictForLocaleAndType(locale, Dictionary.TYPE_EMOJI, getContext());
                var dictionary = dictFile != null? DictionaryFactory.getDictionary(dictFile, locale) : null;
                sDictionaryFacilitator = dictionary != null? new SingleDictionaryFacilitator(dictionary) : null;
            }
        } else {
            closeDictionaryFacilitator();
        }
    }

    public static void closeDictionaryFacilitator() {
        if (sDictionaryFacilitator != null) {
            sDictionaryFacilitator.closeDictionaries();
            sDictionaryFacilitator = null;
        }
    }

    private static class TabImageView extends ImageView {
        private final Paint mCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean mIsSelectedCategory = false;

        public TabImageView(Context context) {
            super(context);
            // Start faded by default
            setAlpha(0.45f);
        }

        public void setSelectedCategory(boolean selected, int accentColor) {
            mIsSelectedCategory = selected;
            mCirclePaint.setColor(accentColor);
            if (selected) {
                setAlpha(1.0f);
            } else {
                setAlpha(0.45f);
                // restore normal unselected tint via clearColorFilter
                clearColorFilter();
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mIsSelectedCategory) {
                float radius = Math.min(getWidth(), getHeight()) * 0.48f;
                canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, radius, mCirclePaint);
            }
            super.onDraw(canvas);
        }
    }
}
