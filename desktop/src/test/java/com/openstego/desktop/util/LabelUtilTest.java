/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.openstego.desktop.OpenStego;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for a crash reported on Android split-image reveal with a wrong password: a
 * {@code NullPointerException} inside {@link LabelUtil#getString}. The root cause was that
 * {@link OpenStego#init()} used to be an intentionally empty method, relying on invoking it to trigger
 * {@code OpenStego}'s static initializer (which registers the "OpenStego" namespace) as a side effect.
 * That is a real JVM guarantee for a normal call, but a release build's optimizer (R8) is free to treat
 * a call to a genuinely empty method as dead code and drop it, silently skipping the registration on any
 * code path that only ever calls {@code OpenStego.init()} and never constructs an {@code OpenStego}
 * instance - exactly what {@code MultiCoverPayloadSplitter.extractSplit} does.
 */
public class LabelUtilTest {

    @Test
    public void getStringOnUnregisteredNamespaceReturnsKeyInsteadOfThrowing() {
        String key = "no.such.namespace.was.ever.registered";
        assertEquals(
                key,
                LabelUtil.getInstance("NoSuchNamespace-" + System.nanoTime()).getString(key));
    }

    @Test
    public void initRegistersTheOpenStegoNamespaceWithoutConstructingAnInstance() {
        OpenStego.init();
        String message = LabelUtil.getInstance(OpenStego.NAMESPACE).getString("err.noValidPlugin");
        assertNotNull(message);
        // A real, translated message - not the fallback path that just echoes the key back.
        assertEquals(false, message.equals("err.noValidPlugin"));
    }

    @Test
    public void initIsIdempotent() {
        OpenStego.init();
        OpenStego.init();
        assertNotNull(LabelUtil.getInstance(OpenStego.NAMESPACE).getString("err.noValidPlugin"));
    }

    /**
     * A second, related crash reported on the same flow: {@link com.openstego.desktop.OpenStegoException}
     * looks up its message key via {@code errMsgKeyMap.get(namespace + errorCode)}, which is {@code null}
     * for an error code that was never registered for that namespace (e.g. "LSB" errors thrown by
     * {@link com.openstego.desktop.plugin.lsb.LSBDataHeader} on behalf of a plugin, like Adaptive, that
     * never triggers {@code LSBPlugin}'s own registration). The old fallback in {@link #getString(String)}
     * returned that null key as-is, which then crashed {@code MessageFormat.format(null, params)} with an
     * NPE on {@code pattern.length()} instead of ever reporting the original error.
     */
    @Test
    public void getStringWithNullKeyDoesNotCrashMessageFormat() {
        assertNotNull(LabelUtil.getInstance(OpenStego.NAMESPACE).getString(null, "param"));
    }
}
