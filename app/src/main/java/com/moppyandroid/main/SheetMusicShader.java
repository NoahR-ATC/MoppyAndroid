package com.moppyandroid.main;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.midisheetmusic.MidiFile;
import com.midisheetmusic.MidiOptions;
import com.midisheetmusic.SheetMusic;

import jp.kshoji.javax.sound.midi.MidiMessage;
import jp.kshoji.javax.sound.midi.Receiver;

/**
 * {@link jp.kshoji.javax.sound.midi.Receiver} that shades a {@link com.midisheetmusic.SheetMusic} as MIDI notes are sent.
 */
public class SheetMusicShader implements Receiver {
    private HandlerThread thread;
    private Handler handler;
    private SheetMusic sheetMusic;
    private long startTime;
    private double startPulseTime;
    private double currentPulseTime;
    private double pulsesPerMs;
    private boolean paused;

    public SheetMusicShader() {
        thread = new HandlerThread("SheetMusicShader");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public void start(MidiFile midiFile, MidiOptions options, SheetMusic sheetMusic) {
        this.sheetMusic = sheetMusic;
        startTime = SystemClock.uptimeMillis();
        pulsesPerMs = midiFile.getTime().getQuarter() * (1000.0 / options.tempo);
        startPulseTime = 0;
    }

    public void pause() { paused = true;}

    public void unpause() {
        if (!paused) { return; }
        startPulseTime = currentPulseTime;
        startTime = SystemClock.uptimeMillis();
        paused = false;
    }

    /**
     * Called at {@link MidiMessage} receiving
     *
     * @param message   the received message
     * @param timeStamp -1 if the timeStamp information is not available
     */
    @Override
    public void send(@NonNull MidiMessage message, long timeStamp) {
        if (paused || sheetMusic == null || handler == null) { return; }
        if (timeStamp == -1) {
            doShade.run();
        } // TODO: Loading/resetting breaks start time
        else {
            // TODO: Schedule shading for the provided timestamp
        }
    }

    private Runnable doShade = () -> {
        long msec = SystemClock.uptimeMillis() - startTime;
        double prevPulseTime = currentPulseTime;
        currentPulseTime = startPulseTime + msec * pulsesPerMs;
        // Handler used to avoid blocking while expensive UI drawing/Bitmap processing is done
        sheetMusic.ShadeNotes((int) currentPulseTime, (int) prevPulseTime, SheetMusic.GradualScroll);
    };

    /**
     * Close the {@link Receiver}
     */
    @Override
    public void close() {
        thread.quitSafely();
        handler = null;
    }
}
