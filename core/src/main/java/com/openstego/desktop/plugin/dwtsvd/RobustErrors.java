/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.dwtsvd;

import com.openstego.desktop.OpenStegoException;

/** Error codes for the robust (DWT-SVD-QIM) data-hiding plugin. */
public class RobustErrors {

    public static final int ERR_NO_COVER_FILE = 1;
    public static final int ERR_FILE_TOO_SMALL = 2;
    public static final int ERR_MESSAGE_TOO_LONG = 3;
    public static final int ERR_DECODE_FAILED = 4;

    public static void init() {
        OpenStegoException.addErrorCodes(
                RobustPlugin.NAMESPACE,
                new int[] {ERR_NO_COVER_FILE, ERR_FILE_TOO_SMALL, ERR_MESSAGE_TOO_LONG, ERR_DECODE_FAILED},
                new String[] {"err.cover.missing", "err.file.too.small", "err.message.too.long", "err.decode.failed"});
    }
}
