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

package ie.macinnes.tvheadend.player.source;

public class PositionReference {

    private long timeUs = 0;

    private long startPosition;
    private long endPosition;
    private long currentPosition;

    public PositionReference() {
        reset();
    }

    public void reset() {
        startPosition = System.currentTimeMillis();
        endPosition = startPosition;
        currentPosition = startPosition;
    }

    public void set(long timeUs, long wallClockTime) {
        this.timeUs = timeUs;

        if(isPositionSane(wallClockTime)) {
            this.currentPosition = wallClockTime;
        }
    }

    public long getStartPosition() {
        return startPosition;
    }

    void setStartPosition(long pos) {
        if(!isPositionSane(pos)) {
            return;
        }

        startPosition = pos;
    }

    public long getDuration() {
        return endPosition - startPosition;
    }

    public long getEndPosition() {
        return endPosition;
    }

    void setEndPosition(long pos) {
        if(!isPositionSane(pos)) {
            return;
        }

        endPosition = pos;
    }

    public long positionFromTimeUs(long timeUs) {
        long diffMs = (timeUs - this.timeUs) / 1000;
        return currentPosition + diffMs;
    }

    private boolean isPositionSane(long pos) {
        long saneWindowMs = 3 * 60 * 60 * 1000;
        return (Math.abs(currentPosition - pos) <= saneWindowMs);
    }

    public long timeUsFromPosition(long position) {
        return timeUs + (position - currentPosition) * 1000;
    }

}
