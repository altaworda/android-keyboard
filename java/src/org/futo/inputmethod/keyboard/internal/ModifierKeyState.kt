package org.futo.inputmethod.keyboard.internal

import android.util.Log
import android.view.ViewConfiguration

open class ModifierKeyState(protected val mName: String) {
    protected val TAG = ModifierKeyState::class.java.simpleName
    protected val DEBUG = false
    protected val RELEASING = 0; protected val PRESSING = 1; protected val CHORDING = 2
    protected var mState = RELEASING
    var isLocked = false; protected set

    open fun onPress() { val oldState = mState; mState = PRESSING; if (DEBUG) Log.d(TAG, "$mName.onPress: ${toString(oldState)} > $this") }
    open fun onRelease() { val oldState = mState; if (!isLocked) { mState = RELEASING }; if (DEBUG) Log.d(TAG, "$mName.onRelease: ${toString(oldState)} > $this") }
    open fun onOtherKeyPressed() { val oldState = mState; if (oldState == PRESSING) mState = CHORDING; if (DEBUG) Log.d(TAG, "$mName.onOtherKeyPressed: ${toString(oldState)} > $this") }

    fun isPressing(): Boolean = mState == PRESSING
    fun isReleasing(): Boolean = mState == RELEASING
    fun isChording(): Boolean = mState == CHORDING
    open fun onTapForLock() {}

    override fun toString(): String = toString(mState)
    open fun toString(state: Int): String = when (state) { RELEASING -> "RELEASING"; PRESSING -> "PRESSING"; CHORDING -> "CHORDING"; else -> "UNKNOWN" }
}

class CtrlKeyState : ModifierKeyState("Ctrl") {
    private var lastTapTime: Long = 0
    override fun onTapForLock() {
        val now = System.currentTimeMillis()
        if (now - lastTapTime < ViewConfiguration.getDoubleTapTimeout()) { isLocked = !isLocked; if (!isLocked) mState = RELEASING }
        lastTapTime = now
    }
}

class AltKeyState : ModifierKeyState("Alt") {
    private var lastTapTime: Long = 0
    override fun onTapForLock() {
        val now = System.currentTimeMillis()
        if (now - lastTapTime < ViewConfiguration.getDoubleTapTimeout()) { isLocked = !isLocked; if (!isLocked) mState = RELEASING }
        lastTapTime = now
    }
}

class ShiftKeyState(name: String) : ModifierKeyState(name) {
    private val PRESSING_ON_SHIFTED = 3; private val IGNORING = 4
    override fun onOtherKeyPressed() {
        val oldState = mState
        if (oldState == PRESSING) mState = CHORDING else if (oldState == PRESSING_ON_SHIFTED) mState = IGNORING
    }
    fun onPressOnShifted() { mState = PRESSING_ON_SHIFTED }
    fun isPressingOnShifted(): Boolean = mState == PRESSING_ON_SHIFTED
    fun isIgnoring(): Boolean = mState == IGNORING
    override fun toString(state: Int): String = when (state) { PRESSING_ON_SHIFTED -> "PRESSING_ON_SHIFTED"; IGNORING -> "IGNORING"; else -> super.toString(state) }
}
