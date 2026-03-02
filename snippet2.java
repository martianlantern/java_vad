package com.spr.vad;

import com.google.common.annotations.VisibleForTesting;
import com.spr.atlas.counter.SprAtlasCounters;
import com.spr.core.logger.Logger;
import com.spr.core.logger.LoggerFactory;
import com.spr.utils.core.UserContextProvider;
import com.spr.utils.sla.SLAEngine;
import com.spr.utils.sla.SLAUtils;
import com.spr.vad.wrapper.VadInterface;
import com.spr.vad.wrapper.VadWrapper;
import com.spr.vad.wrapper.adapter.Vad4jAdapter;
import com.spr.vad.wrapper.adapter.WebRTCVadAdapter;
import org.apache.commons.lang3.ArrayUtils;
import com.spr.util.PIILogMaskingUtils;

public class AudioStreamBoundaryDetector {

    private static final Logger LOGGER = LoggerFactory.getLogger(AudioStreamBoundaryDetector.class);

    // Duration of audio buffer taken as input
    public static final int BUFFER_DURATION_MILLIS = 20;

    // Sentence was completed before sentence reached max duration limit
    private static final String SENTENCE_COMPLETED = "SENTENCE_COMPLETED";
    // Sentence reached max sentence duration limit so end it forcefully
    private static final String SENTENCE_DURATION_LIMIT_REACHED = "SENTENCE_DURATION_LIMIT_REACHED";
    // Audio stream was completed so flushed remaining audio data (if any)
    private static final String STREAM_COMPLETED = "STREAM_COMPLETED";
    private static final SLAEngine AUDIO_DURATION_SLA = SLAUtils.createSLAEngineWithPrefix("vad.audio.duration");

    private final int speechThreshold;
    private final int maxDurationMillis;

    private VAD vad;
    private byte[] bufferArray;
    private int nonSpeechThreshold;
    private int consecutiveNonSpeechCount = 0;
    private int consecutiveSpeechCount = 0;
    private boolean voiceActivityStarted = false;
    private int timeCounter = 0;
    private int currentVoiceActivityStartTime = 0;

    public AudioStreamBoundaryDetector(boolean webRTCVadEnabled, int speechThreshold, int nonSpeechThreshold, int maxDurationMillis) {
        this.speechThreshold = speechThreshold;
        this.nonSpeechThreshold = nonSpeechThreshold;
        this.maxDurationMillis = maxDurationMillis;
        this.bufferArray = new byte[0];
        this.vad = new VAD(BUFFER_DURATION_MILLIS, 0, 0));
    }

    /* Accumulate speech data and detects sentence boundary.
     * If boundary detected then return timestamps and audio data corresponding to sentence.
     * It starts accumulating data after voice activity started and break sentence once voice activity ended.
     * Voice activity started if continuous speech >= ContinuousSpeechThreshold
     * Voice activity ended if continuous non speech >= ContinuousNonSpeechThreshold or sentence duration >= MAX_DURATION_MILLIS
     */
    public VadResponse process(byte[] buffer) {
        validateLength(buffer);
        boolean isSpeech = vad.isSpeech(buffer);

        bufferArray = ArrayUtils.addAll(bufferArray, buffer);
        if (isSpeech) {
            consecutiveNonSpeechCount = 0;
            consecutiveSpeechCount++;
        } else {
            consecutiveSpeechCount = 0;
            consecutiveNonSpeechCount++;
            if (!voiceActivityStarted) { // clear buffer, as of no use of this buffer until voiceActivity started
                bufferArray = new byte[0];
            }
        }
        timeCounter = timeCounter + BUFFER_DURATION_MILLIS;
        if (voiceActivityStarted) {
            boolean sentenceCompleted = consecutiveNonSpeechCount >= nonSpeechThreshold;
            boolean sentenceLimitReached = (timeCounter - currentVoiceActivityStartTime) > maxDurationMillis;
            if (sentenceCompleted || sentenceLimitReached) {
                String reason = sentenceCompleted ? SENTENCE_COMPLETED : SENTENCE_DURATION_LIMIT_REACHED;
                return handleVoiceActivityEnd(currentVoiceActivityStartTime, timeCounter, reason);
            }
        } else if (consecutiveSpeechCount == speechThreshold) {
            voiceActivityStarted = true;
            currentVoiceActivityStartTime = timeCounter - speechThreshold * BUFFER_DURATION_MILLIS;
        }
        return null;
    }

    public void setNonSpeechThreshold(int nonSpeechThreshold) {
        this.nonSpeechThreshold = nonSpeechThreshold;
    }

    public int getNonSpeechThreshold() {
        return nonSpeechThreshold;
    }

    public boolean isVoiceActivityStarted() {
        return voiceActivityStarted;
    }

    public VadResponse reset() {
        VadResponse response = null;
        if (voiceActivityStarted) {
            response = handleVoiceActivityEnd(currentVoiceActivityStartTime, timeCounter, STREAM_COMPLETED);
        }
        bufferArray = new byte[0];
        consecutiveNonSpeechCount = 0;
        consecutiveSpeechCount = 0;
        voiceActivityStarted = false;
        timeCounter = 0;
        currentVoiceActivityStartTime = 0;
        return response;
    }

    public VadResponse flush() {
        vad.close();
        vad = null;
        VadResponse response = null;
        // if voiceActivityStarted then this means there's speech data present in buffers
        if (voiceActivityStarted) {
            response = handleVoiceActivityEnd(currentVoiceActivityStartTime, timeCounter, STREAM_COMPLETED);
        } else {
            bufferArray = new byte[0];
        }
        return response;
    }

    @VisibleForTesting
    public int getConsecutiveNonSpeechCount() {
        return consecutiveNonSpeechCount;
    }

    @VisibleForTesting
    public int getConsecutiveSpeechCount() {
        return consecutiveSpeechCount;
    }

    public Integer getTotalAudioAccumulatedInMillis() {
        if (voiceActivityStarted) {
            return timeCounter - currentVoiceActivityStartTime;
        }
        return null;
    }

    public byte[] getAccumulatedAudio() {
        return bufferArray;
    }

    private void validateLength(byte[] buffer) {
        int length = buffer.length;
        if (!(length == 160 || length == 320 || length == 480)) {
            throw new IllegalArgumentException("Expecting buffer size of 160|320|480. Got: " + length);
        }
    }

    private VadResponse handleVoiceActivityEnd(int startTime, int endTime, String reason) {
        voiceActivityStarted = false;
        consecutiveNonSpeechCount = 0;
        consecutiveSpeechCount = 0;
        currentVoiceActivityStartTime = endTime;
        int duration = endTime - startTime;
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                "VadResponse: start: " + startTime + " end: " + endTime + " duration: " + duration + " reason: " + PIILogMaskingUtils.maskPII(LOGGER,
                                                                                                                                              reason));
        }
        captureSLA(duration);
        VadResponse vadResponse = new VadResponse(startTime, endTime, bufferArray);
        bufferArray = new byte[0];
        return vadResponse;
    }

    private void captureSLA(int duration) {
        String bucket = AUDIO_DURATION_SLA.findSLABucket(duration);
        SprAtlasCounters.getInstance().incrementSampledCounterWithPartnerId(bucket, UserContextProvider.getCurrentPartnerIdOrException());
    }
}