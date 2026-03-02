package com.spr.vad;

import com.spr.core.logger.Logger;
import com.spr.core.logger.LoggerFactory;
import com.spr.vad.utils.ShortArrayConverter;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.io.Closeable;
import java.util.concurrent.atomic.AtomicBoolean;
import com.spr.util.PIILogMaskingUtils;

public class VAD implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(VAD.class);

    public static final float THRESHOLD = 0.6F;
    public static final int PACKET_MIN_SIZE = 320; //20 ms it represents

    private static final int CODE_SUCCESS = 0;
    private static final int PACKET_SIZE_MS = 120;
    private static final int PACKET_SIZE = 3840;
    private static final int BOS_DELAY_MS = 400;
    private static final int EOS_DELAY_MS = 1000;

    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private final Pointer state;

    public VAD() {
        this(PACKET_SIZE_MS, BOS_DELAY_MS, EOS_DELAY_MS);
    }

    public VAD(int maxPacketSizeMs, int bosDelayMs, int eosDelayMs) {
        state = Detector.INSTANCE.create_kika_vad_detector();
        int result = Detector.INSTANCE.init_kika_vad_detector(state, maxPacketSizeMs, bosDelayMs, eosDelayMs);
        if (CODE_SUCCESS != result) {
            throw new IllegalStateException("Failed to init VAD");
        }
    }

    public float speechProbability(byte[] pcm) {
        if (pcm == null || pcm.length < PACKET_MIN_SIZE || pcm.length > PACKET_SIZE) {
            return 0F;
        }
        short[] frame = ShortArrayConverter.toShortArray(pcm);
        try {
            return Detector.INSTANCE.process_kika_vad_prob(state, frame, frame.length);
        } catch (Exception e) {
            LOGGER.error("Error in VAD", PIILogMaskingUtils.maskExceptionPII(LOGGER, e));
            return 0.0F;
        }
    }

    public boolean isSpeech(byte[] pcm) {
        return speechProbability(pcm) >= THRESHOLD;
    }

    public boolean isSilent(byte[] pcm) {
        return speechProbability(pcm) < THRESHOLD;
    }

    @Override
    public void close() {
        if (stopped.getAndSet(true)) {
            return;
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("closing VAD");
        }
        Detector.INSTANCE.destroy_kika_vad_detector(state);
    }

    public interface Detector extends Library {

        Detector INSTANCE = Native.loadLibrary("kvad", Detector.class);

        Pointer create_kika_vad_detector();

        /**
         * @param vadDetector the pointer
         * @param packetSizeMs packet size in ms, 120 ms recommended
         * @param startDelayMs how long before the wave been treated as start of voice
         * @param stopDelayMs low long before the wave been treated as end of voice
         */
        int init_kika_vad_detector(Pointer vadDetector, int packetSizeMs, int startDelayMs, int stopDelayMs);

        int reset_kika_vad_detector(Pointer vadDetector);

        /**
         * 1: voice
         * 0: no voice
         * &lt;0: crash
         */
        int process_kika_vad(Pointer state, short[] frame, int length);

        float process_kika_vad_prob(Pointer state, short[] frame, int length);

        void destroy_kika_vad_detector(Pointer vadDetector);
    }
}
 