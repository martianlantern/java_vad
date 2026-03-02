package annotator;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.io.Closeable;
import java.util.concurrent.atomic.AtomicBoolean;

public class Vad4j implements Closeable {

    public static final float THRESHOLD = 0.6F;
    private static final int PACKET_MIN_SIZE = 320;
    private static final int PACKET_SIZE_MS = 120;
    private static final int PACKET_SIZE = 3840;
    private static final int BOS_DELAY_MS = 400;
    private static final int EOS_DELAY_MS = 1000;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final Pointer state;

    public Vad4j() {
        this(PACKET_SIZE_MS, BOS_DELAY_MS, EOS_DELAY_MS);
    }

    public Vad4j(int maxPacketSizeMs, int bosDelayMs, int eosDelayMs) {
        state = KVad.INSTANCE.create_kika_vad_detector();
        int result = KVad.INSTANCE.init_kika_vad_detector(state, maxPacketSizeMs, bosDelayMs, eosDelayMs);
        if (result != 0) throw new RuntimeException("Failed to init vad4j");
    }

    public float speechProbability(byte[] pcm) {
        if (pcm == null || pcm.length < PACKET_MIN_SIZE || pcm.length > PACKET_SIZE) return 0F;
        short[] frame = toShorts(pcm);
        try {
            return KVad.INSTANCE.process_kika_vad_prob(state, frame, frame.length);
        } catch (Exception e) {
            return 0F;
        }
    }

    public boolean isSpeech(byte[] pcm) { return speechProbability(pcm) >= THRESHOLD; }

    @Override
    public void close() {
        if (!stopped.getAndSet(true)) KVad.INSTANCE.destroy_kika_vad_detector(state);
    }

    private static short[] toShorts(byte[] b) {
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++)
            s[i] = (short) (((b[i * 2 + 1] & 0xff) << 8) | (b[i * 2] & 0xff));
        return s;
    }

    public interface KVad extends Library {
        KVad INSTANCE = Native.load("kvad", KVad.class);
        Pointer create_kika_vad_detector();
        int init_kika_vad_detector(Pointer p, int packetSizeMs, int startDelayMs, int stopDelayMs);
        int reset_kika_vad_detector(Pointer p);
        int process_kika_vad(Pointer p, short[] frame, int length);
        float process_kika_vad_prob(Pointer p, short[] frame, int length);
        void destroy_kika_vad_detector(Pointer p);
    }
}
