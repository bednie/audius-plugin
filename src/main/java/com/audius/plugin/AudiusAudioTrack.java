package com.audius.plugin;

import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3AudioTrack; // Likely needed for processing MP3 streams
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager; // Needed for getSourceManager method
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser; // Needed if you parse JSON in the track
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools; // Needed if you use HttpClientTools in the track
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface; // Needed for HTTP interactions
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream; // Often used with DelegatedAudioTrack for seeking
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import org.apache.http.client.methods.HttpGet; // Needed for making GET requests

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException; // Needed for throws clauses
import java.net.URI; // Needed for PersistentHttpStream
import java.net.URLEncoder; // Needed for URL encoding
import java.nio.charset.StandardCharsets; // Needed for URL encoding charset

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.FAULT;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;

// Custom AudioTrack class for Audius source, based on DelegatedAudioTrack pattern
public class AudiusAudioTrack extends DelegatedAudioTrack { // Extend DelegatedAudioTrack

    private static final Logger log = LoggerFactory.getLogger(AudiusAudioTrack.class);

    // Reference back to the source manager to use its HTTP client and selected provider
    private final AudiusAudioSourceManager sourceManager;

    // Constructor
    public AudiusAudioTrack(AudioTrackInfo trackInfo, AudiusAudioSourceManager sourceManager) {
        super(trackInfo); // Call the constructor of DelegatedAudioTrack
        this.sourceManager = sourceManager;
    }

    // This method is called by Lavaplayer when the track is about to start playing.
    // Its job is to get the actual stream URL and delegate processing to another track implementation.
    @Override
    public void process(LocalAudioTrackExecutor localExecutor) throws Exception {
        // The trackInfo.identifier contains the Audius track ID that we set in loadItem.
        String trackId = this.trackInfo.identifier;

        // --- Stream URL Fetching Logic ---
        // Call a helper method within this track class to get the stream URL from Audius API
        // getAudiusStreamUrl will now fetch the URL from the JSON response using no_redirect=true
        String streamUrl = getAudiusStreamUrl(trackId);

        if (streamUrl == null || streamUrl.isEmpty()) {
            log.error("Could not get Audius stream URL for ID: {}", trackId);
            throw new FriendlyException("Could not get Audius stream URL for track ID: " + trackId, FAULT, null);
        }

        // --- Stream Execution ---
        // Once you have the stream URL, create an HttpAudioStream or similar and delegate.
        // We will use PersistentHttpStream for seeking.
        // We will likely need Mp3AudioTrack if Audius streams are MP3.

        try (HttpInterface httpInterface = sourceManager.getHttpInterface()) { // Get an HttpInterface from the source manager
            log.debug("Starting Audius track from stream URL: {}", streamUrl);
            // Delegate processing to Mp3AudioTrack using the PersistentHttpStream with the obtained URL
            // Use PersistentHttpStream to handle seeking and connecting to the final stream URL
            processDelegate(new Mp3AudioTrack(trackInfo, new PersistentHttpStream(httpInterface, new URI(streamUrl), null)), localExecutor);

        } catch (IOException e) {
            log.error("Error reading Audius stream for track ID: {}", trackId, e);
            throw new FriendlyException("Error reading Audius stream for track ID: " + trackId, FAULT, e);
        } catch (Exception e) { // Catch URI or other exceptions during stream setup
            log.error("An unexpected error occurred setting up Audius stream for track ID: {}", trackId, e);
            throw new FriendlyException("An unexpected error occurred setting up Audius stream for track ID: " + trackId, FAULT, e);
        }
    }

    // This method is required by DelegatedAudioTrack (similar to makeClone)
    @Override
    protected AudioTrack makeShallowClone() {
        // Return a new instance of your custom track with the same AudioTrackInfo and source manager reference.
        return new AudiusAudioTrack(this.trackInfo, this.sourceManager);
    }

    // This method is required by DelegatedAudioTrack
    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }


    // --- Helper Method within the Track Class ---

    // Method to fetch the actual stream URL for a given Audius track ID
    // Uses the /tracks/:track_id/stream endpoint with no_redirect=true and parses the JSON response.
    private String getAudiusStreamUrl(String trackId) throws IOException {
        log.info("Fetching actual Audius stream URL for track ID: {}", trackId);

        String providerUrl = sourceManager.getSelectedDiscoveryProvider();

        if (providerUrl == null) {
            log.error("Cannot fetch stream URL for track ID {} - discovery provider not available.", trackId);
            throw new IOException("Audius discovery provider not available."); // Throw IOException for process method's catch
        }

        try (HttpInterface httpInterface = sourceManager.getHttpInterface()) { // Get an HttpInterface to make the API call

            // Construct the stream API URL with no_redirect=true
            String apiUrl = String.format("%s/v1/tracks/%s/stream?app_name=%s&no_redirect=true",
                    providerUrl,
                    URLEncoder.encode(trackId, StandardCharsets.UTF_8),
                    URLEncoder.encode(sourceManager.getAppName(), StandardCharsets.UTF_8));

            log.debug("Audius stream API URL (no_redirect=true) for ID {}: {}", trackId, apiUrl);

            // Make the HTTP call and parse the JSON response
            HttpGet get = new HttpGet(apiUrl);
            JsonBrowser response = HttpClientTools.fetchResponseAsJson(httpInterface, get);

            if (response == null || response.isNull()) {
                log.warn("Audius stream API for ID {} returned null or empty response (no_redirect=true).", trackId);
                throw new IOException("Audius stream API returned null or empty response for track ID " + trackId);
            }

            // Check for common Audius API error structure
            JsonBrowser error = response.get("error");
            if (error != null && !error.isNull()) {
                String errorMessage = error.text();
                log.warn("Audius stream API for ID {} returned error: {}", trackId, errorMessage);
                // If resource not found via stream endpoint, treat as no results
                if (errorMessage != null && (errorMessage.toLowerCase().contains("not found") || errorMessage.toLowerCase().contains("resource for id"))) {
                    log.info("Audius stream resource not found for ID: {}", trackId);
                    return null; // Return null to indicate no stream found
                }
                throw new IOException("Audius stream API error for track ID " + trackId + ": " + errorMessage);
            }

            // *** CORRECTED: Extract the stream URL string directly from the 'data' field ***
            // Based on your curl output, the stream URL is the string value of the 'data' field.
            JsonBrowser dataNode = response.get("data");

            if (dataNode == null || dataNode.isNull() || dataNode.text() == null || dataNode.text().isEmpty()) {
                log.warn("Stream URL 'data' field is missing, null, or empty in response for ID {} (no_redirect=true). Response: {}", trackId, response.toString());
                throw new IOException("Stream URL not found in Audius API response for track ID " + trackId);
            }

            // Get the text value of the data node - this is the actual stream URL string
            String finalStreamUrl = dataNode.text();


            log.info("Fetched final Audius stream URL for ID {} (no_redirect=true): {}", trackId, finalStreamUrl);
            return finalStreamUrl; // Return the fetched stream URL


        } catch (IOException e) {
            // Re-throw IOExceptions encountered during fetching
            throw e;
        } catch (Exception e) {
            // Wrap other unexpected exceptions in IOException
            log.error("An unexpected error occurred fetching stream URL for track ID {} (no_redirect=true).", trackId, e);
            throw new IOException("An unexpected error occurred fetching Audius stream URL.", e);
        }
    }
}