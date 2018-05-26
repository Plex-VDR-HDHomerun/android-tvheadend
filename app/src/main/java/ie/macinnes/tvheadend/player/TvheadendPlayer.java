/*
 * Copyright (c) 2017 Kiall Mac Innes <kiall@macinnes.ie>
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

package ie.macinnes.tvheadend.player;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.media.PlaybackParams;
import android.media.tv.TvContract;
import android.media.AudioFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.CaptioningManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.audio.AudioCapabilities;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.RenderersFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.audio.AudioCapabilities;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.text.CaptionStyleCompat;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.ui.DebugTextViewHelper;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.google.android.exoplayer2.upstream.DefaultAllocator;
import com.google.android.exoplayer2.util.MimeTypes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import ie.macinnes.htsp.SimpleHtspConnection;
import ie.macinnes.tvheadend.Constants;
import ie.macinnes.tvheadend.R;
import ie.macinnes.tvheadend.TvContractUtils;
import ie.macinnes.tvheadend.player.utils.TrickPlayController;
import ie.macinnes.tvheadend.player.source.PositionReference;

public class TvheadendPlayer implements com.google.android.exoplayer2.Player.EventListener, VideoRendererEventListener, AudioRendererEventListener {

    private static final String TAG = "TvheadendPlayer";

    private static final float CAPTION_LINE_HEIGHT_RATIO = 0.0533f;
    private static final int TEXT_UNIT_PIXELS = 0;
    private static final long INVALID_TIMESHIFT_TIME = HtspDataSource.INVALID_TIMESHIFT_TIME;
    private static final int DEFAULT_MIN_BUFFER_MS = 3000;
    private static final int DEFAULT_MAX_BUFFER_MS = 5000;
    private static final int DEFAULT_BUFFER_FOR_PLAYBACK_MS = 1000;
    private static final int DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2000;

    public interface Listener {
        /**
         * Called when ther player state changes.
         *
         * @param playWhenReady Whether playback will proceed when ready.
         * @param playbackState One of the {@code STATE} constants defined in the {@link ExoPlayer}
         *     interface.
         */
        void onPlayerStateChanged(boolean playWhenReady, int playbackState);

        void onPlayerError(Exception e);

        void onTracksChanged(StreamBundle bundle);

        void onAudioTrackChanged(Format format);

        void onVideoTrackChanged(Format format);

        void onRenderedFirstFrame();
    }

    private final Context mContext;
    private final SimpleHtspConnection mConnection;
    private final Listener mListener;

    private final Handler mHandler;
    private final Timer mTimer;
    private final SharedPreferences mSharedPreferences;

    private SimpleExoPlayer mExoPlayer;
    private RenderersFactory mRenderersFactory;
    private TvheadendTrackSelector mTrackSelector;
    private LoadControl mLoadControl;
    private EventLogger mEventLogger;
    private HtspDataSource.Factory mHtspSubscriptionDataSourceFactory;
    private HtspDataSource.Factory mHtspFileInputStreamDataSourceFactory;
    private HtspDataSource mDataSource;
    private ExtractorsFactory mExtractorsFactory;

    private View mOverlayView;
    private DebugTextViewHelper mDebugViewHelper;
    private SubtitleView mSubtitleView;
    private LinearLayout mRadioInfoView;

    private MediaSource mMediaSource;

    private Uri mCurrentChannelUri;

    final private PositionReference position;
    final private TrickPlayController trickPlayController;

    public TvheadendPlayer(Context context, SimpleHtspConnection connection, Listener listener) {
        mContext = context;
        mConnection = connection;
        mListener = listener;

        AudioCapabilities audioCapabilities = AudioCapabilities.getCapabilities(context);

        mHandler = new Handler();
        position = new PositionReference();
        mTimer = new Timer();
        boolean passthrough = audioCapabilities.supportsEncoding(AudioFormat.ENCODING_AC3);
        trickPlayController = new TrickPlayController(mHandler, position, mExoPlayer);
        mSharedPreferences = mContext.getSharedPreferences(
                Constants.PREFERENCE_TVHEADEND, Context.MODE_PRIVATE);

        buildExoPlayer();
    }

    public void open(Uri channelUri) {
        // Stop any existing playback
        stop();

        mCurrentChannelUri = channelUri;

        // Create the media source
        if (channelUri.getHost().equals("channel")) {
            buildHtspChannelMediaSource(channelUri);
        } else {
            buildHtspRecordingMediaSource(channelUri);
        }

        // Prepare the media source
        mExoPlayer.prepare(mMediaSource);
    }

    public void release() {
        // Stop any existing playback
        stop();

        if (mDebugViewHelper != null) {
            mDebugViewHelper.stop();
            mDebugViewHelper = null;
        }

        mSubtitleView = null;
        mOverlayView = null;

        // Release ExoPlayer
        mExoPlayer.removeListener(this);
        mExoPlayer.release();
    }

    public void setSurface(Surface surface) {
        mExoPlayer.setVideoSurface(surface);
    }

    public void setVolume(float volume) {
        mExoPlayer.setVolume(volume);
    }

    public boolean selectTrack(int type, String trackId) {
        return mTrackSelector.selectTrack(type, trackId);
    }

    public void play() {
        trickPlayController.stop();
        mExoPlayer.setPlayWhenReady(true);
    }

    public void pause() {
        trickPlayController.stop();
        mExoPlayer.setPlayWhenReady(false);
    }

    public boolean isPaused() {
        return !mExoPlayer.getPlayWhenReady();
    }

    public void seek(long position) {
        long p = this.position.timeUsFromPosition(Math.max(position, this.position.getStartPosition()));
        mExoPlayer.seekTo(p / 1000);
    }

    @TargetApi(23)
    public void setPlaybackParams(PlaybackParams params) {
        Log.d(TAG, "speed: " + params.getSpeed());
        trickPlayController.start(params.getSpeed());
    }

    private void stop() {
        trickPlayController.reset();
        mExoPlayer.stop();
        mTrackSelector.clearSelectionOverrides();
        mHtspSubscriptionDataSourceFactory.releaseCurrentDataSource();
        mHtspFileInputStreamDataSourceFactory.releaseCurrentDataSource();
        position.reset();

        if (mMediaSource != null) {
            mMediaSource.releaseSource();
        }
    }
    public long getTimeshiftStartPosition() {
        if (mDataSource != null) {
            long startTime = mDataSource.getTimeshiftStartTime();
            if (startTime != INVALID_TIMESHIFT_TIME) {
                // For live content
                return startTime / 1000;
            } else {
                // For recorded content
                return 0;
            }
        } else {
            Log.w(TAG, "Unable to getTimeshiftStartPosition, no HtspDataSource available");
        }

        return INVALID_TIMESHIFT_TIME;
    }

    public long getTimeshiftCurrentPosition() {
        if (mDataSource != null) {
            long offset = mDataSource.getTimeshiftOffsetPts();
            if (offset != INVALID_TIMESHIFT_TIME) {
                // For live content
                return System.currentTimeMillis() + (offset / 1000);
            } else {
                // For recorded content
                mExoPlayer.getCurrentPosition();
            }
        } else {
            Log.w(TAG, "Unable to getTimeshiftCurrentPosition, no HtspDataSource available");
        }

        return INVALID_TIMESHIFT_TIME;
    }

    public View getOverlayView(CaptioningManager.CaptionStyle captionStyle, float fontScale) {
        if (mOverlayView == null) {
            LayoutInflater lI = (LayoutInflater) mContext.getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE);
            mOverlayView = lI.inflate(R.layout.player_overlay_view, null);
        }

        if (mDebugViewHelper == null) {
            mDebugViewHelper = getDebugTextView();

            if (mDebugViewHelper != null) {
                mDebugViewHelper.start();
            }
        }

        if (mSubtitleView == null) {
            mSubtitleView = getSubtitleView(captionStyle, fontScale);

            if (mSubtitleView != null) {
                mExoPlayer.setTextOutput(mSubtitleView);
            }
        }

        if (mRadioInfoView == null) {
            mRadioInfoView = mOverlayView.findViewById(R.id.radio_info_view);
        }

        return mOverlayView;
    }

    private DebugTextViewHelper getDebugTextView() {
        final boolean enableDebugTextView = mSharedPreferences.getBoolean(
                Constants.KEY_DEBUG_TEXT_VIEW_ENABLED,
                mContext.getResources().getBoolean(R.bool.pref_default_debug_text_view_enabled)
        );

        if (enableDebugTextView) {
            TextView textView = mOverlayView.findViewById(R.id.debug_text_view);
            textView.setVisibility(View.VISIBLE);
            return new DebugTextViewHelper(
                    mExoPlayer, textView);
        } else {
            return null;
        }
    }

    private SubtitleView getSubtitleView(CaptioningManager.CaptionStyle captionStyle, float fontScale) {
        SubtitleView view = mOverlayView.findViewById(R.id.subtitle_view);

        CaptionStyleCompat captionStyleCompat = CaptionStyleCompat.createFromCaptionStyle(captionStyle);

        float captionTextSize = getCaptionFontSize();
        captionTextSize *= fontScale;

        final boolean applyEmbeddedStyles = mSharedPreferences.getBoolean(
                Constants.KEY_CAPTIONS_APPLY_EMBEDDED_STYLES,
                mContext.getResources().getBoolean(R.bool.pref_default_captions_apply_embedded_styles)
        );

        view.setStyle(captionStyleCompat);
        view.setVisibility(View.VISIBLE);
        view.setFixedTextSize(TEXT_UNIT_PIXELS, captionTextSize);
        view.setApplyEmbeddedStyles(applyEmbeddedStyles);

        return view;
    }

    // Misc Internal Methods
    private void buildExoPlayer() {
        mRenderersFactory = new TvheadendRenderersFactory(mContext);
        mTrackSelector = buildTrackSelector();
        mLoadControl = buildLoadControl();

        mExoPlayer = ExoPlayerFactory.newSimpleInstance(mRenderersFactory, mTrackSelector, mLoadControl);
        mExoPlayer.addListener(this);

        // Add the EventLogger
        mEventLogger = new EventLogger(mTrackSelector);
        mExoPlayer.addListener(mEventLogger);
        mExoPlayer.setAudioDebugListener(mEventLogger);
        mExoPlayer.setVideoDebugListener(mEventLogger);

        final String streamProfile = mSharedPreferences.getString(
                Constants.KEY_HTSP_STREAM_PROFILE,
                mContext.getResources().getString(R.string.pref_default_htsp_stream_profile)
        );

        // Produces DataSource instances through which media data is loaded.
        mHtspSubscriptionDataSourceFactory = new HtspSubscriptionDataSource.Factory(mContext, mConnection, streamProfile);
        mHtspFileInputStreamDataSourceFactory = new HtspFileInputStreamDataSource.Factory(mContext, mConnection);

        // Produces Extractor instances for parsing the media data.
        mExtractorsFactory = new TvheadendExtractorsFactory(mContext);
    }

    private TvheadendTrackSelector buildTrackSelector() {
        TrackSelection.Factory trackSelectionFactory =
                new AdaptiveTrackSelection.Factory(null);

        TvheadendTrackSelector trackSelector = new TvheadendTrackSelector(trackSelectionFactory);

        final boolean enableAudioTunneling = mSharedPreferences.getBoolean(
            Constants.KEY_AUDIO_TUNNELING_ENABLED,
            mContext.getResources().getBoolean(R.bool.pref_default_audio_tunneling_enabled)
        );

        if (enableAudioTunneling) {
            trackSelector.setTunnelingAudioSessionId(C.generateAudioSessionIdV21(mContext));
        }

        return trackSelector;
    }

    private LoadControl buildLoadControl() {
        int bufferForPlaybackMs = Integer.parseInt(
                mSharedPreferences.getString(
                        Constants.KEY_BUFFER_PLAYBACK_MS,
                        mContext.getResources().getString(R.string.pref_default_buffer_playback_ms)
                )
        );

        return new DefaultLoadControl(
                new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
                DEFAULT_MIN_BUFFER_MS,
                DEFAULT_MAX_BUFFER_MS,
                bufferForPlaybackMs,
                DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                C.LENGTH_UNSET,
                false
        );
    }

    private void buildHtspChannelMediaSource(Uri channelUri) {
        // This is the MediaSource representing the media to be played.
        mMediaSource = new ExtractorMediaSource(channelUri,
                mHtspSubscriptionDataSourceFactory, mExtractorsFactory, null, mEventLogger);
    }

    private void buildHtspRecordingMediaSource(Uri recordingUri) {
        // This is the MediaSource representing the media to be played.
        mMediaSource = new ExtractorMediaSource(recordingUri,
                mHtspFileInputStreamDataSourceFactory, mExtractorsFactory, null, mEventLogger);
    }

    private float getCaptionFontSize() {
        Display display = ((WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();
        Point displaySize = new Point();
        display.getSize(displaySize);
        return Math.max(mContext.getResources().getDimension(R.dimen.subtitle_minimum_font_size),
                CAPTION_LINE_HEIGHT_RATIO * Math.min(displaySize.x, displaySize.y));
    }

    private boolean getTrackStatusBoolean(TrackSelection selection, TrackGroup group,
                                          int trackIndex) {
        return selection != null && selection.getTrackGroup() == group
                && selection.indexOf(trackIndex) != C.INDEX_UNSET;
    }

    // ExoPlayer.EventListener Methods
    @Override
    public void onTimelineChanged(Timeline timeline, Object manifest) {
        // Don't care about this event here
    }

    @Override
    public void onRepeatModeChanged(int i) {
        // Don't care about this event here
    }

    @Override
    public void onAudioTrackChanged(Format format) {
        if(format == null) {
            return;
        }
        mListener.onAudioTrackChanged(format);
    }

    @Override
    public void onTracksChanged(TrackGroupArray trackGroupArray, TrackSelectionArray trackSelectionArray) {
        if(mListener == null) {
            return;
        }

        for(int i = 0; i < trackSelectionArray.length; i++) {
            TrackSelection selection = trackSelectionArray.get(i);

            // skip disabled renderers
            if(selection == null) {
                continue;
            }

            Format format = selection.getSelectedFormat();

            // selected audio track
            if(MimeTypes.isAudio(format.sampleMimeType)) {
                mListener.onAudioTrackChanged(format);
            }

            // selected video track
            if(MimeTypes.isVideo(format.sampleMimeType)) {
                mListener.onVideoTrackChanged(format);
            }
        }
    }

    private void enableRadioInfoScreen() {
        // No video track available, use the channel logo as a substitute
        Log.i(TAG, "No video track available");

        try {
            String channelName = TvContractUtils.getChannelName(mContext, Integer.parseInt(mCurrentChannelUri.getPath().substring(1)));
            TextView radioChannelName = mRadioInfoView.findViewById(R.id.radio_channel_name);
            radioChannelName.setText(channelName);

            ImageView radioChannelIcon = mRadioInfoView.findViewById(R.id.radio_channel_icon);

            long androidChannelId = TvContractUtils.getChannelId(mContext, Integer.parseInt(mCurrentChannelUri.getPath().substring(1)));
            Uri channelIconUri = TvContract.buildChannelLogoUri(androidChannelId);

            InputStream is = mContext.getContentResolver().openInputStream(channelIconUri);

            BitmapDrawable iconImage = new BitmapDrawable(mContext.getResources(), is);
            radioChannelIcon.setImageDrawable(iconImage);

            mRadioInfoView.setVisibility(View.VISIBLE);
        } catch (IOException e) {
            Log.e(TAG, "Failed to fetch logo", e);
        }
    }

    private void disableRadioInfoScreen() {
        Log.d(TAG, "Video track is available");
        mRadioInfoView.setVisibility(View.INVISIBLE);
    }

    @Override
    public void onLoadingChanged(boolean isLoading) {
        if (isLoading) {
            // Fetch the current DataSource for later use
            // TODO: Hold a WeakReference to the DataSource instead...
            // TODO: We should know if we're playing a channel or a recording...
            mDataSource = mHtspSubscriptionDataSourceFactory.getCurrentDataSource();
            if (mDataSource == null) {
                mDataSource = mHtspFileInputStreamDataSourceFactory.getCurrentDataSource();
            }
        }
    }

    @Override
    public void onVideoEnabled(DecoderCounters counters) {
    }

    @Override
    public void onVideoDecoderInitialized(String decoderName, long initializedTimestampMs, long initializationDurationMs) {
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        mListener.onPlayerStateChanged(playWhenReady, playbackState);
    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
        mListener.onPlayerError(error);
    }

    @Override
    public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
    }

    @Override
    public void onRenderedFirstFrame(Surface surface) {
        if(trickPlayController.activated()) {
            trickPlayController.postTick();
            return;
        }

        mListener.onRenderedFirstFrame();
    }

    @Override
    public void onVideoInputFormatChanged(Format format) {

    }

    @Override
    public void onVideoDisabled(DecoderCounters counters) {
    }

    @Override
    public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
    }

    @Override
    public void onPositionDiscontinuity(int reason) {
    }

    @Override
    public void onTracksChanged(StreamBundle bundle) {
        mListener.onTracksChanged(bundle);
    }

    @Override
    public void onSeekProcessed() {

    }

    @Override
    public void onAudioInputFormatChanged(Format format) {
        //listener.onAudioTrackChanged(format);
    }

    @Override
    public void onAudioSinkUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {

    }

    @Override
    public void onAudioDecoderInitialized(String decoderName, long initializedTimestampMs, long initializationDurationMs) {
    }

    @Override
    public void onAudioEnabled(DecoderCounters counters) {
    }

    @Override
    public void onAudioDisabled(DecoderCounters counters) {
    }

    @Override
    public void onDroppedFrames(int count, long elapsedMs) {
    }

    @Override
    public void onAudioSessionId(int audioSessionId) {
    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
        // Don't care about this event here
    }
}
