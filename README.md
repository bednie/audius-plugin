# Lavalink Audius Plugin

`audius-plugin` enables [Audius](https://audius.co/) as an audio source for Lavalink.

This document explains how to get and install the Audius plugin for Lavalink.

## Acknowledgements

This plugin was built from the [Lavalink Plugin Template](https://github.com/lavalink-devs/lavalink-plugin-template). Thank you to the Template contributors and to the Lavalink project. 

## Usage

Once the plugin is installed and Lavalink is restarted, you can use Audius as an audio source in your client.

This plugin supports Audius tracks, albums, and playlist URLs:
- `https://audius.co/user/track-title`
- `https://audius.co/user/album/album-title`
- `https://audius.co/user/playlist/playlist-title`


Also, you may use the `audsearch` prefix for searching:
`audsearch:some track by an artist`

Refer to your client's documentation for configuring search sources.

## Prerequisites
*   Java Development Kit (JDK) 11 or higher installed.
*   Git installed.
*   (For building from source) Gradle installed.

## Installation Options

### Option 1: Using the JitPack Dependency

This is the easiest method for users currently. You can configure Lavalink to download the plugin on startup by adding the following to your `application.yml` file under the `lavalink` section:

```yaml
lavalink:
  # ... other server configurations ...
  plugins:
    - dependency: com.github.bednie:audius-plugin:{VERSION} # Replace {VERSION} with the latest version from the audius-plugin "Releases" tab at https://jitpack.io/#bednie/audius-plugin/
      repository: https://jitpack.io # Specify JitPack as the source repository
  # ... other lavalink configurations ...
# ... other top-level configurations ...
```

Save your `application.yml` and restart your Lavalink server. Lavalink will read the `plugins` configuration and download the plugin JAR from JitPack.

### Option 2: Building from Source

If you prefer to build the plugin yourself and install it locally, you will use the `pluginsDir` feature of Lavalink, which is one of the ways Lavalink loads plugins.

1.  **Clone the Repository:**
    Clone the plugin's Git repository to your local machine.
    ```bash
    git clone https://github.com/bednie/audius-plugin.git
    cd audius-plugin
    ```

2.  **Build the Project:**
    Build the project using Gradle.
    ```bash
    ./gradlew build
    ```
    This command will compile the source code and package it into a JAR file. Look for the resulting JAR in the `build/libs` directory within your cloned project folder (e.g., `your_project_root/build/libs/audius-plugin-*.jar`).

3.  **Install the Plugin JAR:**
    Copy the built JAR file into the directory specified by the `pluginsDir` configuration in your Lavalink `application.yml`. This tells Lavalink where to find locally installed plugins.

    Your `application.yml` might include:
    ```yaml
    lavalink:
      server:
        # ... other configurations ...
      pluginsDir: "/plugins" # <--- Directory where Lavalink loads local plugins
    # ... other top-level configurations ...
    ```
    Copy the JAR file to the configured `pluginsDir`. For example, if your `pluginsDir` is `/plugins`, you would copy the JAR there. The exact command depends on your setup (e.g., `cp` for local, `scp` for remote). Refer to the official Lavalink documentation for detailed instructions on configuring `pluginsDir` and managing local plugins.

After copying the JAR, restart your Lavalink server to load the plugin from the specified directory.

### Help 
Please open an issue for help with this plugin.
