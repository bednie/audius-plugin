package com.audius.plugin;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
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
import org.springframework.stereotype.Service;

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

@Service
public class AudiusAudioSourceManager implements AudioSourceManager {

    private static final Logger log = LoggerFactory.getLogger(AudiusAudioSourceManager.class);

    private static final String SOURCE_NAME = "audius";
    private static final String SEARCH_PREFIX = "audsearch:";

    // Match Audius tracks
    private static final String AUDIUS_URL_REGEX = "^https?://(?:www\\.)?audius\\.co/([^/]+)/([^/]+)(?:/.*)?$";
    private static final Pattern audiusUrlPattern = Pattern.compile(AUDIUS_URL_REGEX);

    // Match Audius playlists
    private static final String AUDIUS_PLAYLIST_URL_REGEX = "^https?://(?:www\\.)?audius\\.co/([^/]+)/playlist/([^/]+)(?:/.*)?$";
    private static final Pattern audiusPlayListUrlPattern = Pattern.compile(AUDIUS_PLAYLIST_URL_REGEX);

    // Match Audius album playlists
    private static final String AUDIUS_ALBUM_URL_REGEX = "^https?://(?:www\\.)?audius\\.co/([^/]+)/album/([^/]+)(?:/.*)?$";
    private static final Pattern audiusAlbumUrlPattern = Pattern.compile(AUDIUS_ALBUM_URL_REGEX);

    private static final String DISCOVERY_PROVIDERS_URL = "https://api.audius.co"; // Endpoint to get list of available providers
    private static final String APP_NAME = "AudiusLavaLinkPlugin";
    private final HttpInterfaceManager httpInterfaceManager;
    private volatile String selectedDiscoveryProvider = null;

    public AudiusAudioSourceManager() {
        this.httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
        log.info("Initializing AudiusAudioSourceManager...");
        fetchAndSelectDiscoveryProvider();
        log.info("AudiusAudioSourceManager initialized. Provider: {}", selectedDiscoveryProvider != null ? selectedDiscoveryProvider : "None selected");
    }

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
                        log.info("Audius discovery providers response contains data, but it's not a non-empty array of items.");
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
        if (trackInfo == null) {
            log.error("Attempted to decode track with null AudioTrackInfo.");
            throw new IOException("Cannot decode track: Provided AudioTrackInfo is null.");
        }

