package com.spr.vad;

import org.apache.commons.lang3.ArrayUtils;

public class AudioStreamBoundaryDetectorSilero {

    public static final int BUFFER_DURATION_MILLIS = 20;

    private static final int SAMPLE_RATE = 16000;
    private static final int SILERO_WINDOW_SIZE = 512;
    private static final int BUFFER_SAMPLES = SAMPLE_RATE * BUFFER_DURATION_MILLIS / 1000;
    private static final String SENTENCE_COMPLETED = "SENTENCE_COMPLETED";
    private static final String SENTENCE_DURATION_LIMIT_REACHED = "SENTENCE_DURATION_LIMIT_REACHED";
    private static final String STREAM_COMPLETED = "STREAM_COMPLETED";

    private final int speechThreshold;
    private final int maxDurationMillis;

    private SileroVAD vad;
    private byte[] bufferArray;
    private byte[] accumulatorBytes;
    private int nonSpeechThreshold;
    private int consecutiveNonSpeechCount = 0;
    private int consecutiveSpeechCount = 0;
    private boolean voiceActivityStarted = false;
    private int timeCounter = 0;
    private int currentVoiceActivityStartTime = 0;

    public AudioStreamBoundaryDetectorSilero(String onnxModelPath, int speechThreshold,
                                              int nonSpeechThreshold, int maxDurationMillis) {
        this.speechThreshold = speechThreshold;
        this.nonSpeechThreshold = nonSpeechThreshold;
        this.maxDurationMillis = maxDurationMillis;
        this.bufferArray = new byte[0];
        this.accumulatorBytes = new byte[0];
        this.vad = new SileroVAD(onnxModelPath);
    }

    public VadResponse process(byte[] buffer) {
        validateLength(buffer);

        accumulatorBytes = ArrayUtils.addAll(accumulatorBytes, buffer);
        int windowBytes = SILERO_WINDOW_SIZE * 2;

        boolean isSpeech = false;
        if (accumulatorBytes.length >= windowBytes) {
            byte[] frame = new byte[windowBytes];
            System.arraycopy(accumulatorBytes, 0, frame, 0, windowBytes);
            isSpeech = vad.isSpeech(frame);

            int remaining = accumulatorBytes.length - windowBytes;
            if (remaining > 0) {
                byte[] leftover = new byte[remaining];
                System.arraycopy(accumulatorBytes, windowBytes, leftover, 0, remaining);
                accumulatorBytes = leftover;
            } else {
                accumulatorBytes = new byte[0];
            }
        }

        bufferArray = ArrayUtils.addAll(bufferArray, buffer);
        if (isSpeech) {
            consecutiveNonSpeechCount = 0;
            consecutiveSpeechCount++;
        } else {
            consecutiveSpeechCount = 0;
            consecutiveNonSpeechCount++;
            if (!voiceActivityStarted) {
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
        accumulatorBytes = new byte[0];
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
        if (voiceActivityStarted) {
            response = handleVoiceActivityEnd(currentVoiceActivityStartTime, timeCounter, STREAM_COMPLETED);
        } else {
            bufferArray = new byte[0];
        }
        return response;
    }

    public Integer getTotalAudioAccumulatedInMillis() {
        if (voiceActivityStarted) return timeCounter - currentVoiceActivityStartTime;
        return null;
    }

    public byte[] getAccumulatedAudio() {
        return bufferArray;
    }

    private void validateLength(byte[] buffer) {
        int length = buffer.length;
        if (!(length == 160 || length == 320 || length == 480 || length == 640 || length == 1024)) {
            throw new IllegalArgumentException("Expecting buffer size of 160|320|480|640|1024. Got: " + length);
        }
    }

    private VadResponse handleVoiceActivityEnd(int startTime, int endTime, String reason) {
        voiceActivityStarted = false;
        consecutiveNonSpeechCount = 0;
        consecutiveSpeechCount = 0;
        currentVoiceActivityStartTime = endTime;
        VadResponse vadResponse = new VadResponse(startTime, endTime, bufferArray);
        bufferArray = new byte[0];
        return vadResponse;
    }
}
