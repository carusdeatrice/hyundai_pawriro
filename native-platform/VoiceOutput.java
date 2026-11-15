package kr.routerescue.demo;

import android.content.Context;
import android.media.*;
import android.speech.tts.*;
import java.util.Locale;

final class VoiceOutput implements AutoCloseable {
    private final TextToSpeech speech;
    private final AudioManager audio;
    private final AudioFocusRequest focus;
    private boolean ready;
    VoiceOutput(Context context) {
        audio = context.getSystemService(AudioManager.class);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener(change -> {
                if (change < 0) stop();
            }).build();
        speech = new TextToSpeech(context, this::initialize);
        speech.setAudioAttributes(attributes);
        speech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) { audio.abandonAudioFocusRequest(focus); }
            @Override public void onError(String id) { audio.abandonAudioFocusRequest(focus); }
        });
    }
    private void initialize(int status) {
        ready = status == TextToSpeech.SUCCESS && speech.setLanguage(Locale.KOREAN) >= 0;
    }
    void speak(String prompt) {
        if (!ready || audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return;
        if (speech.speak(prompt,TextToSpeech.QUEUE_FLUSH,null,"route-offer") == TextToSpeech.ERROR) stop();
    }
    void stop() { speech.stop(); audio.abandonAudioFocusRequest(focus); }
    @Override public void close() { stop(); speech.shutdown(); }
}
