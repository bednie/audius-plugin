package com.audius.plugin;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;

import org.apache.http.client.methods.HttpGet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// Remove Spring annotations if not running within a standard Lavalink context
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.FAULT;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;


// Remove @Service and @PostConstruct if not running within a Spring context.
@Service
public class AudiusAudioSourceManager implements AudioSourceManager {

    private static final Logger log = LoggerFactory.getLogger(AudiusAudioSourceManager.class);

    private static final String SOURCE_NAME = "audius";
    private static final String SEARCH_PREFIX = "audsearch:";

    // This regex matches audius.co/[userhandle]/[slug](/...)
    private static final String AUDIUS_URL_REGEX = "^https?://(?:www\\.)?audius\\.co/([^/]+)/([^/]+)(?:/.*)?$";
    private static final Pattern audiusUrlPattern = Pattern.compile(AUDIUS_URL_REGEX);

    private static final String AUDIUS_PLAYLIST_URL_REGEX = "^https?://(?:www\\.)?audius\\.co/([^/]+)/playlist/([^/]+)(?:/.*)?$";
    private static final Pattern audiusPlayListUrlPattern = Pattern.compile(AUDIUS_PLAYLIST_URL_REGEX);


    private static final String DISCOVERY_PROVIDERS_URL = "https://api.audius.co"; // Endpoint to get list of available providers

    private static final String APP_NAME = "YourLavalinkAudiusPlugin"; // TODO: Replace with a unique name for attribution

    // --- Instance Variables ---
    private final HttpInterfaceManager httpInterfaceManager;
    private volatile String selectedDiscoveryProvider = null;

    // --- Constructor ---
    public AudiusAudioSourceManager() {
        this.httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
        log.info("Initializing AudiusAudioSourceManager...");
        // Move initialization logic from @PostConstruct method to the constructor
        fetchAndSelectDiscoveryProvider();
        log.info("AudiusAudioSourceManager initialized. Provider: {}", selectedDiscoveryProvider != null ? selectedDiscoveryProvider : "None selected");
    }

    // --- Initialization Method ---
    // Remove @PostConstruct if not using Spring
    // @PostConstruct
    // private void initialize() { ... }


    // Method to fetch the list of discovery providers and select one
    private void fetchAndSelectDiscoveryProvider() {
        log.info("Fetching Audius discovery providers from {}", DISCOVERY_PROVIDERS_URL);
        List<JsonBrowser> providers = Collections.emptyList();

        try (HttpInterface httpInterface = httpInterfaceManager.getInterface()) {
            HttpGet get = new HttpGet(DISCOVERY_PROVIDERS_URL);
            JsonBrowser response = HttpClientTools.fetchResponseAsJson(httpInterface, get);

            if (response == null || response.isNull()) {
                log.error("Audius discovery providers endpoint returned null or empty response.");
                return;
            }

            JsonBrowser error = response.get("error");
            if (error != null && !error.isNull()) {
                String errorMessage = error.text();
                log.error("Audius discovery providers endpoint returned error: {}", errorMessage);
                return;
            }

            JsonBrowser dataNode = response.get("data");

            if (dataNode != null && !dataNode.isNull()) {
                try {
                    List<JsonBrowser> potentialProviders = dataNode.values();
                    if (potentialProviders != null && !potentialProviders.isEmpty()) {
                        providers = potentialProviders;
                        log.debug("Attempted to get values from data node. List size: {}", providers.size());
                    } else {
                        log.info("Audius discovery providers response contains data, but it's not a non-empty array of items."); // Corrected log message, removed 'query'
                        providers = Collections.emptyList();
                    }

                } catch (Exception e) {
                    log.error("Failed to get values from 'data' node. Response may not be a valid list structure.", e);
                    providers = Collections.emptyList();
                }
            } else {
                log.warn("Audius discovery providers response does not contain a non-null 'data' field.");
            }


            if (providers.isEmpty()) {
                log.error("Audius discovery providers list is empty or could not be successfully parsed.");
            } else {
                JsonBrowser firstProviderNode = providers.get(0);
                if (firstProviderNode != null && !firstProviderNode.isNull() && firstProviderNode.text() != null && !firstProviderNode.text().isEmpty()) {
                    selectedDiscoveryProvider = firstProviderNode.text();
                    log.info("Selected Audius discovery provider: {}", selectedDiscoveryProvider);
                } else {
                    log.error("First item in Audius discovery providers list is null, invalid, or has no text value.");
                }
            }

        } catch (IOException e) {
            log.error("Failed to fetch Audius discovery providers due to IO error.", e);
        } catch (FriendlyException e) {
            log.error("Failed to fetch Audius discovery providers (FriendlyException).", e);
        } catch (Exception e) {
            log.error("An unexpected error occurred during Audius discovery provider fetching or initial processing.", e);
        }
    }


