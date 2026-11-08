/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.futo.inputmethod.latin;

import static org.futo.inputmethod.latin.common.Constants.ImeOption.FORCE_ASCII;
import static org.futo.inputmethod.latin.common.Constants.ImeOption.NO_MICROPHONE;
import static org.futo.inputmethod.latin.common.Constants.ImeOption.NO_MICROPHONE_COMPAT;

import android.Manifest.permission;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.os.Build;
import android.os.Debug;
import android.os.IBinder;
import android.text.InputType;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.SparseArray;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodSubtype;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.futo.inputmethod.accessibility.AccessibilityUtils;
import org.futo.inputmethod.annotations.UsedForTesting;
import org.futo.inputmethod.compat.ViewOutlineProviderCompatUtils;
import org.futo.inputmethod.compat.ViewOutlineProviderCompatUtils.InsetsUpdater;
import org.futo.inputmethod.engine.ExpandableSuggestionBarConfiguration;
import org.futo.inputmethod.engine.IMEInterface;
import org.futo.inputmethod.engine.IMEManager;
import org.futo.inputmethod.event.Event;
import org.futo.inputmethod.event.HardwareEventDecoder;
import org.futo.inputmethod.event.HardwareKeyboardEventDecoder;
import org.futo.inputmethod.event.InputTransaction;
import org.futo.inputmethod.keyboard.Keyboard;
import org.futo.inputmethod.keyboard.KeyboardActionListener;
import org.futo.inputmethod.keyboard.KeyboardSwitcher;
import org.futo.inputmethod.keyboard.MainKeyboardView;
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo;
import org.futo.inputmethod.latin.common.Constants;
import org.futo.inputmethod.latin.common.CoordinateUtils;
import org.futo.inputmethod.latin.common.InputPointers;
import org.futo.inputmethod.latin.define.DebugFlags;
import org.futo.inputmethod.latin.define.ProductionFlags;
import org.futo.inputmethod.latin.permissions.PermissionsManager;
import org.futo.inputmethod.latin.personalization.PersonalizationHelper;
import org.futo.inputmethod.latin.settings.Settings;
import org.futo.inputmethod.latin.settings.SettingsValues;
import org.futo.inputmethod.latin.suggestions.SuggestionStripViewAccessor;
import org.futo.inputmethod.latin.suggestions.SuggestionStripViewListener;
import org.futo.inputmethod.latin.utils.ApplicationUtils;
import org.futo.inputmethod.latin.utils.JniUtils;
import org.futo.inputmethod.latin.utils.StatsUtils;
import org.futo.inputmethod.latin.utils.ViewLayoutUtils;
import org.futo.inputmethod.v2keyboard.KeyboardLayoutSetV2;
import org.jetbrains.annotations.NotNull;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Input method implementation for Qwerty'ish keyboard.
 */
