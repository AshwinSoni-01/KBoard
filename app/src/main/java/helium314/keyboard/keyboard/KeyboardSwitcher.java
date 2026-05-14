/*
 * Copyright (C) 2008 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import helium314.keyboard.event.Event;
import helium314.keyboard.keyboard.KeyboardLayoutSet.KeyboardLayoutSetException;
import helium314.keyboard.keyboard.clipboard.ClipboardHistoryView;
import helium314.keyboard.keyboard.emoji.EmojiPalettesView;
import helium314.keyboard.keyboard.internal.AiWritingToolsView;
import helium314.keyboard.keyboard.internal.KeyboardState;
import helium314.keyboard.keyboard.internal.keyboard_parser.EmojiParserKt;
import helium314.keyboard.latin.InputView;
import helium314.keyboard.latin.KeyboardWrapperView;
import helium314.keyboard.latin.LatinIME;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.RichInputMethodManager;
import helium314.keyboard.latin.RichInputMethodSubtype;
import helium314.keyboard.latin.WordComposer;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsValues;
import helium314.keyboard.latin.suggestions.SuggestionStripView;
import helium314.keyboard.latin.utils.CapsModeUtils;
import helium314.keyboard.latin.utils.FoldableUtils;
import helium314.keyboard.latin.utils.KtxKt;
import helium314.keyboard.latin.utils.LanguageOnSpacebarUtils;
import helium314.keyboard.latin.utils.Log;
import helium314.keyboard.latin.utils.RecapitalizeMode;
import helium314.keyboard.latin.utils.ResourceUtils;
import helium314.keyboard.latin.utils.ScriptUtils;
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional;
import helium314.keyboard.latin.utils.ToolbarMode;

public final class KeyboardSwitcher implements KeyboardState.SwitchActions {
    private static final String TAG = KeyboardSwitcher.class.getSimpleName();

    private InputView mCurrentInputView;
    private KeyboardWrapperView mKeyboardViewWrapper;
    private View mMainKeyboardFrame;
    private MainKeyboardView mKeyboardView;
    private EmojiPalettesView mEmojiPalettesView;
    private View mEmojiTabStripView;
    private LinearLayout mClipboardStripView;
    private HorizontalScrollView mClipboardStripScrollView;
    private SuggestionStripView mSuggestionStripView;
    private FrameLayout mStripContainer;
    private ClipboardHistoryView mClipboardHistoryView;
    private AiWritingToolsView mAiWritingToolsView;
    private AccessPointMenuView mAccessPointMenuView;
    private TextView mFakeToastView;
    private HorizontalScrollView mPersistentEmojiRowScroll;
    private LinearLayout mPersistentEmojiRowContainer;
    private LatinIME mLatinIME;
    private RichInputMethodManager mRichImm;
    private boolean mIsHardwareAcceleratedDrawingEnabled;

    private KeyboardState mState;

    private KeyboardLayoutSet mKeyboardLayoutSet;

    private KeyboardTheme mKeyboardTheme;
    private Context mThemeContext;
    private int mCurrentUiMode;
    private int mCurrentOrientation;
    private int mCurrentDpi;
    private boolean mThemeNeedsReload;

    @SuppressLint("StaticFieldLeak") // this is a keyboard, we want to keep it alive in background
    private static final KeyboardSwitcher sInstance = new KeyboardSwitcher();

    public static KeyboardSwitcher getInstance() {
        return sInstance;
    }

    private KeyboardSwitcher() {
        // Intentional empty constructor for singleton.
    }

    public static void init(final LatinIME latinIme) {
        sInstance.initInternal(latinIme);
    }

    private void initInternal(final LatinIME latinIme) {
        mLatinIME = latinIme;
        mRichImm = RichInputMethodManager.getInstance();
        mState = new KeyboardState(this);
        mIsHardwareAcceleratedDrawingEnabled = mLatinIME.enableHardwareAcceleration();
    }

    public void updateKeyboardTheme(@NonNull Context displayContext) {
        final boolean themeUpdated = updateKeyboardThemeAndContextThemeWrapper(
                displayContext, KeyboardTheme.getKeyboardTheme(displayContext));
        if (themeUpdated) {
            Settings settings = Settings.getInstance();
            settings.loadSettings(displayContext, settings.getCurrent().mLocale, settings.getCurrent().mInputAttributes);
            if (mKeyboardView != null)
                mLatinIME.setInputView(onCreateInputView(displayContext, mIsHardwareAcceleratedDrawingEnabled));
        } else if (mCurrentInputView != null && mLatinIME.hasSuggestionStripView()
                    == (Settings.getValues().mToolbarMode == ToolbarMode.HIDDEN || mLatinIME.isEmojiSearch())) {
            mLatinIME.updateSuggestionStripView(mCurrentInputView);
        }
    }



    private boolean updateKeyboardThemeAndContextThemeWrapper(final Context context, final KeyboardTheme keyboardTheme) {
        final Resources res = context.getResources();
        if (mThemeNeedsReload
                || mThemeContext == null
                || !keyboardTheme.equals(mKeyboardTheme)
                || mCurrentDpi != res.getDisplayMetrics().densityDpi
                || mCurrentOrientation != res.getConfiguration().orientation
                || (mCurrentUiMode & Configuration.UI_MODE_NIGHT_MASK) != (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                || !mThemeContext.getResources().equals(res)
                || Settings.getValues().mColors.haveColorsChanged(context)) {
            mThemeNeedsReload = false;
            mKeyboardTheme = keyboardTheme;
            mThemeContext = new ContextThemeWrapper(context, keyboardTheme.mStyleId);
            mCurrentUiMode = res.getConfiguration().uiMode;
            mCurrentOrientation = res.getConfiguration().orientation;
            mCurrentDpi = res.getDisplayMetrics().densityDpi;
            KeyboardLayoutSet.onKeyboardThemeChanged();
            return true;
        }
        return false;
    }

    public void loadKeyboard(final EditorInfo editorInfo, final SettingsValues settingsValues,
            final int currentAutoCapsState, @Nullable final RecapitalizeMode currentRecapitalizeState,
            KeyboardLayoutSet.InternalAction internalAction) {
        final KeyboardLayoutSet.Builder builder = new KeyboardLayoutSet.Builder(
                mThemeContext, editorInfo);
        final int keyboardWidth = ResourceUtils.getKeyboardWidth(mThemeContext, settingsValues);
        final int keyboardHeight = ResourceUtils.getKeyboardHeight(mThemeContext.getResources(), settingsValues);
        final boolean oneHandedModeEnabled = settingsValues.mOneHandedModeEnabled;
        mKeyboardLayoutSet = builder.setKeyboardGeometry(keyboardWidth, keyboardHeight)
                .setSubtype(mRichImm.getCurrentSubtype())
                .setVoiceInputKeyEnabled(settingsValues.mShowsVoiceInputKey)
                .setNumberRowEnabled(settingsValues.mShowsNumberRow)
                .setNumberRowInSymbolsEnabled(settingsValues.mShowsNumberRowInSymbols)
                .setLanguageSwitchKeyEnabled(settingsValues.isLanguageSwitchKeyEnabled())
                .setEmojiKeyEnabled(settingsValues.mShowsEmojiKey)
                .setSplitLayoutEnabled(settingsValues.mIsSplitKeyboardEnabled)
                .setOneHandedModeEnabled(oneHandedModeEnabled)
                .setInternalAction(internalAction)
                .build();
        try {
            mState.onLoadKeyboard(currentAutoCapsState, currentRecapitalizeState, oneHandedModeEnabled);
        } catch (KeyboardLayoutSetException e) {
            Log.e(TAG, "loading keyboard failed: " + e.mKeyboardId, e.getCause());
            try {
                final InputMethodSubtype defaults = SubtypeUtilsAdditional.INSTANCE.createDefaultSubtype(mRichImm.getCurrentSubtypeLocale());
                mKeyboardLayoutSet = builder.setKeyboardGeometry(keyboardWidth, keyboardHeight)
                        .setSubtype(RichInputMethodSubtype.Companion.get(defaults))
                        .setVoiceInputKeyEnabled(settingsValues.mShowsVoiceInputKey)
                        .setNumberRowEnabled(settingsValues.mShowsNumberRow)
                        .setNumberRowInSymbolsEnabled(settingsValues.mShowsNumberRowInSymbols)
                        .setLanguageSwitchKeyEnabled(settingsValues.isLanguageSwitchKeyEnabled())
                        .setEmojiKeyEnabled(settingsValues.mShowsEmojiKey)
                        .setSplitLayoutEnabled(settingsValues.mIsSplitKeyboardEnabled)
                        .setOneHandedModeEnabled(oneHandedModeEnabled)
                        .build();
                mState.onLoadKeyboard(currentAutoCapsState, currentRecapitalizeState, oneHandedModeEnabled);
                showToast("error loading the keyboard, falling back to defaults", false);
            } catch (KeyboardLayoutSetException e2) {
                Log.e(TAG, "even fallback to defaults failed: " + e2.mKeyboardId, e2.getCause());
            }
        }
    }

    public void saveKeyboardState() {
        if (getKeyboard() != null || isShowingEmojiPalettes() || isShowingClipboardHistory()) {
            mState.onSaveKeyboardState();
        }
    }

    public void onHideWindow() {
        if (mKeyboardView != null) {
            mKeyboardView.onHideWindow();
        }
    }

    private void setKeyboard(final int keyboardId, @NonNull final KeyboardSwitchState toggleState) {
        // with a hardware keyboard we might get here without ever calling onCreateInputView, so don't crash
        if (mKeyboardView == null) return;

        // Make {@link MainKeyboardView} visible and hide {@link EmojiPalettesView}.
        final SettingsValues currentSettingsValues = Settings.getValues();
        setMainKeyboardFrame(currentSettingsValues, toggleState);
        // TODO: pass this object to setKeyboard instead of getting the current values.
        final MainKeyboardView keyboardView = mKeyboardView;
        final Keyboard oldKeyboard = keyboardView.getKeyboard();
        final Keyboard newKeyboard = mKeyboardLayoutSet.getKeyboard(keyboardId);
        keyboardView.setKeyboard(newKeyboard);
        mCurrentInputView.setKeyboardTopPadding(newKeyboard.mTopPadding);
        keyboardView.setKeyPreviewPopupEnabled(currentSettingsValues.mKeyPreviewPopupOn);
        keyboardView.updateShortcutKey(mRichImm.isShortcutImeReady());
        final boolean subtypeChanged = (oldKeyboard == null) || !newKeyboard.mId.mSubtype.equals(oldKeyboard.mId.mSubtype);
        final int languageOnSpacebarFormatType = LanguageOnSpacebarUtils.getLanguageOnSpacebarFormatType(newKeyboard.mId.mSubtype);
        final boolean hasMultipleEnabledIMEsOrSubtypes = mRichImm.hasMultipleEnabledIMEsOrSubtypes(true);
        keyboardView.startDisplayLanguageOnSpacebar(subtypeChanged, languageOnSpacebarFormatType, hasMultipleEnabledIMEsOrSubtypes);

        if (currentSettingsValues.needsToLookupSuggestions()
                                    && (currentSettingsValues.mInlineEmojiSearch || currentSettingsValues.mSuggestEmojis)) {
            EmojiParserKt.loadEmojiDefaultVersionsAndPopupSpecs(mThemeContext);
        }
        updatePersistentEmojiRow();
        if (mCurrentInputView != null) {
            mCurrentInputView.requestLayout();
        }
    }

    public Keyboard getKeyboard() {
        if (mKeyboardView != null) {
            return mKeyboardView.getKeyboard();
        }
        return null;
    }

    // TODO: Remove this method. Come up with a more comprehensive way to reset the keyboard layout
    // when a keyboard layout set doesn't get reloaded in LatinIME.onStartInputViewInternal().
    public void resetKeyboardStateToAlphabet(final int currentAutoCapsState,
            @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onResetKeyboardStateToAlphabet(currentAutoCapsState, currentRecapitalizeState);
    }

    public void onPressKey(final int code, final boolean isSinglePointer,
            final int currentAutoCapsState, @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onPressKey(code, isSinglePointer, currentAutoCapsState, currentRecapitalizeState);
    }

    public void onReleaseKey(final int code, final boolean withSliding,
            final int currentAutoCapsState, @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onReleaseKey(code, withSliding, currentAutoCapsState, currentRecapitalizeState);
    }

    public void onFinishSlidingInput(final int currentAutoCapsState,
            @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onFinishSlidingInput(currentAutoCapsState, currentRecapitalizeState);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setAlphabetKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setAlphabetManualShiftedKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetManualShiftedKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET_MANUAL_SHIFTED, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setAlphabetAutomaticShiftedKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetAutomaticShiftedKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET_AUTOMATIC_SHIFTED, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setAlphabetShiftLockedKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetShiftLockedKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCKED, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setAlphabetShiftLockShiftedKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetShiftLockShiftedKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCK_SHIFTED, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setSymbolsKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setSymbolsKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_SYMBOLS, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setSymbolsShiftedKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setSymbolsShiftedKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_SYMBOLS_SHIFTED, KeyboardSwitchState.SYMBOLS_SHIFTED);
    }

    public boolean isImeSuppressedByHardwareKeyboard(
            @NonNull final SettingsValues settingsValues,
            @NonNull final KeyboardSwitchState toggleState) {
        return settingsValues.mHasHardwareKeyboard && toggleState == KeyboardSwitchState.HIDDEN;
    }

    private void setMainKeyboardFrame(
            @NonNull final SettingsValues settingsValues,
            @NonNull final KeyboardSwitchState toggleState) {
        final int visibility = isImeSuppressedByHardwareKeyboard(settingsValues, toggleState) ? View.GONE : View.VISIBLE;
        final int stripVisibility = mLatinIME.hasSuggestionStripView()? View.VISIBLE : View.GONE;
        mStripContainer.setVisibility(stripVisibility);
        PointerTracker.switchTo(mKeyboardView);
        mKeyboardView.setVisibility(visibility);
        // The visibility of {@link #mKeyboardView} must be aligned with {@link #MainKeyboardFrame}.
        // @see #getVisibleKeyboardView() and
        // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
        mMainKeyboardFrame.setVisibility(visibility);
        mEmojiPalettesView.setVisibility(View.GONE);
        mEmojiPalettesView.stopEmojiPalettes();
        mEmojiTabStripView.setVisibility(View.GONE);
        mClipboardStripScrollView.setVisibility(View.GONE);
        mSuggestionStripView.setVisibility(stripVisibility);
        mClipboardHistoryView.setVisibility(View.GONE);
        mClipboardHistoryView.stopClipboardHistory();
        if (mAiWritingToolsView != null) {
            mAiWritingToolsView.setVisibility(View.GONE);
            mAiWritingToolsView.onClose();
        }
        if (mAccessPointMenuView != null) {
            mAccessPointMenuView.setVisibility(View.GONE);
        }
        updatePersistentEmojiRow();
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setEmojiKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setEmojiKeyboard");
        }
        updatePersistentEmojiRow();
        mMainKeyboardFrame.setVisibility(View.VISIBLE);
        mKeyboardView.setVisibility(View.GONE);

        // Start emoji palettes
        mEmojiPalettesView.startEmojiPalettes(mKeyboardView.getKeyVisualAttribute(),
                mLatinIME.getCurrentInputEditorInfo(), mLatinIME.mKeyboardActionListener);
        mEmojiPalettesView.setVisibility(View.VISIBLE);
        mEmojiTabStripView.setVisibility(View.VISIBLE);
        mStripContainer.setVisibility(getSecondaryStripVisibility());

        mSuggestionStripView.setVisibility(View.GONE);
        mClipboardStripScrollView.setVisibility(View.GONE);
        mClipboardHistoryView.setVisibility(View.GONE);
        if (mAiWritingToolsView != null) {
            mAiWritingToolsView.setVisibility(View.GONE);
            mAiWritingToolsView.onClose();
        }
        updatePersistentEmojiRow();
        if (mCurrentInputView != null) mCurrentInputView.requestLayout();
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setClipboardKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setClipboardKeyboard");
        }
        updatePersistentEmojiRow();
        mMainKeyboardFrame.setVisibility(View.VISIBLE);
        mKeyboardView.setVisibility(View.GONE);

        // Start clipboard
        mClipboardHistoryView.startClipboardHistory(mLatinIME.getClipboardHistoryManager(), mKeyboardView.getKeyVisualAttribute(),
                mLatinIME.getCurrentInputEditorInfo(), mLatinIME.mKeyboardActionListener);
        mClipboardHistoryView.setVisibility(View.VISIBLE);
        mStripContainer.setVisibility(getSecondaryStripVisibility());
        mClipboardStripScrollView.post(() -> mClipboardStripScrollView.fullScroll(HorizontalScrollView.FOCUS_RIGHT));
        mClipboardStripScrollView.setVisibility(View.VISIBLE);

        mEmojiTabStripView.setVisibility(View.GONE);
        mSuggestionStripView.setVisibility(View.GONE);
        mEmojiPalettesView.setVisibility(View.GONE);
        if (mAiWritingToolsView != null) {
            mAiWritingToolsView.setVisibility(View.GONE);
            mAiWritingToolsView.onClose();
        }
        if (mAccessPointMenuView != null) {
            mAccessPointMenuView.setVisibility(View.GONE);
        }
        updatePersistentEmojiRow();
        if (mCurrentInputView != null) mCurrentInputView.requestLayout();
    }

    @Override
    public void setAiToolsKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAiToolsKeyboard");
        }
        updatePersistentEmojiRow();
        mMainKeyboardFrame.setVisibility(View.VISIBLE);
        mKeyboardView.setVisibility(View.GONE);

        if (mAiWritingToolsView != null) {
            mAiWritingToolsView.onOpen(mLatinIME.getCurrentInputConnection());
            mAiWritingToolsView.setVisibility(View.VISIBLE);
        }
        mStripContainer.setVisibility(getSecondaryStripVisibility());

        mEmojiTabStripView.setVisibility(View.GONE);
        mSuggestionStripView.setVisibility(View.GONE);
        mEmojiPalettesView.setVisibility(View.GONE);
        mClipboardHistoryView.setVisibility(View.GONE);
        mClipboardStripScrollView.setVisibility(View.GONE);
        if (mAccessPointMenuView != null) {
            mAccessPointMenuView.setVisibility(View.GONE);
        }
        updatePersistentEmojiRow();
        if (mCurrentInputView != null) mCurrentInputView.requestLayout();
    }

    @Override
    public void setAccessPointKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAccessPointKeyboard");
        }
        updatePersistentEmojiRow();
        mMainKeyboardFrame.setVisibility(View.VISIBLE);
        mKeyboardView.setVisibility(View.GONE);
        mEmojiPalettesView.setVisibility(View.GONE);
        mClipboardHistoryView.setVisibility(View.GONE);
        if (mAiWritingToolsView != null) {
            mAiWritingToolsView.setVisibility(View.GONE);
            mAiWritingToolsView.onClose();
        }
        mEmojiTabStripView.setVisibility(View.GONE);
        mClipboardStripScrollView.setVisibility(View.GONE);

        if (mAccessPointMenuView != null) {
            mAccessPointMenuView.populateMenu();
            mAccessPointMenuView.setVisibility(View.VISIBLE);
        }
        mStripContainer.setVisibility(View.VISIBLE);
        if (mSuggestionStripView != null) {
            mSuggestionStripView.setVisibility(View.VISIBLE);
        }
        updatePersistentEmojiRow();
        if (mCurrentInputView != null) mCurrentInputView.requestLayout();
    }

    @Override
    public void setNumpadKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setNumpadKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_NUMPAD, KeyboardSwitchState.OTHER);
    }

    @Override
    public void toggleNumpad(final boolean withSliding, final int autoCapsFlags,
            @Nullable final RecapitalizeMode recapitalizeMode, final boolean forceReturnToAlpha) {
        if (DEBUG_ACTION) {
            Log.d(TAG, "toggleNumpad");
        }
        mState.toggleNumpad(withSliding, autoCapsFlags, recapitalizeMode, forceReturnToAlpha, true);
    }

    public enum KeyboardSwitchState {
        HIDDEN(-1),
        SYMBOLS_SHIFTED(KeyboardId.ELEMENT_SYMBOLS_SHIFTED),
        EMOJI(KeyboardId.ELEMENT_EMOJI_RECENTS),
        CLIPBOARD(KeyboardId.ELEMENT_CLIPBOARD),
        AI_TOOLS(KeyboardId.ELEMENT_AI_TOOLS),
        ACCESS_POINT(-1),
        OTHER(-1);

        final int mKeyboardId;

        KeyboardSwitchState(int keyboardId) {
            mKeyboardId = keyboardId;
        }
    }

    public KeyboardSwitchState getKeyboardSwitchState() {
        boolean hidden = !isShowingEmojiPalettes() && !isShowingClipboardHistory() && !isShowingAiWritingTools() && !isShowingAccessPointMenu()
                && (mKeyboardLayoutSet == null
                || mKeyboardView == null
                || !mKeyboardView.isShown());
        if (hidden) {
            return KeyboardSwitchState.HIDDEN;
        } else if (isShowingEmojiPalettes()) {
            return KeyboardSwitchState.EMOJI;
        } else if (isShowingClipboardHistory()) {
            return KeyboardSwitchState.CLIPBOARD;
        } else if (isShowingAiWritingTools()) {
            return KeyboardSwitchState.AI_TOOLS;
        } else if (isShowingAccessPointMenu()) {
            return KeyboardSwitchState.ACCESS_POINT;
        } else if (isShowingKeyboardId(KeyboardId.ELEMENT_SYMBOLS_SHIFTED)) {
            return KeyboardSwitchState.SYMBOLS_SHIFTED;
        }
        return KeyboardSwitchState.OTHER;
    }

    public void onToggleKeyboard(@NonNull final KeyboardSwitchState toggleState) {
        KeyboardSwitchState currentState = getKeyboardSwitchState();
        Log.w(TAG, "onToggleKeyboard() : Current = " + currentState + " : Toggle = " + toggleState);
        if (currentState == toggleState) {
            if (toggleState == KeyboardSwitchState.ACCESS_POINT) {
                setAlphabetKeyboard();
            } else {
                mLatinIME.stopShowingInputView();
                mLatinIME.hideWindow();
                setAlphabetKeyboard();
            }
        } else {
            mLatinIME.startShowingInputView(true);
            if (toggleState == KeyboardSwitchState.EMOJI) {
                setEmojiKeyboard();
            } else if (toggleState == KeyboardSwitchState.CLIPBOARD) {
                setClipboardKeyboard();
            } else if (toggleState == KeyboardSwitchState.AI_TOOLS) {
                setAiToolsKeyboard();
            } else if (toggleState == KeyboardSwitchState.ACCESS_POINT) {
                if (currentState == KeyboardSwitchState.CLIPBOARD || currentState == KeyboardSwitchState.EMOJI || currentState == KeyboardSwitchState.AI_TOOLS) {
                    Log.w(TAG, "Ignoring ACCESS_POINT toggle because current state is " + currentState);
                    return;
                }
                setAccessPointKeyboard();
            } else {
                mMainKeyboardFrame.setVisibility(View.VISIBLE);
                mKeyboardView.setVisibility(View.VISIBLE);
                setKeyboard(toggleState.mKeyboardId, toggleState);

                mEmojiPalettesView.stopEmojiPalettes();
                mEmojiPalettesView.setVisibility(View.GONE);

                mClipboardHistoryView.stopClipboardHistory();
                mClipboardHistoryView.setVisibility(View.GONE);

                if (mAiWritingToolsView != null) {
                    mAiWritingToolsView.setVisibility(View.GONE);
                    mAiWritingToolsView.onClose();
                }

                if (mAccessPointMenuView != null) {
                    mAccessPointMenuView.setVisibility(View.GONE);
                }

                if (mCurrentInputView != null) {
                    mCurrentInputView.requestLayout();
                }
            }
        }
    }

    // Future method for requesting an updating to the shift state.
    @Override
    public void requestUpdatingShiftState(final int autoCapsFlags, @Nullable final RecapitalizeMode recapitalizeMode) {
        if (DEBUG_ACTION) {
            Log.d(TAG, "requestUpdatingShiftState: "
                    + " autoCapsFlags=" + CapsModeUtils.flagsToString(autoCapsFlags)
                    + " recapitalizeMode=" + recapitalizeMode);
        }
        mState.onUpdateShiftState(autoCapsFlags, recapitalizeMode);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void startDoubleTapShiftKeyTimer() {
        if (DEBUG_TIMER_ACTION) {
            Log.d(TAG, "startDoubleTapShiftKeyTimer");
        }
        final MainKeyboardView keyboardView = getMainKeyboardView();
        if (keyboardView != null) {
            keyboardView.startDoubleTapShiftKeyTimer();
        }
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void cancelDoubleTapShiftKeyTimer() {
        if (DEBUG_TIMER_ACTION) {
            Log.d(TAG, "setAlphabetKeyboard");
        }
        final MainKeyboardView keyboardView = getMainKeyboardView();
        if (keyboardView != null) {
            keyboardView.cancelDoubleTapShiftKeyTimer();
        }
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setOneHandedModeEnabled(boolean enabled) {
        setOneHandedModeEnabled(enabled, false);
    }

    public void setOneHandedModeEnabled(boolean enabled, boolean force) {
        if (!force && mKeyboardViewWrapper.getOneHandedModeEnabled() == enabled) {
            return;
        }
        final Settings settings = Settings.getInstance();
        mKeyboardViewWrapper.setOneHandedModeEnabled(enabled);
        mKeyboardViewWrapper.setOneHandedGravity(settings.getCurrent().mOneHandedModeGravity);

        settings.writeOneHandedModeEnabled(enabled);
        reloadKeyboard();
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void switchOneHandedMode() {
        mKeyboardViewWrapper.switchOneHandedModeSide();
        Settings.getInstance().writeOneHandedModeGravity(mKeyboardViewWrapper.getOneHandedGravity());
    }

    public void toggleSplitKeyboardMode() {
        final Settings settings = Settings.getInstance();
        settings.writeSplitKeyboardEnabled(
            !settings.getCurrent().mIsSplitKeyboardEnabled,
            mCurrentOrientation == Configuration.ORIENTATION_LANDSCAPE,
            FoldableUtils.INSTANCE.isFolded()
        );
        setOneHandedModeEnabled(settings.getCurrent().mOneHandedModeEnabled, true);
        reloadKeyboard();
    }

    public void reloadKeyboard() {
        if (mCurrentInputView == null)
            return;
        mEmojiPalettesView.clearKeyboardCache();
        reloadMainKeyboard();
    }

    public void reloadMainKeyboard() {
        // Reload the entire keyboard, and switch to the previous layout
        final boolean wasEmoji = isShowingEmojiPalettes();
        final boolean wasClipboard = isShowingClipboardHistory();
        loadKeyboard(mLatinIME.getCurrentInputEditorInfo(), Settings.getValues(),
                mLatinIME.getCurrentAutoCapsState(), mLatinIME.getCurrentRecapitalizeState(), null);
        if (wasEmoji) {
            setEmojiKeyboard();
        } else if (wasClipboard) {
            setClipboardKeyboard();
        }
    }

    /**
     * Displays a toast message.
     *
     * @param text The text to display in the toast message.
     * @param briefToast If true, the toast duration will be short; otherwise, it will last longer.
     */
    public void showToast(final String text, final boolean briefToast){
        // In API 32 and below, toasts can be shown without a notification permission.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            final int toastLength = briefToast ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG;
            final Toast toast = Toast.makeText(mLatinIME, text, toastLength);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
        } else {
            final int toastLength = briefToast ? 2000 : 3500;
            showFakeToast(text, toastLength);
        }
    }

    private static int getSecondaryStripVisibility() {
        return Settings.getValues().mSecondaryStripVisible? View.VISIBLE : View.GONE;
    }

    // Displays a toast-like message with the provided text for a specified duration.
    private void showFakeToast(final String text, final int timeMillis) {
        if (mFakeToastView.getVisibility() == View.VISIBLE) return;

        final Drawable appIcon = mFakeToastView.getCompoundDrawables()[0];
        if (appIcon != null) {
            final int bound = mFakeToastView.getLineHeight();
            appIcon.setBounds(0, 0, bound, bound);
            mFakeToastView.setCompoundDrawables(appIcon, null, null, null);
        }
        mFakeToastView.setText(text);
        mFakeToastView.setVisibility(View.VISIBLE);
        mFakeToastView.bringToFront();
        mFakeToastView.startAnimation(AnimationUtils.loadAnimation(mLatinIME, R.anim.fade_in));

        mFakeToastView.postDelayed(() -> {
            mFakeToastView.startAnimation(AnimationUtils.loadAnimation(mLatinIME, R.anim.fade_out));
            mFakeToastView.setVisibility(View.GONE);
        }, timeMillis);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public boolean isInDoubleTapShiftKeyTimeout() {
        if (DEBUG_TIMER_ACTION) {
            Log.d(TAG, "isInDoubleTapShiftKeyTimeout");
        }
        final MainKeyboardView keyboardView = getMainKeyboardView();
        return keyboardView != null && keyboardView.isInDoubleTapShiftKeyTimeout();
    }

    /**
     * Updates state machine to figure out when to automatically switch back to the previous mode.
     */
    public void onEvent(final Event event, final int currentAutoCapsState,
            @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onEvent(event, currentAutoCapsState, currentRecapitalizeState);
    }

    public boolean isShowingKeyboardId(@NonNull int... keyboardIds) {
        if (mKeyboardView == null || !mKeyboardView.isShown()) {
            return false;
        }
        final Keyboard keyboard = mKeyboardView.getKeyboard();
        if (keyboard == null) // may happen when using hardware keyboard
            return false;
        int activeKeyboardId = keyboard.mId.mElementId;
        for (int keyboardId : keyboardIds) {
            if (activeKeyboardId == keyboardId) {
                return true;
            }
        }
        return false;
    }

    public boolean isShowingEmojiPalettes() {
        return mEmojiPalettesView != null && mEmojiPalettesView.isShown();
    }

    public boolean isShowingClipboardHistory() {
        return mClipboardHistoryView != null && mClipboardHistoryView.isShown();
    }

    public boolean isShowingAiWritingTools() {
        return mAiWritingToolsView != null && mAiWritingToolsView.isShown();
    }

    public boolean isShowingAccessPointMenu() {
        return mAccessPointMenuView != null && mAccessPointMenuView.isShown();
    }

    public boolean isShowingPopupKeysPanel() {
        if (isShowingEmojiPalettes() || isShowingClipboardHistory() || isShowingAiWritingTools() || isShowingAccessPointMenu()) {
            return false;
        }
        return mKeyboardView.isShowingPopupKeysPanel();
    }

    public boolean isShowingStripContainer() {
        return mStripContainer.isShown();
    }

    public EmojiPalettesView getEmojiPalettesView() {
        return mEmojiPalettesView;
    }

    public AccessPointMenuView getAccessPointMenuView() {
        return mAccessPointMenuView;
    }

    public View getVisibleKeyboardView() {
        if (isShowingEmojiPalettes()) {
            return mEmojiPalettesView;
        } else if (isShowingClipboardHistory()) {
            return mClipboardHistoryView;
        } else if (isShowingAiWritingTools()) {
            return mAiWritingToolsView;
        } else if (isShowingAccessPointMenu()) {
            return mAccessPointMenuView;
        }
        return mKeyboardView;
    }

    public View getWrapperView() {
        return mKeyboardViewWrapper;
    }

    public View getEmojiTabStrip() {
        return mEmojiTabStripView;
    }

    public LinearLayout getClipboardStrip() {
        return mClipboardStripView;
    }

    public MainKeyboardView getMainKeyboardView() {
        return mKeyboardView;
    }

    public MainKeyboardView getKeyboardView() {
        return mKeyboardView;
    }

    public FrameLayout getStripContainer() { return mStripContainer; }

    public View getClipboardHistoryView() { return mClipboardHistoryView; }

    public View getAiWritingToolsView() { return mAiWritingToolsView; }

    public void deallocateMemory() {
        if (mKeyboardView != null) {
            mKeyboardView.cancelAllOngoingEvents();
            mKeyboardView.deallocateMemory();
        }
        if (mEmojiPalettesView != null) {
            mEmojiPalettesView.stopEmojiPalettes();
        }
        if (mClipboardHistoryView != null) {
            mClipboardHistoryView.stopClipboardHistory();
        }
        if (mAiWritingToolsView != null) {
            mAiWritingToolsView.onClose();
        }
    }

    public void trimMemory() {
        if (mEmojiPalettesView != null) {
            mEmojiPalettesView.clearKeyboardCache();
        }
    }

    @SuppressLint("InflateParams")
    public View onCreateInputView(@NonNull Context displayContext, final boolean isHardwareAcceleratedDrawingEnabled) {
        if (mKeyboardView != null) {
            mKeyboardView.closing();
        }
        PointerTracker.clearOldViewData();
        final SharedPreferences prefs = KtxKt.prefs(displayContext);
        if (mSuggestionStripView != null)
            prefs.unregisterOnSharedPreferenceChangeListener(mSuggestionStripView);
        if (mClipboardHistoryView != null)
            prefs.unregisterOnSharedPreferenceChangeListener(mClipboardHistoryView);
        if (mThemeNeedsReload) // necessary in some cases (e.g. theme switch) when mThemeNeedsReload is set before first keyboard load
            Settings.getInstance().loadSettings(displayContext, Settings.getValues().mLocale, Settings.getValues().mInputAttributes);

        updateKeyboardThemeAndContextThemeWrapper(displayContext, KeyboardTheme.getKeyboardTheme(displayContext));
        mCurrentInputView = (InputView)LayoutInflater.from(mThemeContext).inflate(R.layout.input_view, null);
        mMainKeyboardFrame = mCurrentInputView.findViewById(R.id.main_keyboard_frame);
        mEmojiPalettesView = mCurrentInputView.findViewById(R.id.emoji_palettes_view);
        mClipboardHistoryView = mCurrentInputView.findViewById(R.id.clipboard_history_view);
        mAiWritingToolsView = mCurrentInputView.findViewById(R.id.ai_writing_tools_view);
        mAccessPointMenuView = mCurrentInputView.findViewById(R.id.access_point_menu_view);
        mFakeToastView = mCurrentInputView.findViewById(R.id.fakeToast);

        mKeyboardViewWrapper = mCurrentInputView.findViewById(R.id.keyboard_view_wrapper);
        mKeyboardViewWrapper.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mKeyboardView = mCurrentInputView.findViewById(R.id.keyboard_view);
        mKeyboardView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
        mKeyboardView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mEmojiPalettesView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
        mEmojiPalettesView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mClipboardHistoryView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
        mClipboardHistoryView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        if (mAiWritingToolsView != null) {
            mAiWritingToolsView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
            mAiWritingToolsView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        }
        if (mAccessPointMenuView != null) {
            mAccessPointMenuView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        }
        mEmojiTabStripView = mCurrentInputView.findViewById(R.id.emoji_tab_strip);
        mClipboardStripView = mCurrentInputView.findViewById(R.id.clipboard_strip);
        mClipboardStripScrollView = mCurrentInputView.findViewById(R.id.clipboard_strip_scroll_view);
        mSuggestionStripView = mCurrentInputView.findViewById(R.id.suggestion_strip_view);
        mStripContainer = mCurrentInputView.findViewById(R.id.strip_container);
        mPersistentEmojiRowScroll = mCurrentInputView.findViewById(R.id.persistent_emoji_row_scroll);
        mPersistentEmojiRowContainer = mCurrentInputView.findViewById(R.id.persistent_emoji_row_container);

        if (mMainKeyboardFrame instanceof ViewGroup) {
            ((ViewGroup) mMainKeyboardFrame).setLayoutTransition(null);
        }
        if (mCurrentInputView != null) {
            mCurrentInputView.setLayoutTransition(null);
        }
        if (mStripContainer != null) {
            mStripContainer.setLayoutTransition(null);
        }

        prefs.registerOnSharedPreferenceChangeListener(mSuggestionStripView);
        prefs.registerOnSharedPreferenceChangeListener(mClipboardHistoryView);
        PointerTracker.switchTo(mKeyboardView);
        return mCurrentInputView;
    }

    public int getKeyboardShiftMode() {
        final Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            return WordComposer.CAPS_MODE_OFF;
        }
        return keyboard.mId.getKeyboardCapsMode();
    }

    public String getCurrentKeyboardScript() {
        if (null == mKeyboardLayoutSet) {
            return ScriptUtils.SCRIPT_UNKNOWN;
        }
        return mKeyboardLayoutSet.getScript();
    }

    public void switchToSubtype(InputMethodSubtype subtype) {
        mLatinIME.switchToSubtype(subtype);
    }

    // used for debug
    public String getLocaleAndConfidenceInfo() {
        return mLatinIME.getLocaleAndConfidenceInfo();
    }

    public void updatePersistentEmojiRow() {
        if (mPersistentEmojiRowScroll == null || mPersistentEmojiRowContainer == null || mCurrentInputView == null) {
            return;
        }
        final android.content.SharedPreferences prefs = KtxKt.prefs(mCurrentInputView.getContext());
        final boolean enabled = prefs.getBoolean(Settings.PREF_PERSISTENT_EMOJI_ROW, helium314.keyboard.latin.settings.Defaults.PREF_PERSISTENT_EMOJI_ROW);
        final View divider = mMainKeyboardFrame != null ? mMainKeyboardFrame.findViewById(R.id.persistent_emoji_row_divider) : null;
        final KeyboardSwitchState state = getKeyboardSwitchState();
        final boolean inPanel = state == KeyboardSwitchState.EMOJI || state == KeyboardSwitchState.CLIPBOARD || state == KeyboardSwitchState.AI_TOOLS || state == KeyboardSwitchState.ACCESS_POINT || (mEmojiPalettesView != null && mEmojiPalettesView.getVisibility() == View.VISIBLE) || (mClipboardHistoryView != null && mClipboardHistoryView.getVisibility() == View.VISIBLE) || (mAiWritingToolsView != null && mAiWritingToolsView.getVisibility() == View.VISIBLE) || (mAccessPointMenuView != null && mAccessPointMenuView.getVisibility() == View.VISIBLE);
        if (!enabled || mMainKeyboardFrame == null || mMainKeyboardFrame.getVisibility() != View.VISIBLE) {
            mPersistentEmojiRowScroll.setVisibility(View.GONE);
            if (divider != null) divider.setVisibility(View.GONE);
            return;
        }
        if (inPanel) {
            mPersistentEmojiRowScroll.setVisibility(View.GONE);
            if (divider != null) divider.setVisibility(View.GONE);
            return;
        }
        mPersistentEmojiRowScroll.setVisibility(View.VISIBLE);
        if (divider != null) divider.setVisibility(View.VISIBLE);

        final java.util.List<String> rawEmojis = helium314.keyboard.keyboard.emoji.EmojiPalettesView.AdaptiveEmojiEngine.getRankedEmojis(mPersistentEmojiRowContainer.getContext());
        final java.util.List<String> emojis = new java.util.ArrayList<>();
        if (rawEmojis.size() >= 10) {
            // (6, 7, 8, 9, 10) on the left, (1, 2, 3, 4, 5) on the right just like old project
            emojis.addAll(rawEmojis.subList(5, 10));
            emojis.addAll(rawEmojis.subList(0, 5));
        } else {
            emojis.addAll(rawEmojis);
        }

        mPersistentEmojiRowContainer.removeAllViews();
        final android.content.Context context = mPersistentEmojiRowContainer.getContext();
        final SettingsValues sv = Settings.getValues();
        final int kbWidth = sv != null ? helium314.keyboard.latin.utils.ResourceUtils.getKeyboardWidth(context, sv) : context.getResources().getDisplayMetrics().widthPixels;
        final int itemWidth = kbWidth / 10;
        final int height = (int) (36 * context.getResources().getDisplayMetrics().density);

        for (final String emoji : emojis) {
            final TextView tv = new TextView(context);
            tv.setText(emoji);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f);
            tv.setGravity(Gravity.CENTER);
            tv.setLayoutParams(new LinearLayout.LayoutParams(itemWidth, height));
            tv.setClickable(true);
            tv.setFocusable(true);
            final android.util.TypedValue outValue = new android.util.TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
            tv.setBackgroundResource(outValue.resourceId);
            tv.setOnClickListener(v -> {
                if (mLatinIME != null && mLatinIME.mKeyboardActionListener != null) {
                    mLatinIME.mKeyboardActionListener.onTextInput(emoji);
                    if (mEmojiPalettesView != null) {
                        mEmojiPalettesView.addRecentEmoji(emoji);
                        updatePersistentEmojiRow();
                    }
                }
            });
            mPersistentEmojiRowContainer.addView(tv);
        }

        // Add the polished right-side "Remove Row" button from old project
        final LinearLayout removeBtn = new LinearLayout(context);
        removeBtn.setOrientation(LinearLayout.HORIZONTAL);
        removeBtn.setGravity(Gravity.CENTER);
        final int paddingH = (int) (12 * context.getResources().getDisplayMetrics().density);
        final int paddingV = (int) (6 * context.getResources().getDisplayMetrics().density);
        final int marginH = (int) (16 * context.getResources().getDisplayMetrics().density);
        removeBtn.setPadding(paddingH, paddingV, paddingH, paddingV);

        final LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int) (32 * context.getResources().getDisplayMetrics().density));
        removeParams.gravity = Gravity.CENTER_VERTICAL;
        removeParams.leftMargin = marginH;
        removeParams.rightMargin = marginH;
        removeBtn.setLayoutParams(removeParams);

        final android.graphics.drawable.GradientDrawable removeBg = new android.graphics.drawable.GradientDrawable();
        removeBg.setColor(0x20808080); // subtle semi-transparent background matching t.keySurface.copy(alpha = 0.3f)
        removeBg.setCornerRadius(16 * context.getResources().getDisplayMetrics().density);
        removeBtn.setBackground(removeBg);
        removeBtn.setClickable(true);
        removeBtn.setFocusable(true);
        removeBtn.setOnClickListener(v -> {
            prefs.edit().putBoolean(Settings.PREF_PERSISTENT_EMOJI_ROW, false).apply();
            updatePersistentEmojiRow();
            if (mCurrentInputView != null) mCurrentInputView.requestLayout();
        });

        final TextView removeText = new TextView(context);
        removeText.setText("Remove row");
        removeText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        removeText.setGravity(Gravity.CENTER);
        removeText.setTextColor(0xFF808080); // subtle gray text matching t.keyText.copy(alpha = 0.6f)
        removeBtn.addView(removeText);

        mPersistentEmojiRowContainer.addView(removeBtn);
    }

    public boolean isShowingPersistentEmojiRow() {
        return mPersistentEmojiRowScroll != null && mPersistentEmojiRowScroll.getVisibility() == View.VISIBLE;
    }

    public int getPersistentEmojiRowHeight() {
        if (mPersistentEmojiRowScroll == null) return 0;
        float density = mPersistentEmojiRowScroll.getContext().getResources().getDisplayMetrics().density;
        return (int) (41 * density);
    }

    /** Marks the theme as outdated. The theme will be reloaded next time the keyboard is shown.
     *  If the keyboard is currently showing, theme will be reloaded immediately. */
    public void setThemeNeedsReload() {
        mThemeNeedsReload = true;
        if (mLatinIME == null || !mLatinIME.isInputViewShown())
            return; // will be reloaded right before showing IME

        // Hide and show IME, showing will trigger the reload.
        // Reloading while IME is shown is glitchy, and hiding / showing is so fast the user shouldn't notice.
        mLatinIME.hideWindow();
        try {
            mLatinIME.showWindow(true);
        } catch (IllegalStateException e) {
            // in tests isInputViewShown returns true, but showWindow throws "IllegalStateException: Window token is not set yet."
        }
    }

    public void updateLiveFrostedGlassColors() {
        if (mCurrentInputView == null) return;
        final Settings settings = Settings.getInstance();
        final SettingsValues oldValues = Settings.getValues();
        if (oldValues != null) {
            settings.loadSettings(mCurrentInputView.getContext(), oldValues.mLocale, oldValues.mInputAttributes);
        }
        final SettingsValues settingsValues = Settings.getValues();
        if (settingsValues == null) return;
        final helium314.keyboard.latin.common.Colors colors = settingsValues.mColors;
        if (colors == null) return;

        // 1. Update mMainKeyboardFrame background
        if (mMainKeyboardFrame != null) {
            colors.setBackground(mMainKeyboardFrame, helium314.keyboard.latin.common.ColorType.MAIN_BACKGROUND);
            mMainKeyboardFrame.invalidate();
        }
        if (mCurrentInputView != null) {
            mCurrentInputView.invalidate();
        }

        // 2. Update mKeyboardView theme colors and force a redraw
        if (mKeyboardView != null) {
            mKeyboardView.updateThemeColors(colors);
        }

        // 3. Update mSuggestionStripView background and keys
        if (mSuggestionStripView != null) {
            mSuggestionStripView.updateThemeColors(colors);
        }

        // 4. Update the soft window background blur radius
        if (mLatinIME != null) {
            helium314.keyboard.latin.FrostedGlassHelper.configureFrostedGlass(mLatinIME, mCurrentInputView, helium314.keyboard.latin.FrostedGlassHelper.isFrostedTheme(mLatinIME));
        }
    }
}