    @Override
    public String getSourceName() {
        return SOURCE_NAME;
    }


    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        return true;
    }


    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
        // The AudioTrackInfo object (containing title, author, length, identifier, uri, etc.)
        // is already decoded by Lavaplayer's default process before this method is called.
        // 'input' would be used to read any *additional* custom data you wrote in encodeTrack.
        // Since our encodeTrack is empty, we don't read from 'input'.

        // We just need to reconstruct our custom AudiusAudioTrack object
        // using the provided trackInfo and a reference back to this source manager instance.

        // Ensure trackInfo is not null (shouldn't happen in practice, but defensive coding)
        if (trackInfo == null) {
            log.error("Attempted to decode track with null AudioTrackInfo.");
            throw new IOException("Cannot decode track: Provided AudioTrackInfo is null.");
        }

        log.debug("Decoding Audius track with identifier: {}", trackInfo.identifier);

        // Create a new instance of your custom AudiusAudioTrack
        // The trackInfo object contains the necessary details like the Audius track ID (stored in identifier).
        return new com.audius.plugin.AudiusAudioTrack(trackInfo, this);
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) throws IOException {
        // No custom data to encode for Audius tracks currently.
        // If you needed to save extra plugin-specific data with the track, you would write it here.
    }

    @Override
    public void shutdown() {
        try {
            if (httpInterfaceManager != null) {
                httpInterfaceManager.close();
            }
            log.info("AudiusAudioSourceManager shut down.");
        } catch (Exception e) {
            log.error("Error during AudiusAudioSourceManager shutdown", e);
        }
    }

    // Main method to handle Audius URLs (tracks, playlists) using the /resolve endpoint
    private AudioItem handleAudiusUrl(String providerUrl, String fullUrl) {
        log.info("Attempting to resolve Audius URL {} using provider {}", fullUrl, providerUrl);

        try {
            // Use the /v1/resolve endpoint. fetchJsonFromUrl follows the redirect.
            // The response from fetchJsonFromUrl is the response from the final redirected URL
            // (e.g., /v1/tracks?slug=...&handle=... or /v1/playlists?slug=...&handle=...).
            String resolveUrl = String.format("%s/v1/resolve?url=%s&app_name=%s",
                    providerUrl,
                    URLEncoder.encode(fullUrl, StandardCharsets.UTF_8),
                    URLEncoder.encode(APP_NAME, StandardCharsets.UTF_8));

            log.debug("Audius resolve API URL: {}", resolveUrl);

            // fetchJsonFromUrl handles network errors, API errors (not resource not found),
            // and returns null if a resource not found error is detected.
            JsonBrowser response = fetchJsonFromUrl(resolveUrl); // This follows the redirect

            // fetchJsonFromUrl now returns null for resource not found errors, check here.
            if (response == null) {
                log.info("Audius resolve API for URL '{}' returned no data (resource not found via fetchJsonFromUrl).", fullUrl);
                return AudioReference.NO_TRACK; // Treat resource not found as no track
            }

            JsonBrowser resourceNode = response.get("data");

            // Remove the check for 'kindNode' as 'kind' is not at this level.
            JsonBrowser resourceIdNode = resourceNode != null ? resourceNode.get("id") : null;

            // This condition now only checks if the data node is null, empty, or missing a valid ID.
            if (resourceNode == null || resourceNode.isNull() || resourceIdNode == null || resourceIdNode.isNull() || resourceIdNode.text() == null || resourceIdNode.text().isEmpty()) {
                log.warn("Audius resolve API response 'data' field is missing or invalid (missing ID). Final Response Data: {}", resourceNode != null ? resourceNode.toString() : "null");
                throw new FriendlyException("Could not resolve Audius URL: Invalid data structure in response after resolution.", SUSPICIOUS, null);
            }

            String resourceId = resourceIdNode.text(); // Get the ID text

            // Check for fields characteristic of a track or playlist.
            JsonBrowser resourceTitleNode = resourceNode.get("title"); // Common in tracks
            JsonBrowser resourcePlaylistNameNode = resourceNode.get("playlist_name"); // Common in playlists

            if (resourceTitleNode != null && !resourceTitleNode.isNull() && resourceTitleNode.text() != null && !resourceTitleNode.text().isEmpty()) {
                // It looks like a track (has an ID and a title)
                log.info("Resolved URL '{}' to a track with ID '{}'.", fullUrl, resourceId);
                return buildTrackFromNode(resourceNode, fullUrl); // Build track directly from resourceNode

            //} else if (resourcePlaylistNameNode != null && !resourcePlaylistNameNode.isNull() && resourcePlaylistNameNode.text() != null && !resourcePlaylistNameNode.text().isEmpty()) {
            //    // It looks like a playlist (has an ID and a playlist_name)
            //    log.info("Resolved URL '{}' to a playlist with ID '{}'.", fullUrl, resourceId);
            //    return buildPlaylistFromNode(resourceNode, fullUrl); // Build playlist directly from resourceNode

            } else {
                // It has a valid ID but doesn't look like a track (missing title) or a playlist (missing playlist_name)
                log.warn("Resolved URL '{}' to resource with ID '{}' but unable to determine type (missing title or playlist_name). Resource Data: {}", fullUrl, resourceId, resourceNode.toString());
                throw new FriendlyException("Could not resolve Audius URL: Unable to determine resource type.", SUSPICIOUS, null);
            }


        } catch (FriendlyException e) {
            throw e; // Re-throw FriendlyExceptions we created
        } catch (IOException e) {
            log.error("Failed to load Audius URL '{}' due to IO error: {}", fullUrl, e.getMessage()); // Log just the message to avoid excessive stacktrace
            throw new FriendlyException("Failed to load Audius URL.", FAULT, e); // Corrected FAault to FAULT
        } catch (Exception e) {
            log.error("An unexpected error occurred during Audius URL loading for '{}'.", fullUrl, e);
            throw new FriendlyException("An unexpected error occurred during Audius URL loading.", FAULT, e); // Corrected FAault to FAULT
        }
    }


    // Helper to build an AudioTrack from a JSON node (dataNode content for a track)
    private AudioTrack buildTrackFromNode(JsonBrowser trackNode, String sourceUrl) {
        log.debug("Building track from node: {}", trackNode.toString());

        // Extract track info - VERIFY FIELD NAMES! (Confirmed against your JSON sample)
        JsonBrowser userInfo = trackNode.get("user");
        String id = trackNode.get("id").text();
        String title = trackNode.get("title").text();
        String author = (userInfo != null && !userInfo.isNull() && userInfo.get("name") != null && !userInfo.get("name").isNull()) ? userInfo.get("name").text() : "Unknown Artist";
        long durationSec = trackNode.get("duration").asLong(-1); // Duration is in seconds in the API
        long durationMs = durationSec != -1 ? durationSec * 1000 : com.sedmelluq.discord.lavaplayer.tools.Units.DURATION_MS_UNKNOWN;
        String permalink = trackNode.get("permalink").text();
        String artworkUrl = getArtworkUrl(trackNode.get("artwork"));

        // Basic validation
        if (id == null || id.isEmpty() || title == null || title.isEmpty() || permalink == null || permalink.isEmpty()) {
            log.warn("Track node missing essential info. ID: {}, Title: {}, Permalink: {}", id, title, permalink);
            throw new FriendlyException("Audius track details are incomplete.", SUSPICIOUS, null);
        }

        AudioTrackInfo trackInfo = new AudioTrackInfo(
                title,
                author,
                durationMs,
                id, // Use the Audius track ID as the identifier
                false, // isStream
                permalink, // URI
                artworkUrl, // Artwork URL
                null // ISRC
        );

        log.debug("Built track: {}", trackInfo.title);
        return new com.audius.plugin.AudiusAudioTrack(trackInfo, this);
    }


    /**
     * Fetches the full JSON details for a single track by its Audius ID.
     *
     * @param providerUrl The URL of the selected Audius discovery provider.
     * @param trackId     The ID of the track to fetch.
     * @return A JsonBrowser representing the track's full data, or null if not found or an error occurs.
     * @throws IOException If an IO error occurs during the HTTP request.
     */
    private JsonBrowser fetchTrackDetailsById(String providerUrl, String trackId) throws IOException {
        log.debug("Attempting to fetch track details for ID '{}' using provider {}", trackId, providerUrl);

        try {
            // Construct the API URL for fetching a track by ID
            String apiUrl = String.format("%s/v1/tracks/%s",
                    providerUrl,
                    URLEncoder.encode(trackId, StandardCharsets.UTF_8)); // Ensure track ID is URL encoded

            log.debug("Audius get track by ID API URL: {}", apiUrl);

            // Use the generic helper to fetch JSON
            // fetchJsonFromUrl returns null for not found or top-level API errors
            JsonBrowser response = fetchJsonFromUrl(apiUrl);

            if (response == null) {
                log.warn("Audius track details API for ID '{}' returned no data (resource not found or API error via fetchJsonFromUrl).", trackId);
                // fetchJsonFromUrl already logged the underlying reason (not found/error)
                return null; // Indicate track not found or couldn't be fetched
            }

            // The track data object is directly under the "data" key for this endpoint
            JsonBrowser trackNode = response.get("data");

            // Basic validation that we got a valid track object
            if (trackNode == null || trackNode.isNull() || trackNode.get("id").isNull()) {
                log.warn("Audius track details API response for ID '{}' is missing the main 'data' field or track ID.", trackId);
                // Treat as if the track wasn't found or data was invalid
                return null;
            }

            log.debug("Successfully fetched track details for ID '{}'.", trackId);
            return trackNode; // Return the JSON browser for the single track object

        } catch (IOException e) {
            log.error("Failed to fetch Audius track details for ID '{}' due to IO error: {}", trackId, e.getMessage());
            // Propagate IO exceptions so the caller can decide how to handle it (e.g., skip track or fail playlist)
            throw e;
        } catch (Exception e) {
            log.error("An unexpected error occurred fetching track details for ID '{}'.", trackId, e);
            // Wrap other exceptions in IOException for consistency or handle as needed
            throw new IOException("An unexpected error occurred fetching track details for track ID " + trackId, e);
        }
    }


    /**
     * Handles loading a complete Audius playlist from a URL.
     * Resolves the playlist URL, fetches track IDs, then fetches details for each track.
     *
     * @param providerUrl The URL of the selected Audius discovery provider.
     * @param playlistUrl The full URL of the Audius playlist.
     * @return A BasicAudioPlaylist object containing the playlist information and its tracks.
     */
    private AudioItem handleAudiusPlaylistUrl(String providerUrl, String playlistUrl) {
        log.info("Attempting to load Audius playlist from URL {} using provider {}", playlistUrl, providerUrl);

        try {
            // Step 1: Resolve the playlist URL to get playlist metadata and track IDs
            String resolveUrl = String.format("%s/v1/resolve?url=%s&app_name=%s",
                    providerUrl,
                    URLEncoder.encode(playlistUrl, StandardCharsets.UTF_8),
                    URLEncoder.encode(APP_NAME, StandardCharsets.UTF_8));

            log.debug("Audius resolve API URL for playlist: {}", resolveUrl);

            JsonBrowser resolveResponse = fetchJsonFromUrl(resolveUrl);

            if (resolveResponse == null) {
                log.info("Audius resolve API for playlist URL '{}' returned no data (playlist not found via fetchJsonFromUrl).", playlistUrl);
                return AudioReference.NO_TRACK; // Playlist URL did not resolve to a resource
            }

            // The resolved item (the playlist) is expected to be the first element in the "data" array
            JsonBrowser playlistNode = resolveResponse.get("data").index(0);

            // Basic validation for the playlist node structure
            // Corrected: Use isNull() check and presence of the node itself instead of isText()
            if (playlistNode == null || playlistNode.isNull() || playlistNode.get("playlist_name") == null || playlistNode.get("playlist_name").isNull()) {
                log.warn("Audius resolve API response for playlist URL '{}' is missing the main 'data' field or playlist name.", playlistUrl);
                throw new FriendlyException("Could not load Audius playlist: Invalid data structure in response.", SUSPICIOUS, null);
            }

            // Extract playlist-level metadata
            String playlistTitle = playlistNode.get("playlist_name").text();
            JsonBrowser userNode = playlistNode.get("user");
            // Corrected: Use isNull() and text() != null checks instead of isText()
            String playlistAuthor = (userNode != null && !userNode.isNull() && userNode.get("name") != null && !userNode.get("name").isNull() && userNode.get("name").text() != null && !userNode.get("name").text().isEmpty()) ? userNode.get("name").text() : "Unknown Artist";
            String playlistPermalink = playlistNode.get("permalink").text(); // Use as source URL for tracks
            JsonBrowser artworkNode = playlistNode.get("artwork"); // Get artwork node


            // Get the list of track ID objects from the "playlist_contents" array
            JsonBrowser playlistContentsNode = playlistNode.get("playlist_contents");
            List<JsonBrowser> trackIdNodesList = Collections.emptyList(); // Default to empty

            // Corrected: Check for null/isNull and wrap values() in try-catch like handleAudiusSearch
            // This is the standard pattern when isArray() is not available or preferred.
            if (playlistContentsNode != null && !playlistContentsNode.isNull()) {
                try {
                    // Attempt to get values (treats as array if successful)
                    trackIdNodesList = playlistContentsNode.values();
                } catch (Exception e) { // values() might throw if not an array or other issue
                    log.warn("Playlist URL '{}' contained 'playlist_contents' but could not be processed as an array.", playlistUrl, e);
                    // Keep trackIdNodesList as empty list
                }
            } else {
                log.warn("Playlist URL '{}' response did not contain a non-null 'playlist_contents' field.", playlistUrl);
            }


            if (trackIdNodesList.isEmpty()) {
                log.info("Playlist '{}' from URL '{}' is empty or 'playlist_contents' was not a valid list.", playlistTitle, playlistUrl);
                // Return an empty playlist
                return new BasicAudioPlaylist(playlistTitle != null ? playlistTitle : "Unknown Playlist", Collections.emptyList(), null, false);
            }

            // Step 2: Fetch details for each track ID and build AudioTrack objects
            List<AudioTrack> tracks = new ArrayList<>();
            log.debug("Fetching details for {} tracks in playlist '{}'.", trackIdNodesList.size(), playlistTitle);

            for (JsonBrowser trackIdNode : trackIdNodesList) {
                // Check if trackIdNode is valid and has a non-null/empty "track_id" field
                if (trackIdNode == null || trackIdNode.isNull() || trackIdNode.get("track_id") == null || trackIdNode.get("track_id").isNull() || trackIdNode.get("track_id").text() == null || trackIdNode.get("track_id").text().isEmpty()) {
                    log.warn("Skipping invalid track entry in playlist from URL '{}' (missing or invalid 'track_id'). Node: {}", playlistUrl, trackIdNode != null ? trackIdNode.toString() : "null");
                    continue; // Skip this entry if track_id is missing or invalid
                }

                String trackId = trackIdNode.get("track_id").text(); // Extract the track_id string

                try {
                    // Fetch the full details for the individual track
                    JsonBrowser trackDetailsNode = fetchTrackDetailsById(providerUrl, trackId);

                    if (trackDetailsNode != null) {
                        // Build the AudioTrack object using the existing helper method
                        // Pass the playlist's permalink as the source URL for tracks within the playlist
                        tracks.add(buildTrackFromNode(trackDetailsNode, playlistPermalink));
                    } else {
                        // fetchTrackDetailsById logs a reason if it returns null
                        log.warn("Could not fetch details for track ID '{}' in playlist from URL '{}'. Skipping track.", trackId, playlistUrl);
                    }
                } catch (IOException e) {
                    log.warn("IO error fetching details for track ID '{}' in playlist from URL '{}'. Skipping track.", trackId, playlistUrl, e);
                    // Skip track on IO errors during fetching its details
                } catch (FriendlyException e) {
                    log.warn("Skipping track ID '{}' in playlist from URL '{}' due to error building track: {}", trackId, playlistUrl, e.getMessage());
                    // Skip track if buildTrackFromNode fails (e.g., missing essential fields)
                } catch (Exception e) {
                    log.error("An unexpected error occurred processing track ID '{}' in playlist from URL '{}'. Skipping track.", trackId, playlistUrl, e);
                    // Catch any other unexpected errors during track processing
                }
            }

            if (tracks.isEmpty() && !trackIdNodesList.isEmpty()) {
                log.warn("Playlist '{}' from URL '{}' contained track IDs, but no valid tracks could be processed.", playlistTitle, playlistUrl);
                // You might decide to return NO_TRACK here if no tracks could be loaded at all
                return new BasicAudioPlaylist(playlistTitle != null ? playlistTitle : "Unknown Playlist", Collections.emptyList(), null, false);
            } else if (tracks.isEmpty()) {
                log.info("Playlist '{}' from URL '{}' is empty after processing (already logged).", playlistTitle, playlistUrl);
            }


            // Step 3: Create and return the BasicAudioPlaylist
            log.info("Successfully loaded playlist '{}' with {} tracks from URL '{}'.", playlistTitle, tracks.size(), playlistUrl);
            return new BasicAudioPlaylist(playlistTitle != null ? playlistTitle : "Unknown Playlist", tracks, null, false); // selectedTrack is null, isSearchResult is false

        } catch (FriendlyException e) {
            // Re-throw FriendlyExceptions created within this method
            throw e;
        } catch (IOException e) {
            log.error("Failed to load Audius playlist URL '{}' due to IO error.", playlistUrl, e);
            throw new FriendlyException("Failed to load Audius playlist due to a network error.", FAULT, e);
        } catch (Exception e) {
            log.error("An unexpected error occurred during Audius playlist loading for '{}'.", playlistUrl, e);
            throw new FriendlyException("An unexpected error occurred while loading the Audius playlist.", FAULT, e);
        }
    }

    // Modify your existing loadItem method to call handleAudiusPlaylistUrl
    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        if (selectedDiscoveryProvider == null) {
            log.warn("Audius discovery provider not available. Cannot load item: {}", reference.identifier);
            throw new FriendlyException("Audius source is not initialized. Discovery provider not available.", FAULT, null);
        }

        if (reference.identifier.startsWith(SEARCH_PREFIX)) {
            String query = reference.identifier.substring(SEARCH_PREFIX.length()).trim();
            log.error("Detected Audius search prefix: {}", reference.identifier); // Changed log level

            if (!query.isEmpty()) {
                return handleAudiusSearch(selectedDiscoveryProvider, query, reference.identifier);
            } else {
                return AudioReference.NO_TRACK;
            }
        }

        Matcher playlistUrlMatcher = audiusPlayListUrlPattern.matcher(reference.identifier);
        if (playlistUrlMatcher.matches()) {
            log.info("Detected Audius playlist URL: {}", reference.identifier);
            // Call the new method specifically for playlists
            return handleAudiusPlaylistUrl(selectedDiscoveryProvider, reference.identifier);
        }


        // Handle other direct Audius URLs (including tracks resolved via /v1/resolve)
        Matcher urlMatcher = audiusUrlPattern.matcher(reference.identifier);
        if (urlMatcher.matches()) {
            log.info("Detected general Audius URL (attempting to resolve): {}", reference.identifier); // Added/Changed log level
            // Keep using handleAudiusUrl for other URL types that might resolve to a track
            // handleAudiusUrl uses /v1/resolve which can handle track URLs not matching
            // the strict track regex, as well as other resource types if you add handlers later.
            // NOTE: handleAudiusUrl currently also has playlist handling logic - you might
            // want to remove that from handleAudiusUrl now that handleAudiusPlaylistUrl exists,
            // or ensure handleAudiusUrl specifically delegates to handleAudiusPlaylistUrl
            // if /v1/resolve indicates a playlist.
            // For now, let's keep the delegation clear: playlist regex calls handleAudiusPlaylistUrl.
            // If a playlist URL somehow didn't match the regex but ended up in the general URL matcher,
            // handleAudiusUrl *might* still detect it as a playlist and call buildPlaylistFromNode
            // (which would need adjustment or replacement by a call to handleAudiusPlaylistUrl).
            // For clarity, let's assume the regex catches playlists and the rest go to handleAudiusUrl.
            return handleAudiusUrl(selectedDiscoveryProvider, reference.identifier); // Assuming handleAudiusUrl still handles track URLs
        }


        return null; // Not an Audius item identifier
    }


    // Helper method to extract artwork URL from the nested artwork object
    private String getArtworkUrl(JsonBrowser artworkNode) {
        if (artworkNode == null || artworkNode.isNull()) {
            return null; // No artwork node
        }
        // Try to get a preferred size, fallback if not available
        JsonBrowser urlNode = artworkNode.get("480x480");
        if (urlNode == null || urlNode.isNull() || urlNode.text() == null || urlNode.text().isEmpty()) {
            urlNode = artworkNode.get("150x150"); // Fallback to 150x150
        }
        if (urlNode == null || urlNode.isNull() || urlNode.text() == null || urlNode.text().isEmpty()) {
            urlNode = artworkNode.get("1000x1000"); // Fallback to 1000x1000
        }

        return (urlNode != null && !urlNode.isNull()) ? urlNode.text() : null;
    }


    // Generic helper to fetch JSON from a URL and handle basic errors
    private JsonBrowser fetchJsonFromUrl(String url) throws IOException {
        try (HttpInterface httpInterface = httpInterfaceManager.getInterface()) {
            HttpGet get = new HttpGet(url);
            JsonBrowser response = HttpClientTools.fetchResponseAsJson(httpInterface, get);

            if (response == null || response.isNull()) {
                log.warn("API endpoint {} returned null or empty response.", url);
                // *** CORRECTED: Treat null/empty response as resource not found ***
                log.info("Audius API resource not found (null/empty response) for URL: {}", url);
                return null; // Return null to indicate no resource found
            }

            JsonBrowser error = response.get("error");
            if (error != null && !error.isNull()) {
                String errorMessage = error.text();
                log.warn("API endpoint {} returned error: {}", url, errorMessage);
                // Check if it's a resource not found error based on the message content (VERIFY)
                if (errorMessage != null && (errorMessage.toLowerCase().contains("not found") || errorMessage.toLowerCase().contains("resource for id"))) {
                    // If resource not found, treat as no results rather than a hard error
                    log.info("Audius API resource not found for URL: {}", url);
                    return null; // Return null to indicate no resource found
                }
                throw new IOException("Audius API error from " + url + ": " + errorMessage);
            }

            // Based on the /v1/tracks?slug&handle JSON, the resource data IS directly in the 'data' field.
            // fetchJsonFromUrl's job is just to get the JSON and handle top-level API errors and resource not found (by returning null).
            // The caller (handleAudiusUrl, handleAudiusSearch) will handle the structure within 'data'.

            // *** CORRECTED: Only check for data field if a top-level error was NOT present ***
            // If error is null/missing AND data is null/missing, that's still an issue.
            JsonBrowser data = response.get("data");
            if (data == null || data.isNull()) {
                log.warn("API endpoint {} response is missing non-null 'data' field after successful call (no top-level error). Response: {}", url, response.toString());
                throw new IOException("Audius API response is missing data field from " + url);
            }

            return response; // Return the full response JSON
        } catch (IOException e) {
            if (!(e instanceof IOException)) {
                throw new IOException("IO error fetching data from " + url, e);
            }
            throw e;
        } catch (Exception e) {
            log.error("An unexpected error occurred fetching data from {}.", url, e);
            throw new IOException("An unexpected error occurred fetching data from Audius API " + url, e);
        }
    }


    // Method to handle Audius search queries (using custom AudiusAudioTrack)
    private AudioItem handleAudiusSearch(String providerUrl, String query, String sourceUrl) {
        log.info("Attempting to search Audius for '{}' using provider {}", query, providerUrl);

        try (HttpInterface httpInterface = httpInterfaceManager.getInterface()) {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String apiUrl = String.format("%s/v1/tracks/search?query=%s&app_name=%s",
                    providerUrl, encodedQuery, URLEncoder.encode(APP_NAME, StandardCharsets.UTF_8));

            log.debug("Audius search API URL: {}", apiUrl);

            HttpGet get = new HttpGet(apiUrl);
            JsonBrowser response = HttpClientTools.fetchResponseAsJson(httpInterface, get);

            if (response == null || response.isNull()) {
                log.warn("Audius search API for query '{}' returned null or empty response.", query);
                return AudioReference.NO_TRACK;
            }

            JsonBrowser error = response.get("error");
            if (error != null && !error.isNull()) {
                String errorMessage = error.text();
                log.warn("Audius search API for query '{}' returned error: {}", query, errorMessage);
                return AudioReference.NO_TRACK; // Return NO_TRACK on search API errors
            }

            JsonBrowser dataNode = response.get("data");

            List<JsonBrowser> trackNodes = Collections.emptyList();
            if (dataNode != null && !dataNode.isNull()) {
                try {
                    List<JsonBrowser> potentialTrackNodes = dataNode.values();
                    if (potentialTrackNodes != null && !potentialTrackNodes.isEmpty()) {
                        trackNodes = potentialTrackNodes;
                        log.debug("Successfully extracted {} track nodes from search response for query '{}'.", trackNodes.size(), query);
                    } else {
                        log.info("Audius search API for query '{}' returned data, but it's not a non-empty array of items.", query);
                        trackNodes = Collections.emptyList();
                    }
                } catch (Exception e) {
                    log.error("Failed to get values from 'data' node in search response for query '{}'. Response structure unexpected.", query, e);
                    trackNodes = Collections.emptyList();
                }
            } else {
                log.warn("Audius search API response for query '{}' does not contain a non-null 'data' field.", query);
            }


            if (trackNodes.isEmpty()) {
                log.info("Audius search API for query '{}' returned no results or the data could not be processed as a list.", query);
                return AudioReference.NO_TRACK;
            }

            List<AudioTrack> tracks = new ArrayList<>();
            for (JsonBrowser trackNode : trackNodes) {
                JsonBrowser idNode = trackNode != null ? trackNode.get("id") : null;

                if (trackNode == null || trackNode.isNull() || idNode == null || idNode.isNull() || idNode.text() == null || idNode.text().isEmpty()) { // Corrected typo: idNodeIdNode -> idNode
                    log.warn("Skipping null or invalid track node in search results for query '{}' (missing ID). Node: {}", query, trackNode != null ? trackNode.toString() : "null");
                    continue;
                }

                // Build track from the node using the helper
                try {
                    // Pass the original search identifier string as sourceUrl for search results
                    tracks.add(buildTrackFromNode(trackNode, sourceUrl));
                } catch (FriendlyException e) {
                    log.warn("Skipping track in search results for query '{}' due to incomplete data: {}", query, e.getMessage());
                    continue; // Skip this track if building failed
                }


            }

            if (tracks.isEmpty()) {
                log.info("Audius search API for query '{}' returned results, but no valid tracks could be processed.", query);
                return AudioReference.NO_TRACK;
            }

            // Use the correct constructor for BasicAudioPlaylist (name, tracks, selectedTrack, isSearchResult)
            return new BasicAudioPlaylist("Audius search results for: " + query, tracks, null, true);

        } catch (IOException e) {
            log.error("Failed to search Audius for query '{}' due to IO error: {}", query, e.getMessage());
            throw new FriendlyException("Failed to search Audius.", FAULT, e);
        } catch (FriendlyException e) {
            log.error("Failed to search Audius for query '{}' (FriendlyException).", query, e);
            throw e;
        } catch (Exception e) {
            log.error("An unexpected error occurred during Audius search for query '{}'.", query, e);
            throw new FriendlyException("An unexpected error occurred during Audius search.", FAULT, e);
        }
    }


    public HttpInterface getHttpInterface() {
        if (httpInterfaceManager == null) {
            throw new IllegalStateException("HttpInterfaceManager is not initialized.");
        }
        return httpInterfaceManager.getInterface();
    }

    public String getSelectedDiscoveryProvider() {
        return selectedDiscoveryProvider;
    }

    public String getAppName() {
        return APP_NAME;
    }
}