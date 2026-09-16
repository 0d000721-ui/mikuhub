/*
 * Copyright (C) 2019 The Android Open Source Project
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

package me.rerere.rikkahub.device.fixture;

/**
 * Test-only argument parser. The three parsing methods below are copied unmodified from
 * Android SDK 36.1's AOSP BasicShellCommandHandler, under the Apache 2.0 license above.
 * This tests the platform grammar independently of our APK command builder.
 * Reference: https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/88c7ff1cd72d6305ec59f97aadc2198cc2dc3592/android-34/com/android/modules/utils/BasicShellCommandHandler.java
 */
public final class AospShellArgumentParser {
    private final String[] mArgs;
    private int mArgPos;
    private String mCurArgData;

    public AospShellArgumentParser(String[] args, int firstArgPos) {
        mArgs = args;
        mArgPos = firstArgPos;
    }
    public String getNextOption() {
        if (mCurArgData != null) {
            String prev = mArgs[mArgPos - 1];
            throw new IllegalArgumentException("No argument expected after \"" + prev + "\"");
        }
        if (mArgPos >= mArgs.length) {
            return null;
        }
        String arg = mArgs[mArgPos];
        if (!arg.startsWith("-")) {
            return null;
        }
        mArgPos++;
        if (arg.equals("--")) {
            return null;
        }
        if (arg.length() > 1 && arg.charAt(1) != '-') {
            if (arg.length() > 2) {
                mCurArgData = arg.substring(2);
                return arg.substring(0, 2);
            } else {
                mCurArgData = null;
                return arg;
            }
        }
        mCurArgData = null;
        return arg;
    }

    public String getNextArg() {
        if (mCurArgData != null) {
            String arg = mCurArgData;
            mCurArgData = null;
            return arg;
        } else if (mArgPos < mArgs.length) {
            return mArgs[mArgPos++];
        } else {
            return null;
        }
    }

    public String getNextArgRequired() {
        String arg = getNextArg();
        if (arg == null) {
            String prev = mArgs[mArgPos - 1];
            throw new IllegalArgumentException("Argument expected after \"" + prev + "\"");
        }
        return arg;
    }
}