public class LatinIMELegacy implements KeyboardActionListener,
        SuggestionStripViewListener, SuggestionStripViewAccessor,
        PermissionsManager.PermissionsResultCallback {

    public interface SuggestionStripController {
        public void updateVisibility(boolean shouldShowSuggestionsStrip, boolean fullscreenMode);
        public void setSuggestions(SuggestedWords suggestedWords, boolean rtlSubtype, ExpandableSuggestionBarConfiguration cfg);
    }
    
    private final InputMethodService mInputMethodService;
    private final IMEManager mImeManager;

    static final String TAG = LatinIMELegacy.class.getSimpleName();
    private static final boolean TRACE = false;

    private static final int PERIOD_FOR_AUDIO_AND_HAPTIC_FEEDBACK_IN_KEY_REPEAT = 2;

    private static final String SCHEME_PACKAGE = "package";

    public final Settings mSettings;
    private Locale mLocale;
    final SparseArray<HardwareEventDecoder> mHardwareEventDecoders = new SparseArray<>(1);

    private View mInputView;
    private View mComposeInputView;
    private InsetsUpdater mInsetsUpdater;

    public RichInputMethodManager mRichImm;
    public final KeyboardSwitcher mKeyboardSwitcher;
    private EmojiAltPhysicalKeyDetector mEmojiAltPhysicalKeyDetector;

    @Nullable private Context mDisplayContext;

    private final BroadcastReceiver mDictionaryDumpBroadcastReceiver =
            new DictionaryDumpBroadcastReceiver(this);

    private final boolean mIsHardwareAcceleratedDrawingEnabled;

    static {
        JniUtils.loadNativeLibrary();
    }

    public LatinIMELegacy(InputMethodService inputMethodService, SuggestionStripController suggestionStripController) {
        super();
        mInputMethodService = inputMethodService;
        mSettings = Settings.getInstance();
        mKeyboardSwitcher = KeyboardSwitcher.getInstance();
        mIsHardwareAcceleratedDrawingEnabled = true;
        Log.i(TAG, "Hardware accelerated drawing: " + mIsHardwareAcceleratedDrawingEnabled);

        mImeManager = ((LatinIME)inputMethodService).getImeManager();
    }

    public void onCreate() {
        Settings.init(mInputMethodService);
        RichInputMethodManager.init(mInputMethodService);
        mRichImm = RichInputMethodManager.getInstance();
        AudioAndHapticFeedbackManager.init(mInputMethodService);
        AccessibilityUtils.init(mInputMethodService);
        final WindowManager wm = mInputMethodService.getSystemService(WindowManager.class);
        mDisplayContext = getDisplayContext();
        KeyboardSwitcher.init(this);

        loadSettings();

        final IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        ContextCompat.registerReceiver(mInputMethodService, mRingerModeChangeReceiver, filter, ContextCompat.RECEIVER_EXPORTED);

        final IntentFilter dictDumpFilter = new IntentFilter();
        dictDumpFilter.addAction(DictionaryDumpBroadcastReceiver.DICTIONARY_DUMP_INTENT_ACTION);
        ContextCompat.registerReceiver(mInputMethodService, mDictionaryDumpBroadcastReceiver, dictDumpFilter, ContextCompat.RECEIVER_NOT_EXPORTED);

        StatsUtils.onCreate(mSettings.getCurrent(), mRichImm);
    }

    public void loadSettings() {
        mLocale = mRichImm.getCurrentSubtypeLocale();
        final EditorInfo editorInfo = mInputMethodService.getCurrentInputEditorInfo();
        final InputAttributes inputAttributes = new InputAttributes(
                editorInfo, mInputMethodService.isFullscreenMode(), mInputMethodService.getPackageName());
        mSettings.loadSettings(mInputMethodService, mLocale, inputAttributes);
        final SettingsValues currentSettingsValues = mSettings.getCurrent();
        AudioAndHapticFeedbackManager.getInstance().onSettingsChanged(currentSettingsValues);
        refreshPersonalizationDictionarySession(currentSettingsValues);
    }

    private boolean mLastPersonalizedDicts = true;
    private void refreshPersonalizationDictionarySession(
            final SettingsValues currentSettingsValues) {
        if (!currentSettingsValues.mUsePersonalizedDicts && mLastPersonalizedDicts) {
            PersonalizationHelper.removeAllUserHistoryDictionaries(mInputMethodService);
            mImeManager.clearUserHistoryDictionaries();
        }

        mLastPersonalizedDicts = currentSettingsValues.mUsePersonalizedDicts;
    }

    public void onDestroy() {
        mSettings.onDestroy();
        mInputMethodService.unregisterReceiver(mRingerModeChangeReceiver);
        mInputMethodService.unregisterReceiver(mDictionaryDumpBroadcastReceiver);
    }

    @UsedForTesting
    public void recycle() {
        mInputMethodService.unregisterReceiver(mDictionaryDumpBroadcastReceiver);
        mInputMethodService.unregisterReceiver(mRingerModeChangeReceiver);
        mImeManager.recycle();
    }

    public boolean isImeSuppressedByHardwareKeyboard() {
        if(true) return false;
        final KeyboardSwitcher switcher = KeyboardSwitcher.getInstance();
        return !onEvaluateInputViewShown() && switcher.isImeSuppressedByHardwareKeyboard(
                mSettings.getCurrent(), switcher.getKeyboardSwitchState());
    }

    public void onConfigurationChanged(final Configuration conf) {
        SettingsValues settingsValues = mSettings.getCurrent();
        if (settingsValues.mDisplayOrientation != conf.orientation) {
            if(mInputMethodService.isInputViewShown()) mKeyboardSwitcher.saveKeyboardState();
        }
        if (settingsValues.mHasHardwareKeyboard != Settings.readHasHardwareKeyboard(conf)) {
            loadSettings();
        }
    }

    public void onInitializeInterface() {
        mDisplayContext = getDisplayContext();
        mKeyboardSwitcher.updateKeyboardTheme(mDisplayContext);
    }

    private @NonNull Context getDisplayContext() {
        return mInputMethodService;
    }

    public View onCreateInputView() {
        StatsUtils.onCreateInputView();
        assert mDisplayContext != null;
        return mKeyboardSwitcher.onCreateInputView(mDisplayContext,
                mIsHardwareAcceleratedDrawingEnabled);
    }

    public void updateTheme() {
        mKeyboardSwitcher.queueThemeSwitch();
        mKeyboardSwitcher.updateKeyboardTheme(mDisplayContext);
    }

    public void setComposeInputView(final View view) {
        mComposeInputView = view;
        mInsetsUpdater = ViewOutlineProviderCompatUtils.setInsetsOutlineProvider(view);
        updateSoftInputWindowLayoutParameters();
    }

    public void setInputView(final View view) {
        mInputView = view;
    }

    public void setCandidatesView(final View view) {
    }

    public void onStartInput(final EditorInfo editorInfo, final boolean restarting) {
        onStartInputInternal(editorInfo, restarting);
    }

    public void onStartInputView(final EditorInfo editorInfo, final boolean restarting) {
        onStartInputViewInternal(editorInfo, restarting);
    }

    public void onFinishInputView(final boolean finishingInput) {
        StatsUtils.onFinishInputView();
        onFinishInputViewInternal(finishingInput);
    }

    public void onFinishInput() {
        onFinishInputInternal();
    }

    public void onCurrentInputMethodSubtypeChanged(final InputMethodSubtype subtype) {
        mImeManager.onFinishInput();
        InputMethodSubtype oldSubtype = mRichImm.getCurrentSubtype().getRawSubtype();
        StatsUtils.onSubtypeChanged(oldSubtype, subtype);
        mRichImm.onSubtypeChanged(subtype);
        loadSettings();
        mImeManager.onStartInput();
        loadKeyboard();
    }

    void onStartInputInternal(final EditorInfo editorInfo, final boolean restarting) {
    }

    public void updateMainKeyboardViewSettings() {
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        SettingsValues currentSettingsValues = mSettings.getCurrent();

        mainKeyboardView.setImeAllowsGestureInput(
                mImeManager.getActiveIME(currentSettingsValues).isGestureHandlingAvailable());
        mainKeyboardView.setKeyPreviewPopupEnabled(currentSettingsValues.mKeyPreviewPopupOn,
                currentSettingsValues.mKeyPreviewPopupDismissDelay);
        mainKeyboardView.setSlidingKeyInputPreviewEnabled(
                currentSettingsValues.mSlidingKeyInputPreviewEnabled);
        mainKeyboardView.setGestureHandlingEnabledByUser(
                currentSettingsValues.mGestureInputEnabled,
                currentSettingsValues.mGestureTrailEnabled,
                currentSettingsValues.mGestureFloatingPreviewTextEnabled);
    }

    @SuppressWarnings("deprecation")
    void onStartInputViewInternal(final EditorInfo editorInfo, final boolean restarting) {
        mRichImm.refreshSubtypeCaches();
        final KeyboardSwitcher switcher = mKeyboardSwitcher;
        switcher.updateKeyboardTheme(mDisplayContext);
        final MainKeyboardView mainKeyboardView = switcher.getMainKeyboardView();
        SettingsValues currentSettingsValues = mSettings.getCurrent();

        if (editorInfo == null) {
            Log.e(TAG, "Null EditorInfo in onStartInputView()");
            if (DebugFlags.DEBUG_ENABLED) {
                throw new NullPointerException("Null EditorInfo in onStartInputView()");
            }
            return;
        }
        if (DebugFlags.DEBUG_ENABLED) {
            Log.d(TAG, "onStartInputView: editorInfo:"
                    + String.format("inputType=0x%08x imeOptions=0x%08x",
                            editorInfo.inputType, editorInfo.imeOptions));
            Log.d(TAG, "All caps = "
                    + ((editorInfo.inputType & InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0)
                    + ", sentence caps = "
                    + ((editorInfo.inputType & InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0)
                    + ", word caps = "
                    + ((editorInfo.inputType & InputType.TYPE_TEXT_FLAG_CAP_WORDS) != 0));
        }
        Log.i(TAG, "Starting input. Cursor position = "
                + editorInfo.initialSelStart + "," + editorInfo.initialSelEnd);

        if (InputAttributes.inPrivateImeOptions(null, NO_MICROPHONE_COMPAT, editorInfo)) {
            Log.w(TAG, "Deprecated private IME option specified: " + editorInfo.privateImeOptions);
        }
        if (InputAttributes.inPrivateImeOptions(mInputMethodService.getPackageName(), FORCE_ASCII, editorInfo)) {
            Log.w(TAG, "Deprecated private IME option specified: " + editorInfo.privateImeOptions);
        }

        if (mainKeyboardView == null) {
            return;
        }

        final AccessibilityUtils accessUtils = AccessibilityUtils.getInstance();
        if (accessUtils.isTouchExplorationEnabled()) {
            accessUtils.onStartInputViewInternal(mainKeyboardView, editorInfo, restarting);
        }

        final boolean inputTypeChanged = !currentSettingsValues.isSameInputType(editorInfo);
        final boolean isDifferentTextField = !restarting || inputTypeChanged;

        updateFullscreenMode();

        if (isDifferentTextField ||
                !currentSettingsValues.hasSameOrientation(mInputMethodService.getResources().getConfiguration())) {
            loadSettings();
        }
        if (isDifferentTextField) {
            mainKeyboardView.closing();
            currentSettingsValues = mSettings.getCurrent();

            switcher.loadKeyboard(editorInfo, currentSettingsValues, getCurrentAutoCapsState());
        } else if (restarting) {
            switcher.resetKeyboardStateToAlphabet(editorInfo, getCurrentAutoCapsState());
            switcher.requestUpdatingShiftState(getCurrentAutoCapsState());
        }

        updateMainKeyboardViewSettings();

        if (TRACE) Debug.startMethodTracing("/data/trace/latinime");
    }

    public void onWindowShown() {
        setNavigationBarVisibility(mInputMethodService.isInputViewShown());
    }

    public void onWindowHidden() {
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
        setNavigationBarVisibility(false);
    }

    void onFinishInputInternal() {
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
    }

    void onFinishInputViewInternal(final boolean finishingInput) {
    }

    protected void deallocateMemory() {
        mKeyboardSwitcher.deallocateMemory();
    }

    public void onUpdateSelection(final int oldSelStart, final int oldSelEnd,
            final int newSelStart, final int newSelEnd,
            final int composingSpanStart, final int composingSpanEnd) {
        if (DebugFlags.DEBUG_ENABLED) {
            Log.i(TAG, "onUpdateSelection: oss=" + oldSelStart + ", ose=" + oldSelEnd
                    + ", nss=" + newSelStart + ", nse=" + newSelEnd
                    + ", cs=" + composingSpanStart + ", ce=" + composingSpanEnd);
        }

        final SettingsValues settingsValues = mSettings.getCurrent();
        if (mInputMethodService.isInputViewShown()) {
            mImeManager.onUpdateSelection(
                    oldSelStart, oldSelEnd,
                    newSelStart, newSelEnd,
                    composingSpanStart, composingSpanEnd
            );
        }
    }

    public void onExtractedTextClicked() {
        if (mSettings.getCurrent().needsToLookupSuggestions()) {
            return;
        }
    }

    public void onExtractedCursorMovement(final int dx, final int dy) {
        if (mSettings.getCurrent().needsToLookupSuggestions()) {
            return;
        }
    }

    public void hideWindow() {
        mKeyboardSwitcher.onHideWindow();
        if (TRACE) Debug.stopMethodTracing();
    }

    public void setInsets(final InputMethodService.Insets insets) {
        mInsetsUpdater.setInsets(insets);
    }

    public boolean onShowInputRequested(final int flags, final boolean configChange) {
        if (isImeSuppressedByHardwareKeyboard()) {
            return true;
        }
        return false;
    }

    public boolean onEvaluateInputViewShown() {
        return false;
    }

    public void updateFullscreenMode() {
        updateSoftInputWindowLayoutParameters();
    }

    private void updateSoftInputWindowLayoutParameters() {
        final Window window = mInputMethodService.getWindow().getWindow();
        ViewLayoutUtils.updateLayoutHeightOf(window, LayoutParams.MATCH_PARENT);
        if (mComposeInputView != null) {
            final int layoutHeight = mInputMethodService.isFullscreenMode()
                    ? LayoutParams.WRAP_CONTENT : LayoutParams.MATCH_PARENT;
            final View inputArea = window.findViewById(android.R.id.inputArea);

            if(inputArea != null) {
                ViewLayoutUtils.updateLayoutHeightOf(inputArea, layoutHeight);
                ViewLayoutUtils.updateLayoutGravityOf(inputArea, Gravity.BOTTOM);
            }

            ViewLayoutUtils.updateLayoutHeightOf(mComposeInputView, layoutHeight);
        }
    }

    int getCurrentAutoCapsState() {
        return getActiveIME().getCurrentAutoCapsState();
    }

    public int[] getCoordinatesForCurrentKeyboard(final int[] codePoints) {
        final Keyboard keyboard = mKeyboardSwitcher.getKeyboard();
        if (null == keyboard) {
            return CoordinateUtils.newCoordinateArray(codePoints.length,
                    Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE);
        }
        return keyboard.getCoordinates(codePoints);
    }

    @Override
    public void showImportantNoticeContents() {
        PermissionsManager.get(mInputMethodService).requestPermissions(
                this,
                null, permission.READ_CONTACTS);
    }

    @Override
    public void onRequestPermissionsResult(boolean allGranted) {
    }

    private boolean canDoLanguageSwitch() {
        SettingsValues settings = mSettings.getCurrent();

        return settings.mInputAttributes.mLocaleOverride == null
                && settings.mInputAttributes.mLayoutOverride == null;
    }

    @Override
    public boolean onCustomRequest(final int requestCode) {
        switch (requestCode) {
        case Constants.CUSTOM_CODE_SHOW_INPUT_METHOD_PICKER:
            if(!canDoLanguageSwitch()) return false;
            getLatinIME().getUixManager().showLanguageSwitcher();
            return true;
        }
        return false;
    }

    @Override
    public void onMovePointer(int steps) {
        mImeManager.getActiveIME(
                mSettings.getCurrent()
        ).onMovePointer(steps, false, null);
    }

    @Override
    public void onMoveDeletePointer(int steps) {
        mImeManager.getActiveIME(
                mSettings.getCurrent()
        ).onMoveDeletePointer(steps);
    }

    @Override
    public void onUpWithDeletePointerActive() {
        mImeManager.getActiveIME(
                mSettings.getCurrent()
        ).onUpWithDeletePointerActive();
    }

    @Override
    public void onUpWithPointerActive() {
        mImeManager.getActiveIME(
                mSettings.getCurrent()
        ).onUpWithPointerActive();
    }

    private float mSwipeLanguageProgress = 0.0f;
    @Override
    public void onSwipeLanguageReleased() {
        mKeyboardSwitcher.getMainKeyboardView().updateSwipeLanguageProgress(0.0f);
        if(!canDoLanguageSwitch()) {
            mSwipeLanguageProgress = 0.0f;
            return;
        }
        if(Math.abs(mSwipeLanguageProgress) >= 1.0f) {
            String newSubtype = Subtypes.INSTANCE.switchToNextLanguage(mInputMethodService, mSwipeLanguageProgress > 0.0f ? 1 : -1);
            if(newSubtype != null && !newSubtype.isEmpty()) {
                getLatinIME().changeSubtype(newSubtype);
            }
        }
        mSwipeLanguageProgress = 0.0f;
    }

    @Override
    public void onSwipeLanguageProgress(float progress) {
        if(!canDoLanguageSwitch()) return;
        MainKeyboardView view = mKeyboardSwitcher.getMainKeyboardView();
        if(mSettings.getCurrent().mVibrateOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (Math.abs(progress) >= 1.0f && Math.abs(mSwipeLanguageProgress) < 1.0f) {
                view.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE);
            } else if(Math.abs(progress) < 1.0f && Math.abs(mSwipeLanguageProgress) >= 1.0f) {
                view.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE);
            }
        }
        view.updateSwipeLanguageProgress(progress);
        mSwipeLanguageProgress = progress;
    }

    @Override
    public void onMovingCursorLockEvent(boolean canMoveCursor) {
        if(canMoveCursor) {
            hapticAndAudioFeedback(Constants.CODE_UNSPECIFIED, 0);
        }
    }

    private int getCodePointForKeyboard(final int codePoint) {
        if (Constants.CODE_SHIFT == codePoint) {
            final Keyboard currentKeyboard = mKeyboardSwitcher.getKeyboard();
            if (null != currentKeyboard && currentKeyboard.mId.isAlphabetKeyboard()) {
                return codePoint;
            }
            return Constants.CODE_SYMBOL_SHIFT;
        }
        return codePoint;
    }

    // Implementation of {@link KeyboardActionListener}.
    @Override
    public void onCodeInput(final int codePoint, final int x, final int y,
            final boolean isKeyRepeat) {
        
        // --- YENİ: CTRL / ALT KOMBİNASYON KONTROLÜ ---
        if (mKeyboardSwitcher.getState() != null && 
            (mKeyboardSwitcher.getState().isCtrlActive() || mKeyboardSwitcher.getState().isAltActive())) {
            int metaState = 0;
            if (mKeyboardSwitcher.getState().isCtrlActive()) {
                metaState |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
            }
            if (mKeyboardSwitcher.getState().isAltActive()) {
                metaState |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
            }
            
            int keyCode = charToKeyCode(codePoint);
            if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                InputConnection ic = mInputMethodService.getCurrentInputConnection();
                if (ic != null) {
                    long now = System.currentTimeMillis();
                    KeyEvent downEvent = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState);
                    KeyEvent upEvent = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState);
                    ic.sendKeyEvent(downEvent);
                    ic.sendKeyEvent(upEvent);
                    return; // Normal harf girişini engelle
                }
            }
        }
        // --- YENİ BİTTİ ---

        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        final int keyX = mainKeyboardView.getKeyX(x);
        final int keyY = mainKeyboardView.getKeyY(y);
        final Event event = createSoftwareKeypressEvent(getCodePointForKeyboard(codePoint),
                keyX, keyY, isKeyRepeat);
        onEvent(event);
    }

    // YENİ: Karakteri KeyEvent koduna çeviren yardımcı metot
    private int charToKeyCode(int codePoint) {
        if (codePoint >= 'a' && codePoint <= 'z') {
            return KeyEvent.KEYCODE_A + (codePoint - 'a');
        } else if (codePoint >= 'A' && codePoint <= 'Z') {
            return KeyEvent.KEYCODE_A + (codePoint - 'A');
        } else if (codePoint >= '0' && codePoint <= '9') {
            return KeyEvent.KEYCODE_0 + (codePoint - '0');
        } else if (codePoint == ' ') {
            return KeyEvent.KEYCODE_SPACE;
        } else if (codePoint == '.') {
            return KeyEvent.KEYCODE_PERIOD;
        } else if (codePoint == ',') {
            return KeyEvent.KEYCODE_COMMA;
        }
        return KeyEvent.KEYCODE_UNKNOWN;
    }

    public void onEvent(@Nonnull final Event event) {
        if (Constants.CODE_SHORTCUT == event.mKeyCode) {
            getLatinIME().trySwitchToShortcutIMEorInternal();
        }
        getActiveIME().onEvent(event);
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState());
    }

    @Nonnull
    public static Event createSoftwareKeypressEvent(final int keyCodeOrCodePoint, final int keyX,
             final int keyY, final boolean isKeyRepeat) {
        final int keyCode;
        final int codePoint;
        if (keyCodeOrCodePoint <= 0) {
            keyCode = keyCodeOrCodePoint;
            codePoint = Event.NOT_A_CODE_POINT;
        } else {
            keyCode = Event.NOT_A_KEY_CODE;
            codePoint = keyCodeOrCodePoint;
        }
        return Event.createSoftwareKeypressEvent(codePoint, keyCode, keyX, keyY, isKeyRepeat);
    }

    @Override
    public void onTextInput(final String rawText) {
        final Event event = Event.createSoftwareTextEvent(rawText, Constants.CODE_OUTPUT_TEXT);
        getActiveIME().onEvent(event);
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState());
    }

    public void onTextInputWithSpace(final String rawText) {
        final Event event = Event.createSoftwareTextEvent(rawText, Constants.CODE_OUTPUT_TEXT_WITH_SPACES);
        getActiveIME().onEvent(event);
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState());
    }

    private IMEInterface getActiveIME() {
        return mImeManager.getActiveIME(mSettings.getCurrent());
    }

    @Override
    public void onStartBatchInput() {
        getActiveIME().onStartBatchInput();
    }

    @Override
    public void onUpdateBatchInput(final InputPointers batchPointers) {
        getActiveIME().onUpdateBatchInput(batchPointers);
    }

    @Override
    public void onEndBatchInput(final InputPointers batchPointers) {
        getActiveIME().onEndBatchInput(batchPointers);
    }

    @Override
    public void onCancelBatchInput() {
        getActiveIME().onCancelBatchInput();
    }

    @Override
    public void onFinishSlidingInput() {
        mKeyboardSwitcher.onFinishSlidingInput(getCurrentAutoCapsState());
    }

    @Override
    public void onCancelInput() {
    }

    @Override
    public void showSuggestionStrip(SuggestedWords suggestedWords) {
    }

    @Override
    public void pickSuggestionManually(final SuggestedWordInfo suggestionInfo) {
        final Event event = Event.createSuggestionPickedEvent(suggestionInfo);
        mImeManager.getActiveIME(
                mSettings.getCurrent()
        ).onEvent(event);

        if(suggestionInfo.isKindOf(SuggestedWordInfo.KIND_EMOJI_SUGGESTION)) {
            getLatinIME().rememberEmojiSuggestion(suggestionInfo);
        }
    }

    @Override
    public void requestForgetWord(SuggestedWordInfo word) {
        getLatinIME().requestForgetWord(word);
    }

    @Override
    public void setNeutralSuggestionStrip() {
    }

    @UsedForTesting
    void loadKeyboard() {
        loadSettings();
        if (mKeyboardSwitcher.getMainKeyboardView() != null) {
            mKeyboardSwitcher.loadKeyboard(mInputMethodService.getCurrentInputEditorInfo(), mSettings.getCurrent(),
                    getCurrentAutoCapsState());
        }
    }

    private void hapticAndAudioFeedback(final int code, final int repeatCount) {
        final MainKeyboardView keyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (keyboardView != null && keyboardView.isInDraggingFinger()) {
            return;
        }
        final AudioAndHapticFeedbackManager feedbackManager =
                AudioAndHapticFeedbackManager.getInstance();
        feedbackManager.performHapticFeedback(keyboardView, repeatCount > 0);
        feedbackManager.performAudioFeedback(code);
    }

    @Override
    public void onPressKey(final int primaryCode, final int repeatCount,
            final boolean isSinglePointer) {
        mKeyboardSwitcher.onPressKey(primaryCode, isSinglePointer, getCurrentAutoCapsState());

        if(primaryCode == Constants.CODE_DELETE && repeatCount > 1) {
            if(!getActiveIME().hasMoreTextToDelete()) return;
        }
        hapticAndAudioFeedback(primaryCode, repeatCount);
    }

    @Override
    public void onReleaseKey(final int primaryCode, final boolean withSliding) {
        mKeyboardSwitcher.onReleaseKey(primaryCode, withSliding, getCurrentAutoCapsState());
    }

    private HardwareEventDecoder getHardwareKeyEventDecoder(final int deviceId) {
        final HardwareEventDecoder decoder = mHardwareEventDecoders.get(deviceId);
        if (null != decoder) return decoder;
        final HardwareEventDecoder newDecoder = new HardwareKeyboardEventDecoder(deviceId);
        mHardwareEventDecoders.put(deviceId, newDecoder);
        return newDecoder;
    }

    public boolean onKeyDown(final int keyCode, final KeyEvent keyEvent) {
        if (mEmojiAltPhysicalKeyDetector == null) {
            mEmojiAltPhysicalKeyDetector = new EmojiAltPhysicalKeyDetector(
                    mInputMethodService.getApplicationContext().getResources());
        }
        mEmojiAltPhysicalKeyDetector.onKeyDown(keyEvent);
        if (!ProductionFlags.IS_HARDWARE_KEYBOARD_SUPPORTED) {
            return false;
        }
        final Event event = getHardwareKeyEventDecoder(
                keyEvent.getDeviceId()).decodeHardwareKey(keyEvent);
        if (event.isHandled()) {
            mImeManager.getActiveIME(
                    mSettings.getCurrent()
            ).onEvent(event);
            return true;
        }
        return false;
    }

    public boolean onKeyUp(final int keyCode, final KeyEvent keyEvent) {
        if (mEmojiAltPhysicalKeyDetector == null) {
            mEmojiAltPhysicalKeyDetector = new EmojiAltPhysicalKeyDetector(
                    mInputMethodService.getApplicationContext().getResources());
        }
        mEmojiAltPhysicalKeyDetector.onKeyUp(keyEvent);
        if (!ProductionFlags.IS_HARDWARE_KEYBOARD_SUPPORTED) {
            return false;
        }
        return false;
    }

    private final BroadcastReceiver mRingerModeChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();
            if (action.equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                AudioAndHapticFeedbackManager.getInstance().onRingerModeChanged();
            }
        }
    };

    private void startActivityOnTheSameDisplay(Intent intent) {
        final int currentDisplayId = ((WindowManager) mInputMethodService.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay().getDisplayId();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mInputMethodService.startActivity(intent,
                    ActivityOptions.makeBasic().setLaunchDisplayId(currentDisplayId).toBundle());
        }
    }

    @UsedForTesting
    SuggestedWords getSuggestedWordsForTest() {
        return null;
    }

    @UsedForTesting
    void waitForLoadingDictionaries(final long timeout, final TimeUnit unit)
            throws InterruptedException {
    }

    @UsedForTesting
    void replaceDictionariesForTest(final Locale locale) {
    }

    @UsedForTesting
    void clearPersonalizedDictionariesForTest() {
    }

    public void dumpDictionaryForDebug(final String dictName) {
    }

    public void debugDumpStateAndCrashWithException(final String context) {
        final SettingsValues settingsValues = mSettings.getCurrent();
        final StringBuilder s = new StringBuilder(settingsValues.toString());
        s.append("\nAttributes : ").append(settingsValues.mInputAttributes)
                .append("\nContext : ").append(context);
        throw new RuntimeException(s.toString());
    }

    protected void dump(final FileDescriptor fd, final PrintWriter fout, final String[] args) {
        final Printer p = new PrintWriterPrinter(fout);
        p.println("LatinIME state :");
        p.println("  VersionCode = " + ApplicationUtils.getVersionCode(mInputMethodService));
        p.println("  VersionName = " + ApplicationUtils.getVersionName(mInputMethodService));
        final Keyboard keyboard = mKeyboardSwitcher.getKeyboard();
        final int keyboardMode = keyboard != null ? keyboard.mId.mMode : -1;
        p.println("  Keyboard mode = " + keyboardMode);
        final SettingsValues settingsValues = mSettings.getCurrent();
        p.println(settingsValues.dump());
    }

    public boolean shouldSwitchToOtherInputMethods() {
        final boolean fallbackValue = mSettings.getCurrent().mIncludesOtherImesInLanguageSwitchList;
        final IBinder token = mInputMethodService.getWindow().getWindow().getAttributes().token;
        if (token == null) {
            return fallbackValue;
        }
        return mRichImm.shouldOfferSwitchingToNextInputMethod(token, fallbackValue);
    }

    private void setNavigationBarVisibility(final boolean visible) {
        getLatinIME().updateNavigationBarVisibility(visible);
    }

    public InputMethodService getInputMethodService() {
        return mInputMethodService;
    }

    public LatinIME getLatinIME() {
        return (LatinIME)(mInputMethodService);
    }

    public Locale getLocale() {
        return mLocale;
    }

    public void setLayout(@NotNull KeyboardLayoutSetV2 layout) {
        mImeManager.setLayout(layout);
    }
}
