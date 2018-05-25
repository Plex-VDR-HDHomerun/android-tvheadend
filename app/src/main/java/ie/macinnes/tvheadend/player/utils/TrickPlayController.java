/*
 * Copyright (c) 2018 Kiall Mac Innes <kiall@macinnes.ie>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ie.macinnes.tvheadend.player.utils;
import android.os.Handler;

import com.google.android.exoplayer2.ExoPlayer;

import ie.macinnes.tvheadend.player.source.PositionReference;

public class TrickPlayController {

    final private PositionReference position;
    final private ExoPlayer player;
    final private Handler handler;

    private long startTime = 0;
    private long startPosition = 0;
    private long playbackSpeed = 1;
    private boolean started = false;

    private Runnable doTick = new Runnable() {
        @Override
        public void run() {
            tick();
        }
    };

    public TrickPlayController(Handler handler, PositionReference position, ExoPlayer player) {
        this.position = position;
        this.player = player;
        this.handler = handler;
    }

    public void start(float speed) {
        if(speed == 1.0) {
            stop();
            return;
        }

        player.setPlayWhenReady(false);
        startTime = System.currentTimeMillis();
        startPosition = position.positionFromTimeUs(player.getCurrentPosition() * 1000);
        playbackSpeed = (int) speed;

        if(!started) {
            started = true;
            postTick();
        }

    }

    private void tick() {
        long diff = (System.currentTimeMillis() - startTime) * playbackSpeed;
        long seekPosition = startPosition + diff;

        // clamp to end position
        seekPosition = Math.min(seekPosition, position.getEndPosition());

        long timeUs = position.timeUsFromPosition(seekPosition);
        player.seekTo(timeUs / 1000);
    }

    public void stop() {
        if(!started) {
            return;
        }

        tick();
        reset();

        player.setPlayWhenReady(true);
    }

    public void reset() {
        handler.removeCallbacks(doTick);
        playbackSpeed = 1;
        started = false;
    }

    public void postTick() {
        handler.postDelayed(doTick, 100);
    }

    public boolean activated() {
        return started;
    }
}
