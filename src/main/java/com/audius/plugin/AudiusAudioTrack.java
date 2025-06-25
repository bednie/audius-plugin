package com.audius.plugin;

import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3AudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import org.apache.http.client.methods.HttpGet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.FAULT;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;

// Custom AudioTrack class for Audius source, based on DelegatedAudioTrack pattern
public class AudiusAudioTrack extends DelegatedAudioTrack {

    private static final Logger log = LoggerFactory.getLogger(AudiusAudioTrack.class);
    private final AudiusAudioSourceManager sourceManager;

    public AudiusAudioTrack(AudioTrackInfo trackInfo, AudiusAudioSourceManager sourceManager) {
        super(trackInfo);
        this.sourceManager = sourceManager;
    }

    @Override
    public void process(LocalAudioTrackExecutor localExecutor) throws Exception {
        String trackId = this.trackInfo.identifier;
        String streamUrl = getAudiusStreamUrl(trackId);

        if (streamUrl == null || streamUrl.isEmpty()) {
            log.error("Could not get Audius stream URL for ID: {}", trackId);
            throw new FriendlyException("Could not get Audius stream URL for track ID: " + trackId, FAULT, null);
        }

        try (HttpInterface httpInterface = sourceManager.getHttpInterface()) {
            log.debug("Starting Audius track from stream URL: {}", streamUrl);
            processDelegate(new Mp3AudioTrack(trackInfo, new PersistentHttpStream(httpInterface, new URI(streamUrl), null)), localExecutor);

        } catch (IOException e) {
            log.error("Error reading Audius stream for track ID: {}", trackId, e);
            throw new FriendlyException("Error reading Audius stream for track ID: " + trackId, FAULT, e);
        } catch (Exception e) { 
            log.error("An unexpected error occurred setting up Audius stream for track ID: {}", trackId, e);
            throw new FriendlyException("An unexpected error occurred setting up Audius stream for track ID: " + trackId, FAULT, e);
        }
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new AudiusAudioTrack(this.trackInfo, this.sourceManager);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    private String getAudiusStreamUrl(String trackId) throws IOException {
        log.info("Fetching actual Audius stream URL for track ID: {}", trackId);

        String providerUrl = sourceManager.getSelectedDiscoveryProvider();

        if (providerUrl == null) {
            log.error("Cannot fetch stream URL for track ID {} - discovery provider not available.", trackId);
            throw new IOException("Audius discovery provider not available.");
        }

        try (HttpInterface httpInterface = sourceManager.getHttpInterface()) { 
            String apiUrl = String.format("%s/v1/tracks/%s/stream?app_name=%s&no_redirect=true",
                    providerUrl,
                    URLEncoder.encode(trackId, StandardCharsets.UTF_8),
                    URLEncoder.encode(sourceManager.getAppName(), StandardCharsets.UTF_8));

            log.debug("Audius stream API URL (no_redirect=true) for ID {}: {}", trackId, apiUrl);

            HttpGet get = new HttpGet(apiUrl);
            JsonBrowser response = HttpClientTools.fetchResponseAsJson(httpInterface, get);

            if (response == null || response.isNull()) {
                log.warn("Audius stream API for ID {} returned null or empty response (no_redirect=true).", trackId);
                throw new IOException("Audius stream API returned null or empty response for track ID " + trackId);
            }

            JsonBrowser error = response.get("error");
            if (error != null && !error.isNull()) {
                String errorMessage = error.text();
                log.warn("Audius stream API for ID {} returned error: {}", trackId, errorMessage);

                if (errorMessage != null && (errorMessage.toLowerCase().contains("not found") || errorMessage.toLowerCase().contains("resource for id"))) {
                    log.info("Audius stream resource not found for ID: {}", trackId);
                    return null;
                }
                throw new IOException("Audius stream API error for track ID " + trackId + ": " + errorMessage);
            }

            JsonBrowser dataNode = response.get("data");

            if (dataNode == null || dataNode.isNull() || dataNode.text() == null || dataNode.text().isEmpty()) {
                log.warn("Stream URL 'data' field is missing, null, or empty in response for ID {} (no_redirect=true). Response: {}", trackId, response.toString());
                throw new IOException("Stream URL not found in Audius API response for track ID " + trackId);
            }

            String finalStreamUrl = dataNode.text();


            log.info("Fetched final Audius stream URL for ID {} (no_redirect=true): {}", trackId, finalStreamUrl);
            return finalStreamUrl;


        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            log.error("An unexpected error occurred fetching stream URL for track ID {} (no_redirect=true).", trackId, e);
            throw new IOException("An unexpected error occurred fetching Audius stream URL.", e);
        }
    }
}