/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;

import app.morphe.extension.shared.spoof.SabrResponseReader;

public class DownloadResponseTest {
    private static final byte MEDIA = 21;
    private static final byte NEXT_REQUEST_POLICY = 35;
    private static final byte SABR_ERROR = 44;

    @Test
    public void detectsErrorAfterPolicyInSameRead() {
        SabrResponseReader reader = new SabrResponseReader();
        reader.read(ByteBuffer.wrap(new byte[] { NEXT_REQUEST_POLICY, 0, SABR_ERROR, 0 }));
        assertTrue(reader.sawSabrError());
    }

    @Test
    public void distinguishesMediaBytesFromTheNextPartTypeAcrossReads() {
        SabrResponseReader reader = new SabrResponseReader();
        reader.read(ByteBuffer.wrap(new byte[] { NEXT_REQUEST_POLICY, 0, MEDIA, 2, SABR_ERROR }));
        reader.read(ByteBuffer.wrap(new byte[] { SABR_ERROR }));
        assertFalse(reader.sawSabrError());

        reader.read(ByteBuffer.wrap(new byte[] { SABR_ERROR }));
        assertTrue(reader.sawSabrError());
    }
}
