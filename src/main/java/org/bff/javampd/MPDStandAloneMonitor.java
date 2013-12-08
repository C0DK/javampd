/*
 * MPDStandAloneMonitor.java
 *
 * Created on October 18, 2005, 10:17 AM
 *
 * To change this template, choose Tools | Options and locate the template under
 * the Source Creation and Management node. Right-click the template and choose
 * Open. You can then make changes to the template in the Source Editor.
 */
package org.bff.javampd;

import com.google.inject.Inject;
import org.bff.javampd.events.*;
import org.bff.javampd.exception.MPDConnectionException;
import org.bff.javampd.exception.MPDException;
import org.bff.javampd.exception.MPDResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * MPDStandAloneMonitor monitors a MPD connection by querying the status and
 * statistics of the MPD server at given delay intervals.  As statistics change
 * appropriate events are fired indicating these changes.  If more detailed
 * events are desired attach listeners to the different controllers of a
 * connection or use the {@link org.bff.javampd.MPDEventRelayer} class.
 *
 * @author Bill
 * @version 1.0
 */
public class MPDStandAloneMonitor
        extends MPDEventMonitor
        implements StandAloneMonitor {

    private Logger logger = LoggerFactory.getLogger(MPDStandAloneMonitor.class);

    @Inject
    private Admin admin;

    private ServerStatus serverStatus;
    private final int delay;
    private int newVolume;
    private int oldVolume;
    private int newPlaylistVersion;
    private int oldPlaylistVersion;
    private int newPlaylistLength;
    private int oldPlaylistLength;
    private int oldSong;
    private int newSong;
    private int oldSongId;
    private int newSongId;
    private int oldBitrate;
    private int newBitrate;
    private long elapsedTime;
    private String state;
    private String error;
    private boolean stopped;
    private HashMap<Integer, MPDOutput> outputMap;

    private PlayerStatus status = PlayerStatus.STATUS_STOPPED;

    private static final int DEFAULT_DELAY = 1000;
    private List<PlayerBasicChangeListener> playerListeners;
    private List<PlaylistBasicChangeListener> playlistListeners;
    private List<VolumeChangeListener> volListeners;
    private List<MPDErrorListener> errorListeners;
    private List<OutputChangeListener> outputListeners;

    /**
     * Creates a new instance of MPDStandAloneMonitor using the default delay
     * of 1 second.
     */
    @Inject
    MPDStandAloneMonitor(ServerStatus serverStatus) {
        this(serverStatus, DEFAULT_DELAY);
    }

    /**
     * Creates a new instance of MPDStandAloneMonitor using the given delay interval
     * for queries.
     *
     * @param delay the delay interval
     */
    MPDStandAloneMonitor(ServerStatus serverStatus, int delay) {
        this.delay = delay;
        createListeners();
        this.outputMap = new HashMap<Integer, MPDOutput>();
        this.serverStatus = serverStatus;

        try {
            //initial load so no events fired
            List<String> response = new ArrayList<String>(serverStatus.getStatus());
            processResponse(response);
            loadOutputs(admin.getOutputs());
        } catch (MPDException ex) {
            logger.error("Problem with initialization", ex);
        }
    }

    private synchronized void createListeners() {
        this.playerListeners = new ArrayList<PlayerBasicChangeListener>();
        this.playlistListeners = new ArrayList<PlaylistBasicChangeListener>();
        this.volListeners = new ArrayList<VolumeChangeListener>();
        this.errorListeners = new ArrayList<MPDErrorListener>();
        this.outputListeners = new ArrayList<OutputChangeListener>();
    }

    @Override
    public synchronized void addPlayerChangeListener(PlayerBasicChangeListener pcl) {
        playerListeners.add(pcl);
    }

    @Override
    public synchronized void removePlayerChangeListener(PlayerBasicChangeListener pcl) {
        playerListeners.remove(pcl);
    }

    /**
     * Sends the appropriate {@link PlayerBasicChangeEvent.Status} to all registered
     * {@link PlayerBasicChangeListener}s.
     *
     * @param status the {@link PlayerBasicChangeEvent.Status}
     */
    protected synchronized void firePlayerChangeEvent(PlayerBasicChangeEvent.Status status) {
        PlayerBasicChangeEvent pce = new PlayerBasicChangeEvent(this, status);

        for (PlayerBasicChangeListener pcl : playerListeners) {
            pcl.playerBasicChange(pce);
        }
    }

    @Override
    public synchronized void addVolumeChangeListener(VolumeChangeListener vcl) {
        volListeners.add(vcl);
    }

    @Override
    public synchronized void removeVolumeChangedListener(VolumeChangeListener vcl) {
        volListeners.remove(vcl);
    }

    /**
     * Sends the appropriate {@link VolumeChangeEvent} to all registered
     * {@link VolumeChangeListener}.
     *
     * @param volume the new volume
     */
    protected synchronized void fireVolumeChangeEvent(int volume) {
        VolumeChangeEvent vce = new VolumeChangeEvent(this, volume);

        for (VolumeChangeListener vcl : volListeners) {
            vcl.volumeChanged(vce);
        }
    }

    @Override
    public synchronized void addOutputChangeListener(OutputChangeListener vcl) {
        outputListeners.add(vcl);
    }

    @Override
    public synchronized void removeOutputChangedListener(OutputChangeListener vcl) {
        outputListeners.remove(vcl);
    }

    /**
     * Sends the appropriate {@link OutputChangeEvent} to all registered
     * {@link OutputChangeListener}s.
     *
     * @param event the event id to send
     */
    protected synchronized void fireOutputChangeEvent(OutputChangeEvent event) {
        for (OutputChangeListener ocl : outputListeners) {
            ocl.outputChanged(event);
        }
    }

    @Override
    public synchronized void addPlaylistChangeListener(PlaylistBasicChangeListener pcl) {
        playlistListeners.add(pcl);
    }

    @Override
    public synchronized void removePlaylistStatusChangedListener(PlaylistBasicChangeListener pcl) {
        playlistListeners.remove(pcl);
    }

    /**
     * Sends the appropriate {@link PlaylistChangeEvent} to all registered
     * {@link PlaylistChangeListener}.
     *
     * @param event the {@link org.bff.javampd.events.PlaylistBasicChangeEvent.Event}
     */
    protected synchronized void firePlaylistChangeEvent(PlaylistBasicChangeEvent.Event event) {
        PlaylistBasicChangeEvent pce = new PlaylistBasicChangeEvent(this, event);

        for (PlaylistBasicChangeListener pcl : playlistListeners) {
            pcl.playlistBasicChange(pce);
        }
    }

    @Override
    public synchronized void addMPDErrorListener(MPDErrorListener el) {
        errorListeners.add(el);
    }

    @Override
    public synchronized void removeMPDErrorListener(MPDErrorListener el) {
        errorListeners.remove(el);
    }

    /**
     * Sends the appropriate {@link MPDErrorListener} to all registered
     * {@link MPDErrorListener}s.
     *
     * @param msg the event message
     */
    protected void fireMPDErrorEvent(String msg) {
        MPDErrorEvent ee = new MPDErrorEvent(this, msg);

        for (MPDErrorListener el : errorListeners) {
            el.errorEventReceived(ee);
        }
    }

    @Override
    public void run() {
        List<String> response;
        while (!isStopped()) {

            try {
                try {
                    synchronized (this) {
                        response = new ArrayList<String>(serverStatus.getStatus());
                        processResponse(response);

                        checkError();
                        checkPlayer();
                        checkPlaylist();
                        checkTrackPosition(elapsedTime);
                        checkVolume();
                        checkBitrate();
                        checkConnection();
                        checkOutputs();
                        this.wait(delay);
                    }
                } catch (InterruptedException ie) {
                    setStopped(true);
                }
            } catch (MPDException mce) {
                if (mce instanceof MPDConnectionException) {
                    fireConnectionChangeEvent(false, mce.getMessage());
                    boolean retry = true;

                    while (retry) {
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException ex) {
                            logger.error("StandAloneMonitor interrupted", ex);
                        }


                        checkConnection();
                        if (isConnectedState()) {
                            retry = false;
                        }
                    }
                }
            }
        }
    }

    @Override
    public void start() {
        setStopped(false);
        Executors.newSingleThreadExecutor().execute(this);
    }

    @Override
    public void stop() {
        setStopped(true);
    }

    @Override
    public boolean isStopped() {
        return stopped;
    }

    @Override
    public PlayerStatus getStatus() {
        return status;
    }

    @Override
    public void clearListeners() {
        createListeners();
    }

    private void setStopped(boolean stopped) {
        this.stopped = stopped;
    }

    private void checkError() {
        if (error != null) {
            fireMPDErrorEvent(error);
        }
    }

    private void checkPlayer() {
        PlayerStatus newStatus = PlayerStatus.STATUS_STOPPED;
        if (state.startsWith(PlayerResponse.PLAY.getPrefix())) {
            newStatus = PlayerStatus.STATUS_PLAYING;
        } else if (state.startsWith(PlayerResponse.PAUSE.getPrefix())) {
            newStatus = PlayerStatus.STATUS_PAUSED;
        } else if (state.startsWith(PlayerResponse.STOP.getPrefix())) {
            newStatus = PlayerStatus.STATUS_STOPPED;
        }

        if (!status.equals(newStatus)) {
            switch (newStatus) {
                case STATUS_PLAYING:
                    switch (status) {
                        case STATUS_PAUSED:
                            firePlayerChangeEvent(PlayerBasicChangeEvent.Status.PLAYER_UNPAUSED);
                            break;
                        case STATUS_STOPPED:
                            firePlayerChangeEvent(PlayerBasicChangeEvent.Status.PLAYER_STARTED);
                            break;
                    }
                    break;
                case STATUS_STOPPED:
                    elapsedTime = 0; //when stopped no time in response reading 0
                    firePlayerChangeEvent(PlayerBasicChangeEvent.Status.PLAYER_STOPPED);
                    if (newSongId == -1) {
                        firePlaylistChangeEvent(PlaylistBasicChangeEvent.Event.PLAYLIST_ENDED);
                    }

                    break;
                case STATUS_PAUSED:
                    switch (status) {
                        case STATUS_PAUSED:
                            firePlayerChangeEvent(PlayerBasicChangeEvent.Status.PLAYER_UNPAUSED);
                            break;
                        case STATUS_PLAYING:
                            firePlayerChangeEvent(PlayerBasicChangeEvent.Status.PLAYER_PAUSED);
                            break;
                    }
            }
            status = newStatus;
        }
    }

    private int checkBitrateCount;

    private void checkBitrate() {
        if (checkBitrateCount == 7) {
            checkBitrateCount = 0;
            if (oldBitrate != newBitrate) {
                firePlayerChangeEvent(PlayerBasicChangeEvent.Status.PLAYER_BITRATE_CHANGE);
                oldBitrate = newBitrate;
            }
        } else {
            ++checkBitrateCount;
        }
    }

    private int checkOutputCount;

    /**
     * Checks the connection status of the MPD.  Fires a {@link ConnectionChangeEvent}
     * if the connection status changes.
     *
     * @throws MPDConnectionException if there is a problem with the connection
     * @throws MPDResponseException   if response is an error
     */
    protected final void checkOutputs() throws MPDConnectionException, MPDResponseException {
        if (checkOutputCount == 3) {
            checkOutputCount = 0;

            List<MPDOutput> outputs = new ArrayList<MPDOutput>(admin.getOutputs());
            if (outputs.size() > outputMap.size()) {
                fireOutputChangeEvent(new OutputChangeEvent(this, OutputChangeEvent.OUTPUT_EVENT.OUTPUT_ADDED));
                loadOutputs(outputs);
            } else if (outputs.size() < outputMap.size()) {
                fireOutputChangeEvent(new OutputChangeEvent(this, OutputChangeEvent.OUTPUT_EVENT.OUTPUT_DELETED));
                loadOutputs(outputs);
            } else {
                for (MPDOutput out : outputs) {
                    MPDOutput output = outputMap.get(out.getId());
                    if (output == null) {
                        fireOutputChangeEvent(new OutputChangeEvent(out, OutputChangeEvent.OUTPUT_EVENT.OUTPUT_CHANGED));
                        loadOutputs(outputs);
                        return;
                    } else {
                        if (output.isEnabled() != out.isEnabled()) {
                            fireOutputChangeEvent(new OutputChangeEvent(out, OutputChangeEvent.OUTPUT_EVENT.OUTPUT_CHANGED));
                            loadOutputs(outputs);
                            return;
                        }
                    }

                }
            }
        } else {
            ++checkOutputCount;
        }
    }

    private void loadOutputs(Collection<MPDOutput> outputs) {
        outputMap.clear();
        for (MPDOutput output : outputs) {
            outputMap.put(output.getId(), output);
        }
    }

    private int checkPlaylistCount;

    private void checkPlaylist() {
        if (checkPlaylistCount == 2) {

            checkPlaylistCount = 0;
            if (oldPlaylistVersion != newPlaylistVersion) {
                firePlaylistChangeEvent(PlaylistBasicChangeEvent.Event.PLAYLIST_CHANGED);
                oldPlaylistVersion = newPlaylistVersion;
            }

            if (oldPlaylistLength != newPlaylistLength) {
                if (oldPlaylistLength < newPlaylistLength) {
                    firePlaylistChangeEvent(PlaylistBasicChangeEvent.Event.SONG_ADDED);
                } else if (oldPlaylistLength > newPlaylistLength) {
                    firePlaylistChangeEvent(PlaylistBasicChangeEvent.Event.SONG_DELETED);
                }

                oldPlaylistLength = newPlaylistLength;
            }

            if (status == PlayerStatus.STATUS_PLAYING) {
                if (oldSong != newSong) {
                    firePlaylistChangeEvent(PlaylistBasicChangeEvent.Event.SONG_CHANGED);
                    oldSong = newSong;
                } else if (oldSongId != newSongId) {
                    firePlaylistChangeEvent(PlaylistBasicChangeEvent.Event.SONG_CHANGED);
                    oldSongId = newSongId;
                }
            }
        } else {
            ++checkPlaylistCount;
        }
    }

    private int checkVolumeCount;

    private void checkVolume() {
        if (checkVolumeCount == 5) {
            checkVolumeCount = 0;
            if (oldVolume != newVolume) {
                fireVolumeChangeEvent(newVolume);
                oldVolume = newVolume;
            }
        } else {
            ++checkVolumeCount;
        }
    }

    private void processResponse(List<String> response) {
        newSongId = -1;
        newSong = -1;
        error = null;

        for (String line : response) {
            if (line.startsWith(StatusList.VOLUME.getStatusPrefix())) {
                newVolume = Integer.parseInt(line.substring(StatusList.VOLUME.getStatusPrefix().length()).trim());
            }
            if (line.startsWith(StatusList.PLAYLIST.getStatusPrefix())) {
                newPlaylistVersion = Integer.parseInt(line.substring(StatusList.PLAYLIST.getStatusPrefix().length()).trim());
            }
            if (line.startsWith(StatusList.PLAYLISTLENGTH.getStatusPrefix())) {
                newPlaylistLength = Integer.parseInt(line.substring(StatusList.PLAYLISTLENGTH.getStatusPrefix().length()).trim());
            }
            if (line.startsWith(StatusList.STATE.getStatusPrefix())) {
                state = line.substring(StatusList.STATE.getStatusPrefix().length()).trim();
            }
            if (line.startsWith(StatusList.CURRENTSONG.getStatusPrefix())) {
                newSong = Integer.parseInt(line.substring(StatusList.CURRENTSONG.getStatusPrefix().length()).trim());
            }
            if (line.startsWith(StatusList.CURRENTSONGID.getStatusPrefix())) {
                newSongId = Integer.parseInt(line.substring(StatusList.CURRENTSONGID.getStatusPrefix().length()).trim());
            }
            if (line.startsWith(StatusList.TIME.getStatusPrefix())) {
                elapsedTime = Long.parseLong(line.substring(StatusList.TIME.getStatusPrefix().length()).trim().split(":")[0]);
            }
            if (line.startsWith(StatusList.BITRATE.getStatusPrefix())) {
                newBitrate = Integer.parseInt(line.substring(StatusList.BITRATE.getStatusPrefix().length()).trim());
            }
            if (line.startsWith(StatusList.ERROR.getStatusPrefix())) {
                error = line.substring(StatusList.ERROR.getStatusPrefix().length()).trim();
            }
        }
    }
}