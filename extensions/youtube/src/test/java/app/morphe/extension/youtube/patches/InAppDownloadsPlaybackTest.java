/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.CodedOutputStream;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import app.morphe.extension.youtube.innertube.Offline.OfflinePlayerConfig;
import app.morphe.extension.youtube.innertube.Offline.OfflinePlayerResponse;
import app.morphe.extension.youtube.innertube.Offline.OfflineState;
import app.morphe.extension.youtube.innertube.Offline.OfflineStateAction;
import app.morphe.extension.youtube.innertube.Offline.OfflineStreamingData;

public class InAppDownloadsPlaybackTest {
    @Test
    public void enablingTrackingPreservesStreamsTokensAndNestedNativeConfig() throws Exception {
        OfflinePlayerResponse original = playableResponse(false);

        OfflinePlayerResponse updated = InAppDownloadsPatch.Playback.enablePositionTracking(original);

        OfflinePlayerResponse expected = original.toBuilder()
                .setPlayerConfig(OfflinePlayerConfig.parseFrom(playerConfig(true)))
                .build();
        assertEquals(expected, updated);
        assertArrayEquals(original.getStreamingData().toByteArray(), updated.getStreamingData().toByteArray());
        assertArrayEquals(original.getOfflineState().toByteArray(), updated.getOfflineState().toByteArray());
        assertFalse(original.getPlayerConfig().getPlaybackPositionConfig().getEnablePlaybackPositionTracking());
        assertTrue(updated.getPlayerConfig().getPlaybackPositionConfig().getEnablePlaybackPositionTracking());
        assertSame(updated, InAppDownloadsPatch.Playback.enablePositionTracking(updated));
    }

    @Test
    public void existingPlayableResponsesCanGainTrackingWithoutPlayerConfig() {
        OfflinePlayerResponse original = OfflinePlayerResponse.newBuilder()
                .setOfflineState(OfflineState.newBuilder()
                        .setAction(OfflineStateAction.OFFLINE_STATE_ACTION_OK)
                        .setToken("saved offline token"))
                .build();

        OfflinePlayerResponse updated = InAppDownloadsPatch.Playback.enablePositionTracking(original);

        assertTrue(updated.getPlayerConfig().getPlaybackPositionConfig().getEnablePlaybackPositionTracking());
        assertEquals(original.getOfflineState(), updated.getOfflineState());
        assertFalse(original.hasPlayerConfig());
    }

    @Test
    public void streamingDisabledAndAlreadyEnabledResponsesAreUnchanged() throws Exception {
        OfflinePlayerResponse enabled = playableResponse(true);
        assertSame(enabled, InAppDownloadsPatch.Playback.enablePositionTracking(enabled));

        OfflinePlayerResponse streaming = playableResponse(false).toBuilder().clearOfflineState().build();
        assertSame(streaming, InAppDownloadsPatch.Playback.enablePositionTracking(streaming));

        for (int action : new int[]{0, 2, 3}) {
            OfflinePlayerResponse response = playableResponse(false).toBuilder()
                    .setOfflineState(OfflineState.newBuilder()
                            .setActionValue(action)
                            .setToken("saved offline token")
                            .setDisabledReason("native restriction"))
                    .build();
            assertSame(response, InAppDownloadsPatch.Playback.enablePositionTracking(response));
        }
    }

    private static OfflinePlayerResponse playableResponse(boolean tracking) throws IOException {
        byte[] format = wire(output -> {
            output.writeString(5, "video/mp4");
            output.writeBool(58, true);
            output.writeUInt32(18, 1337);
        });
        OfflineStreamingData streams = OfflineStreamingData.parseFrom(wire(output -> {
            output.writeByteArray(3, format);
            output.writeUInt64(1, 108000);
            output.writeString(40, "native stream metadata");
        }));
        return OfflinePlayerResponse.newBuilder()
                .mergeFrom(wire(output -> output.writeString(101, "native response metadata")))
                .setStreamingData(streams)
                .setOfflineState(OfflineState.newBuilder()
                        .setAction(OfflineStateAction.OFFLINE_STATE_ACTION_OK)
                        .setToken("saved offline token")
                        .setPolicyLifetimeSeconds(86400))
                .setPlayerConfig(OfflinePlayerConfig.parseFrom(playerConfig(tracking)))
                .build();
    }

    private static byte[] playerConfig(boolean tracking) throws IOException {
        byte[] position = wire(output -> {
            output.writeBool(1, tracking);
            output.writeUInt32(9, 42);
        });
        byte[] exoPlayer = wire(output -> {
            output.writeBool(1, true);
            output.writeBool(53, true);
            output.writeByteArray(90, new byte[]{4, 5, 6});
        });
        return wire(output -> {
            output.writeByteArray(3, position);
            output.writeByteArray(57825011, exoPlayer);
            output.writeByteArray(60102073, new byte[]{8, 1});
            output.writeString(91, "native config metadata");
        });
    }

    private static byte[] wire(WireWriter writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        writer.write(output);
        output.flush();
        return bytes.toByteArray();
    }

    private interface WireWriter {
        void write(CodedOutputStream output) throws IOException;
    }
}