        log.debug("Decoding Audius track with identifier: {}", trackInfo.identifier);
        return new com.audius.plugin.AudiusAudioTrack(trackInfo, this);
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) {
        // No custom data to encode for Audius tracks currently.
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

    private AudioItem handleAudiusUrl(String providerUrl, String fullUrl) {
        log.info("Attempting to resolve Audius URL {} using provider {}", fullUrl, providerUrl);

        try {
            // Use the /v1/resolve endpoint. fetchJsonFromUrl follows the redirect.
            // The response from fetchJsonFromUrl is the response from the final redirected URL
            // (e.g., /v1/tracks?slug=...&handle=... or /v1/playlists?slug=...&handle=...).
            String resolveUrl = String.format("%s/v1/resolve?url=%s&app_name=%s",
                    providerUrl,
                    fullUrl,
                    URLEncoder.encode(APP_NAME, StandardCharsets.UTF_8));

            log.debug("Audius resolve API URL: {}", resolveUrl);

            JsonBrowser response = fetchJsonFromUrl(resolveUrl); // This follows the redirect

            if (response == null) {
                log.info("Audius resolve API for URL '{}' returned no data (resource not found via fetchJsonFromUrl).", fullUrl);
                return AudioReference.NO_TRACK; // Treat resource not found as no track
            }

            JsonBrowser resourceNode = response.get("data");
            JsonBrowser resourceIdNode = resourceNode != null ? resourceNode.get("id") : null;

            if (resourceNode == null || resourceNode.isNull() || resourceIdNode == null || resourceIdNode.isNull() || resourceIdNode.text() == null || resourceIdNode.text().isEmpty()) {
                log.warn("Audius resolve API response 'data' field is missing or invalid (missing ID). Final Response Data: {}", resourceNode != null ? resourceNode.toString() : "null");
                throw new FriendlyException("Could not resolve Audius URL: Invalid data structure in response after resolution.", SUSPICIOUS, null);
            }

            String resourceId = resourceIdNode.text();
            JsonBrowser resourceTitleNode = resourceNode.get("title");

            if (resourceTitleNode != null && !resourceTitleNode.isNull() && resourceTitleNode.text() != null && !resourceTitleNode.text().isEmpty()) {
                log.info("Resolved URL '{}' to a track with ID '{}'.", fullUrl, resourceId);
                return buildTrackFromNode(resourceNode, fullUrl);
            } else {
                log.warn("Resolved URL '{}' to resource with ID '{}' but unable to determine type (missing title or playlist_name). Resource Data: {}", fullUrl, resourceId, resourceNode);
                throw new FriendlyException("Could not resolve Audius URL: Unable to determine resource type.", SUSPICIOUS, null);
            }

        } catch (FriendlyException e) {
            throw e;
        } catch (IOException e) {
            log.error("Failed to load Audius URL '{}' due to IO error: {}", fullUrl, e.getMessage());
            throw new FriendlyException("Failed to load Audius URL.", FAULT, e);
        } catch (Exception e) {
            log.error("An unexpected error occurred during Audius URL loading for '{}'.", fullUrl, e);
            throw new FriendlyException("An unexpected error occurred during Audius URL loading.", FAULT, e);
        }
    }

    private AudioTrack buildTrackFromNode(JsonBrowser trackNode, String ignoredSourceUrl) {
        log.debug("Building track from node: {}", trackNode.toString());

        JsonBrowser userInfo = trackNode.get("user");
        String id = trackNode.get("id").text();
        String title = trackNode.get("title").text();
        String author = (userInfo != null && !userInfo.isNull() && userInfo.get("name") != null && !userInfo.get("name").isNull()) ? userInfo.get("name").text() : "Unknown Artist";
        long durationSec = trackNode.get("duration").asLong(-1); // Duration is in seconds in the API
        long durationMs = durationSec != -1 ? durationSec * 1000 : com.sedmelluq.discord.lavaplayer.tools.Units.DURATION_MS_UNKNOWN;
        String permalink = trackNode.get("permalink").text();
        String artworkUrl = getArtworkUrl(trackNode.get("artwork"));

        if (id == null || id.isEmpty() || title == null || title.isEmpty() || permalink == null || permalink.isEmpty()) {
            log.warn("Track node missing essential info. ID: {}, Title: {}, Permalink: {}", id, title, permalink);
            throw new FriendlyException("Audius track details are incomplete.", SUSPICIOUS, null);
        }

        AudioTrackInfo trackInfo = new AudioTrackInfo(
                title,
                author,
                durationMs,
                id,
                false,
                permalink,
                artworkUrl,
                null
        );

        log.debug("Built track: {}", trackInfo.title);
        return new com.audius.plugin.AudiusAudioTrack(trackInfo, this);
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
            // Resolve the playlist URL to get playlist metadata and its ID
            String resolveUrl = String.format("%s/v1/resolve?url=%s&app_name=%s",
                    providerUrl,
                    playlistUrl,
                    URLEncoder.encode(APP_NAME, StandardCharsets.UTF_8));

            log.debug("Audius resolve API URL for playlist: {}", resolveUrl);

            JsonBrowser resolveResponse = fetchJsonFromUrl(resolveUrl);

            if (resolveResponse == null) {
                log.info("Audius resolve API for playlist URL '{}' returned no data (playlist not found via fetchJsonFromUrl).", playlistUrl);
                return AudioReference.NO_TRACK; // Playlist URL did not resolve to a resource
            }

            JsonBrowser playlistNode = resolveResponse.get("data").index(0);

            // Validate the playlist node structure and get playlist info
            if (playlistNode == null || playlistNode.isNull() || playlistNode.get("playlist_name") == null || playlistNode.get("playlist_name").isNull() || playlistNode.get("id") == null || playlistNode.get("id").isNull()) {
                log.warn("Audius resolve API response for playlist URL '{}' is missing the main 'data' field, playlist name, or ID.", playlistUrl);
                throw new FriendlyException("Could not load Audius playlist: Invalid data structure in resolve response.", SUSPICIOUS, null);
            }

            String playlistTitle = playlistNode.get("playlist_name").text();
            String playlistId = playlistNode.get("id").text();
            String playlistPermalink = playlistNode.get("permalink").text();

            // Fetch all tracks for the playlist using the Get Playlist Tracks endpoint
            String playlistTracksUrl = String.format("%s/v1/playlists/%s/tracks?app_name=%s",
                    providerUrl,
                    URLEncoder.encode(playlistId, StandardCharsets.UTF_8),
                    URLEncoder.encode(APP_NAME, StandardCharsets.UTF_8));

            log.debug("Audius get playlist tracks API URL: {}", playlistTracksUrl);

            JsonBrowser playlistTracksResponse = fetchJsonFromUrl(playlistTracksUrl);

            if (playlistTracksResponse == null) {
                log.info("Audius get playlist tracks API for playlist ID '{}' returned no data.", playlistId);
                return new BasicAudioPlaylist(playlistTitle != null ? playlistTitle : "Unknown Playlist", Collections.emptyList(), null, false);
            }

            JsonBrowser tracksDataNode = playlistTracksResponse.get("data");

            List<JsonBrowser> trackNodes = Collections.emptyList();
            if (tracksDataNode != null && !tracksDataNode.isNull()) {
                try {
                    trackNodes = tracksDataNode.values();
                    log.debug("Successfully extracted {} track nodes from playlist tracks response for playlist '{}' (ID: {}).", trackNodes.size(), playlistTitle, playlistId);
                } catch (Exception e) {
                    log.error("Failed to get values from 'data' node in playlist tracks response for playlist ID '{}'. Response structure unexpected.", playlistId, e);
                    trackNodes = Collections.emptyList();
                }
            } else {
                log.warn("Audius get playlist tracks API response for playlist ID '{}' does not contain a non-null 'data' field.", playlistId);
            }


            if (trackNodes.isEmpty()) {
                log.info("Audius playlist tracks API for playlist '{}' (ID: {}) returned no track data or the data could not be processed as a list.", playlistTitle, playlistId);
                return new BasicAudioPlaylist(playlistTitle != null ? playlistTitle : "Unknown Playlist", Collections.emptyList(), null, false);
            }

            List<AudioTrack> tracks = new ArrayList<>();
            log.debug("Building AudioTrack objects for {} tracks in playlist '{}'.", trackNodes.size(), playlistTitle);

            for (JsonBrowser trackNode : trackNodes) {
                JsonBrowser idNode = trackNode != null ? trackNode.get("id") : null;
                if (trackNode == null || trackNode.isNull() || idNode == null || idNode.isNull() || idNode.text() == null || idNode.text().isEmpty()) {
                    log.warn("Skipping null or invalid track node in playlist '{}' (ID: {}) tracks response (missing ID). Node: {}", playlistTitle, playlistId, trackNode != null ? trackNode.toString() : "null");
                    continue;
                }

                try {
                    tracks.add(buildTrackFromNode(trackNode, playlistPermalink));
                } catch (FriendlyException e) {
                    log.warn("Skipping track ID '{}' in playlist '{}' (ID: {}) due to incomplete data: {}", idNode.text(), playlistTitle, playlistId, e.getMessage());
                } catch (Exception e) {
                    log.error("An unexpected error occurred processing track ID '{}' in playlist '{}' (ID: {}). Skipping track.", idNode.text(), playlistTitle, playlistId, e);
                }
            }

            if (tracks.isEmpty() && !trackNodes.isEmpty()) {
                log.warn("Playlist '{}' (ID: {}) contained track data, but no valid AudioTracks could be built.", playlistTitle, playlistId);
                return new BasicAudioPlaylist(playlistTitle != null ? playlistTitle : "Unknown Playlist", Collections.emptyList(), null, false);
            } else if (tracks.isEmpty()) {
                log.info("Playlist '{}' (ID: {}) is empty after processing (already logged).", playlistTitle, playlistId);
            }

            // Create and return the BasicAudioPlaylist
            log.info("Successfully loaded playlist '{}' with {} tracks from URL '{}' (ID: {}).", playlistTitle, tracks.size(), playlistUrl, playlistId);
            return new BasicAudioPlaylist(playlistTitle != null ? playlistTitle : "Unknown Playlist", tracks, null, false);

        } catch (FriendlyException e) {
            throw e;
        } catch (IOException e) {
            log.error("Failed to load Audius playlist URL '{}' due to IO error.", playlistUrl, e);
            throw new FriendlyException("Failed to load Audius playlist due to a network error.", FAULT, e);
        } catch (Exception e) {
            log.error("An unexpected error occurred during Audius playlist loading for '{}'.", playlistUrl, e);
            throw new FriendlyException("An unexpected error occurred while loading the Audius playlist.", FAULT, e);
        }
    }
  

    /**
     * Handles loading a complete Audius album from a URL.
     * Resolves the album URL, fetches track details directly from the album tracks endpoint.
     *
     * @param providerUrl The URL of the selected Audius discovery provider.
     * @param albumUrl    The full URL of the Audius album.
     * @return A BasicAudioPlaylist object containing the album information and its tracks.
     */
    private AudioItem handleAudiusAlbumUrl(String providerUrl, String albumUrl) {
        log.info("Attempting to load Audius album from URL {} using provider {}", albumUrl, providerUrl);

        String albumTitle = "Unknown Album"; // Default title
        String albumId = null;
        String albumPermalink = null;

        try {
            // Resolve the album URL to get basic info and ID
            String resolveUrl = String.format("%s/v1/resolve?url=%s&app_name=%s",
                    providerUrl,
                    URLEncoder.encode(albumUrl, StandardCharsets.UTF_8),
                    URLEncoder.encode(APP_NAME, StandardCharsets.UTF_8));

            log.debug("Audius resolve API URL for album: {}", resolveUrl);

            JsonBrowser resolveResponse = fetchJsonFromUrl(resolveUrl);

            if (resolveResponse == null || !resolveResponse.get("data").isList() || resolveResponse.get("data").values().isEmpty()) {
                log.info("Audius resolve API for album URL '{}' returned no data or invalid structure (album not found).", albumUrl);
                return AudioReference.NO_TRACK; // Album URL did not resolve to a resource
            }

            JsonBrowser albumNodeFromResolve = resolveResponse.get("data").index(0);

            // Validate the album node structure and get album info
            if (albumNodeFromResolve == null || albumNodeFromResolve.isNull() || albumNodeFromResolve.get("playlist_name") == null || albumNodeFromResolve.get("playlist_name").isNull() || albumNodeFromResolve.get("id") == null || albumNodeFromResolve.get("id").isNull() || !albumNodeFromResolve.get("is_album").asBoolean(false)) {
                log.warn("Audius resolve API response for album URL '{}' is missing essential fields or is not marked as an album.", albumUrl);
                throw new FriendlyException("Could not load Audius album: Invalid data structure in resolve response or not an album.", SUSPICIOUS, null);
            }

            albumTitle = albumNodeFromResolve.get("playlist_name").text(); // Album title is in playlist_name
            albumId = albumNodeFromResolve.get("id").text(); // Get the album ID
            albumPermalink = albumNodeFromResolve.get("permalink").text(); // Keep permalink for track source URL

            log.debug("Resolved album: '{}' (ID: {}), Permalink: {}", albumTitle, albumId, albumPermalink);

            // Fetch album tracks details directly from the /v1/playlists/{id}/tracks endpoint
            String albumTracksUrl = String.format("%s/v1/playlists/%s/tracks?app_name=%s",
                    providerUrl,
                    URLEncoder.encode(albumId, StandardCharsets.UTF_8),
                    URLEncoder.encode(APP_NAME, StandardCharsets.UTF_8));

            log.debug("Audius get album tracks API URL: {}", albumTracksUrl);

            JsonBrowser albumTracksResponse = fetchJsonFromUrl(albumTracksUrl);

            if (albumTracksResponse == null || !albumTracksResponse.get("data").isList()) {
                log.info("Audius get album tracks API for album ID '{}' returned no data or invalid structure.", albumId);
                return new BasicAudioPlaylist(albumTitle, Collections.emptyList(), null, false);
            }

            List<JsonBrowser> trackNodes = albumTracksResponse.get("data").values();

            List<AudioTrack> tracks = new ArrayList<>();
            if (!trackNodes.isEmpty()) {
                log.debug("Processing {} track nodes from /v1/playlists/{}/tracks response.", trackNodes.size(), albumId);
                for (JsonBrowser trackNode : trackNodes) {
                    JsonBrowser idNode = trackNode != null ? trackNode.get("id") : null;
                    if (trackNode == null || trackNode.isNull() || idNode == null || idNode.isNull() || idNode.text() == null || idNode.text().isEmpty()) {
                        log.warn("Skipping null or invalid track node from /v1/playlists/{}/tracks response for album '{}' (ID: {}). Node: {}", albumId, albumTitle, albumId, trackNode != null ? trackNode.toString() : "null");
                        continue;
                    }

                    try {
                        tracks.add(buildTrackFromNode(trackNode, albumPermalink));
                    } catch (FriendlyException e) {
                        log.warn("Skipping track ID '{}' in album '{}' (ID: {}) due to incomplete data: {}", idNode.text(), albumTitle, albumId, e.getMessage());
                    } catch (Exception e) {
                        log.error("An unexpected error occurred processing track ID '{}' from /v1/playlists/{}/tracks for album '{}' (ID: {}). Skipping track.", idNode.text(), albumId, albumTitle, albumId, e);
                    }
                }
                log.debug("Built {} valid AudioTracks for album '{}' (ID: {}).", tracks.size(), albumTitle, albumId);
            } else {
                log.info("Audius get album tracks API for album '{}' (ID: {}) returned an empty track list in the 'data' field.", albumTitle, albumId);
            }


            if (tracks.isEmpty() && !trackNodes.isEmpty()) {
                log.warn("Album '{}' (ID: {}) contained track data in /v1/playlists/{}/tracks, but no valid AudioTracks could be built.", albumTitle, albumId, albumId);
                return new BasicAudioPlaylist(albumTitle, Collections.emptyList(), null, false);
            } else if (tracks.isEmpty()) {
                log.info("Album '{}' (ID: {}) is empty after processing (either no data returned or no valid tracks built).", albumTitle, albumId);
            }


            // Create and return the BasicAudioPlaylist
            log.info("Successfully loaded album '{}' with {} tracks from URL '{}' (ID: {}).", albumTitle, tracks.size(), albumUrl, albumId);
            // Represent album as a playlist
            return new BasicAudioPlaylist(albumTitle, tracks, null, false);

        } catch (FriendlyException e) {
            throw e;
        } catch (IOException e) {
            log.error("Failed to load Audius album URL '{}' due to IO error.", albumUrl, e);
            throw new FriendlyException("Failed to load Audius album due to a network error.", FAULT, e);
        } catch (Exception e) {
            log.error("An unexpected error occurred during Audius album loading for '{}'.", albumUrl, e);
            throw new FriendlyException("An unexpected error occurred while loading the Audius album.", FAULT, e);
        }
    }

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

        // Match Audius albums
        Matcher albumUrlMatcher = audiusAlbumUrlPattern.matcher(reference.identifier);
        if (albumUrlMatcher.matches()) {
            log.info("Detected Audius album URL: {}", reference.identifier);
            return handleAudiusAlbumUrl(selectedDiscoveryProvider, reference.identifier);
        }

        // Match Audius playlists
        Matcher playlistUrlMatcher = audiusPlayListUrlPattern.matcher(reference.identifier);
        if (playlistUrlMatcher.matches()) {
            log.info("Detected Audius playlist URL: {}", reference.identifier);
            return handleAudiusPlaylistUrl(selectedDiscoveryProvider, reference.identifier);
        }

        // Handle other direct Audius URLs (including tracks resolved via /v1/resolve)
        Matcher urlMatcher = audiusUrlPattern.matcher(reference.identifier);
        if (urlMatcher.matches()) {
            log.info("Detected general Audius URL (attempting to resolve): {}", reference.identifier);
            return handleAudiusUrl(selectedDiscoveryProvider, reference.identifier);
        }

        return null;
    }

    // Helper method to extract artwork URL from the nested artwork object
    private String getArtworkUrl(JsonBrowser artworkNode) {
        if (artworkNode == null || artworkNode.isNull()) {
            return null;
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
                log.info("Audius API resource not found (null/empty response) for URL: {}", url);
                return null;
            }

            JsonBrowser error = response.get("error");
            if (error != null && !error.isNull()) {
                String errorMessage = error.text();
                log.warn("API endpoint {} returned error: {}", url, errorMessage);
                if (errorMessage != null && (errorMessage.toLowerCase().contains("not found") || errorMessage.toLowerCase().contains("resource for id"))) {
                    // If resource not found, treat as no results rather than a hard error
                    log.info("Audius API resource not found for URL: {}", url);
                    return null;
                }
                throw new IOException("Audius API error from " + url + ": " + errorMessage);
            }

            JsonBrowser data = response.get("data");
            if (data == null || data.isNull()) {
                log.warn("API endpoint {} response is missing non-null 'data' field after successful call (no top-level error). Response: {}", url, response);
                throw new IOException("Audius API response is missing data field from " + url);
            }

            return response;

        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            log.error("An unexpected error occurred fetching data from {}.", url, e);
            throw new IOException("An unexpected error occurred fetching data from Audius API " + url, e);
        }
    }

    // Handle Audius search queries (using custom AudiusAudioTrack)
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
                return AudioReference.NO_TRACK;
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

                if (trackNode == null || trackNode.isNull() || idNode == null || idNode.isNull() || idNode.text() == null || idNode.text().isEmpty()) {
                    log.warn("Skipping null or invalid track node in search results for query '{}' (missing ID). Node: {}", query, trackNode != null ? trackNode.toString() : "null");
                    continue;
                }

                try {
                    tracks.add(buildTrackFromNode(trackNode, sourceUrl));
                } catch (FriendlyException e) {
                    log.warn("Skipping track in search results for query '{}' due to incomplete data: {}", query, e.getMessage());
                }
            }

            if (tracks.isEmpty()) {
                log.info("Audius search API for query '{}' returned results, but no valid tracks could be processed.", query);
                return AudioReference.NO_TRACK;
            }

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